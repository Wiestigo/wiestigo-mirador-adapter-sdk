package com.example.remoteservice;

import com.rmm.remote.tck.RemoteServiceIdentities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Certificate rotation at the operator's side ({@code docs/REMOTE_PROTOCOL.md},
 * "Certificate rotation"), as the README describes it: add the new
 * certificate of the same binding (both are accepted without a restart and
 * both see the same data, because it is the same binding) and remove the old
 * one only when the administration shows it as retired; after that the
 * service refuses it, even on an open connection.
 *
 * <p>The old and the new certificate: {@link RemoteServiceIdentities#first()}
 * and {@link RemoteServiceIdentities#forgedFirst()} name the same binding
 * with different keys, exactly like a renewed certificate.</p>
 */
public class TestCertificateRotation {

    private Path pins;
    private RemoteService service;

    @BeforeEach
    public void startService() throws IOException {
        pins = ServiceFixture.pinDirectory(List.of("connection-a-old"),
                List.of(RemoteServiceIdentities.first().certificate()));
        service = new RemoteService(ServiceFixture.settings(PinStore.ofDirectory(pins))).start();
    }

    @AfterEach
    public void stopService() {
        service.close();
        ServiceFixture.removeDirectory(pins);
    }

    @Test
    public void bothCertificatesOfABindingCountUntilTheOldOneIsRemoved() throws Exception {
        RemoteServiceIdentities.Identity old = RemoteServiceIdentities.first();
        RemoteServiceIdentities.Identity renewed = RemoteServiceIdentities.forgedFirst();
        HttpClient oldClient = ServiceFixture.client(old);
        String id = (String) post(oldClient, old, "/write/artifact/create", "{\"artifact\":{\"name\":\"Requirement\","
                + "\"label\":\"Requirement\",\"fields\":[{\"name\":\"Name\",\"label\":\"Name\",\"type\":\"STRING\","
                + "\"value\":\"before the switch\"}]}}").get("externalId");
        assertFalse(answers(ServiceFixture.client(renewed), renewed), "the renewed certificate is not pinned yet");

        //1. Accept both: add the new one, without a restart
        ServiceFixture.pin(pins, "connection-a-new", renewed.certificate());
        HttpClient renewedClient = ServiceFixture.client(renewed);
        assertTrue(answers(renewedClient, renewed), "the renewed certificate is pinned next to the old one");
        assertTrue(answers(oldClient, old), "the old certificate still counts");
        assertTrue(Json.write(post(renewedClient, renewed, "/read/artifacts", "{\"cursor\":null}"))
                .contains("before the switch"), "the renewed certificate names the same binding: the same data");

        //3. Remove the old one when the administration shows it as RETIRED
        Files.delete(pins.resolve("connection-a-old.pem"));
        assertFalse(answers(oldClient, old), "the old certificate is refused, also on an open connection");
        assertTrue(answers(renewedClient, renewed), "the renewed certificate goes on");
        assertTrue(Json.write(post(renewedClient, renewed, "/read/artifacts", "{\"cursor\":null}")).contains(id));
    }

    /** Whether the service answers {@code /descriptor} (2xx); 401/403 or a failed handshake: no. */
    private boolean answers(HttpClient client, RemoteServiceIdentities.Identity identity) throws Exception {
        try {
            int status = client.send(request(identity, "/descriptor").GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            assertTrue(status == 200 || status == 401 || status == 403, "status " + status);
            return status == 200;
        } catch (IOException refusedAtTheHandshake) {
            return false;
        }
    }

    private Map<String, Object> post(HttpClient client, RemoteServiceIdentities.Identity identity, String path,
                                     String body) throws Exception {
        HttpResponse<String> response = client.send(request(identity, path)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return Json.parseObject(response.body());
    }

    private HttpRequest.Builder request(RemoteServiceIdentities.Identity identity, String path) {
        return HttpRequest.newBuilder(URI.create(service.baseUrl() + path)).timeout(Duration.ofSeconds(30))
                .header("X-RMM-Adapter-Protocol", "4").header(RemoteService.BINDING_HEADER, identity.bindingId());
    }
}
