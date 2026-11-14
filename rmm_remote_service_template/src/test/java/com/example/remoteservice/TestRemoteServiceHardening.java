package com.example.remoteservice;

import com.rmm.remote.tck.RemoteServiceIdentities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * What the template guards beyond the five cases of the TCK: a failed single
 * create arrives as non-2xx with its reason, the certificate request names no
 * binding, peers that stay silent in the handshake do not hold the worker
 * threads, a refusal closes its connection, and an unknown path or a wrong
 * method is refused before the body is read.
 */
public class TestRemoteServiceHardening {

    /** The admission time limit of these tests; the default is {@link RemoteService#ADMISSION_TIME_LIMIT}. */
    private static final Duration ADMISSION = Duration.ofMillis(300);

    private Path pins;
    private RemoteService service;

    @BeforeEach
    public void startService() throws IOException {
        pins = ServiceFixture.pinDirectory(List.of("connection-a", "connection-b"),
                RemoteServiceIdentities.pinned());
        service = new RemoteService(ServiceFixture.settings(PinStore.ofDirectory(pins))) {
            @Override
            protected Duration admissionTimeLimit() {
                return ADMISSION;
            }
        }.start();
    }

    @AfterEach
    public void stopService() {
        service.close();
        ServiceFixture.removeDirectory(pins);
    }

    /**
     * Protocol: only an update and a comment may carry an {@code error} with
     * 2xx; any other single call fails with non-2xx, and the reason is the
     * {@code message} of the body (Mirador shows it in the run).
     */
    @Test
    public void aFailedSingleCreateAnswersNon2xxWithItsReason() throws Exception {
        RemoteServiceIdentities.Identity a = RemoteServiceIdentities.first();
        HttpClient client = ServiceFixture.client(a);

        HttpResponse<String> artifact = post(client, a, "/write/artifact/create",
                "{\"artifact\":{\"name\":\"Requirement\",\"label\":\"Requirement\"}}");
        assertEquals(422, artifact.statusCode(), artifact.body());
        assertEquals(Map.of("message", "the artifact carries no fields array"), Json.parseObject(artifact.body()));

        HttpResponse<String> link = post(client, a, "/write/relationship/create",
                "{\"relationship\":{\"fromItemId\":\"WI-404\",\"toItemId\":\"WI-405\"}}");
        assertEquals(422, link.statusCode(), link.body());
        assertTrue(Json.write(Json.parseObject(link.body())).contains("does not know"), link.body());

        //In a batch only the element fails; the call answers 200
        HttpResponse<String> batch = post(client, a, "/write/artifacts/create",
                "{\"artifacts\":[{\"name\":\"Requirement\",\"label\":\"Requirement\"}]}");
        assertEquals(200, batch.statusCode(), batch.body());
        assertTrue(batch.body().contains("the artifact carries no fields array"), batch.body());
    }

    /**
     * The values of the binding's keys (object {@code connection} in the
     * envelope): the template reads them with
     * {@link RemoteService#connectionOf}. It answers a {@code connection}
     * that is not an object with 400 and a reason, and accepts an object.
     */
    @Test
    public void theConnectionOfTheEnvelopeIsAnObject() throws Exception {
        RemoteServiceIdentities.Identity a = RemoteServiceIdentities.first();
        HttpClient client = ServiceFixture.client(a);

        HttpResponse<String> refused = post(client, a, "/read/artifacts",
                "{\"cursor\":null,\"connection\":\"project.filter=A\"}");
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("connection"), refused.body());

        HttpResponse<String> read = post(client, a, "/read/artifacts",
                "{\"cursor\":null,\"connection\":{\"project.filter\":\"A\",\"page.limit\":50}}");
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(Map.of("project.filter", "A", "page.limit", 50L),
                RemoteService.connectionOf(Json.parseObject("{\"connection\":{\"project.filter\":\"A\","
                        + "\"page.limit\":50}}")));
        assertEquals(Map.of(), RemoteService.connectionOf(Json.parseObject("{\"cursor\":null}")),
                "without connection: no values");
    }

    /**
     * The service names no certificates in its certificate request;
     * otherwise every peer would learn the bindings of all connections before
     * any check. The key manager of the JDK presents its certificate anyway
     * (as Mirador does, which does not read the list).
     */
    @Test
    public void theCertificateRequestNamesNoBinding() throws Exception {
        assertEquals(0, PinStore.of(RemoteServiceIdentities.pinned()).trustManager().getAcceptedIssuers().length);
        RemoteServiceIdentities.Identity a = RemoteServiceIdentities.first();
        List<List<Principal>> asked = new CopyOnWriteArrayList<>();
        HttpClient client = ServiceFixture.client(a, asked::add);

        assertEquals(200, client.send(request(a, "/descriptor").GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());
        assertFalse(asked.isEmpty(), "the handshake asked for a client certificate");
        assertEquals(List.of(), asked.stream().flatMap(List::stream).toList(),
                "the certificate request must name no pinned certificate");
    }

    /**
     * As many peers as the service has worker threads each send half a
     * ClientHello and stay silent. After the admission time limit the service
     * closes each of these connections, and a connection with a pinned
     * certificate gets through. The test waits only for the close (at most
     * the socket timeout, never a sleep).
     */
    @Test
    public void silentHandshakesDoNotHoldTheWorkers() throws Exception {
        URI address = URI.create(service.baseUrl());
        List<Socket> silent = new ArrayList<>();
        try {
            for (int index = 0; index < RemoteService.THREADS; index++) {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", address.getPort()), 5_000);
                socket.setSoTimeout(5_000);
                OutputStream out = socket.getOutputStream();
                //A TLS handshake record that announces 512 bytes and sends 4 of them
                out.write(new byte[]{0x16, 0x03, 0x01, 0x02, 0x00, 0x01, 0x00, 0x01, (byte) 0xfc});
                out.flush();
                silent.add(socket);
            }
            for (Socket socket : silent) {
                assertClosedByTheService(socket);
            }

            RemoteServiceIdentities.Identity a = RemoteServiceIdentities.first();
            assertEquals(200, ServiceFixture.client(a).send(request(a, "/descriptor").GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode());
        } finally {
            for (Socket socket : silent) {
                socket.close();
            }
        }
    }

    /**
     * A refusal (401, 403) is answered before the service reads the request
     * body; it never reads the body from an unauthenticated connection. If
     * the {@link com.sun.net.httpserver.HttpsServer} of the JDK reads the rest
     * of the body only AFTER the answer and the same read already picks up
     * the encrypted record of the next request, that record stays in its TLS
     * buffer: the server waits for new bytes on the socket that never come,
     * and the next request on the connection stays unanswered (in the TCK:
     * case 2, 30 s until the client times out). The service therefore closes
     * the connection after every refusal ({@code Connection: close}).
     *
     * <p>The test creates exactly this situation: the headers of the refused
     * request alone, then the body and the next request as two TLS records in
     * ONE write. The service must answer the next request or close the
     * connection, and must never stay silent.</p>
     */
    @Test
    public void aRefusalClosesTheConnectionSoALateBodyCannotStallTheNextRequest() throws Exception {
        RemoteServiceIdentities.Identity a = RemoteServiceIdentities.first();
        int port = URI.create(service.baseUrl()).getPort();
        String body = "{\"cursor\":null}";
        CorkedSocket tcp = new CorkedSocket();
        tcp.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
        try (SSLSocket tls = (SSLSocket) ServiceFixture.tls(a, issuers -> { }).getSocketFactory()
                .createSocket(tcp, "127.0.0.1", port, true)) {
            tls.setEnabledProtocols(new String[]{"TLSv1.3"});
            tls.setSoTimeout(5_000);
            tls.startHandshake();
            OutputStream out = tls.getOutputStream();
            InputStream in = tls.getInputStream();
            //Without X-RMM-Binding: 403 before the service reads the body; the body follows only afterwards
            out.write(("POST /read/artifacts HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nX-RMM-Adapter-Protocol: 4"
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String refusal = responseHead(in);
            assertTrue(refusal.startsWith("HTTP/1.1 403"), refusal);

            //Body and next request: two TLS records, one write on the socket
            tcp.cork();
            out.write(body.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            out.write(("GET /health HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nX-RMM-Adapter-Protocol: 4\r\n"
                    + RemoteService.BINDING_HEADER + ": " + a.bindingId() + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            tcp.uncork();

            String next;
            try {
                next = in.read() == -1 ? "closed" : "answered";
            } catch (SocketTimeoutException silent) {
                next = "silent";
            } catch (IOException closed) {
                next = "closed";
            }
            assertTrue(next.equals("answered") || next.equals("closed"), "the service neither answered the next"
                    + " request on the connection of a refusal nor closed it");
            assertTrue(refusal.toLowerCase(Locale.ROOT).contains("\r\nconnection: close\r\n"),
                    "a refusal must close its connection: " + refusal);
            assertEquals("closed", next, "the service closes the connection of a refusal");
        }
    }

    /**
     * An unknown path ({@code 404}) and a wrong method ({@code 405}) are
     * refused before the service reads the request body, like every other
     * refusal. The request announces a body beyond
     * {@link RemoteService#MAX_REQUEST_BYTES} and never sends it: the service
     * answers all the same, names the path or the method (not the size of
     * the body), and closes the connection.
     */
    @Test
    public void anUnknownPathOrAWrongMethodIsRefusedBeforeTheBodyIsRead() throws Exception {
        RemoteServiceIdentities.Identity a = RemoteServiceIdentities.first();

        String unknownPath = answerToAPostWhoseBodyNeverComes(a, "/read/nothing");
        assertTrue(unknownPath.startsWith("HTTP/1.1 404"), unknownPath);
        assertTrue(unknownPath.toLowerCase(Locale.ROOT).contains("\r\nconnection: close\r\n"),
                "a refusal must close its connection: " + unknownPath);

        String wrongMethod = answerToAPostWhoseBodyNeverComes(a, "/descriptor");
        assertTrue(wrongMethod.startsWith("HTTP/1.1 405"), wrongMethod);
        assertTrue(wrongMethod.toLowerCase(Locale.ROOT).contains("\r\nallow: get\r\n"), wrongMethod);
        assertTrue(wrongMethod.toLowerCase(Locale.ROOT).contains("\r\nconnection: close\r\n"),
                "a refusal must close its connection: " + wrongMethod);
    }

    /**
     * The head of the answer to {@code POST path} of the connection
     * {@code identity} with the right binding header, when the request
     * announces a body of one byte more than the service accepts and sends
     * none of it. A service that waits for the body runs into the socket
     * timeout.
     */
    private String answerToAPostWhoseBodyNeverComes(RemoteServiceIdentities.Identity identity, String path)
            throws Exception {
        int port = URI.create(service.baseUrl()).getPort();
        try (SSLSocket tls = (SSLSocket) ServiceFixture.tls(identity, issuers -> { }).getSocketFactory()
                .createSocket("127.0.0.1", port)) {
            tls.setEnabledProtocols(new String[]{"TLSv1.3"});
            tls.setSoTimeout(5_000);
            tls.startHandshake();
            OutputStream out = tls.getOutputStream();
            out.write(("POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nX-RMM-Adapter-Protocol: 4\r\n"
                    + RemoteService.BINDING_HEADER + ": " + identity.bindingId()
                    + "\r\nContent-Type: application/json\r\nContent-Length: "
                    + (RemoteService.MAX_REQUEST_BYTES + 1) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return responseHead(tls.getInputStream());
        }
    }

    /** Reads the head and body of an answer with Content-Length; returns the head including the CRLFs. */
    private static String responseHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next == -1) {
                fail("the service closed the connection before its answer: " + head);
            }
            head.append((char) next);
        }
        long length = 0;
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Long.parseLong(line.substring("content-length:".length()).trim());
            }
        }
        assertEquals(length, in.readNBytes((int) length).length, "the body of the answer");
        return head.toString();
    }

    /**
     * A socket whose writes can be held back: between {@link #cork} and
     * {@link #uncork} it collects what the TLS layer writes through it (also
     * across {@code flush}) and then passes it on in one write.
     */
    private static final class CorkedSocket extends Socket {

        private final ByteArrayOutputStream held = new ByteArrayOutputStream();
        private boolean corked;
        private OutputStream wire;

        synchronized void cork() {
            corked = true;
        }

        synchronized void uncork() throws IOException {
            corked = false;
            wire.write(held.toByteArray());
            wire.flush();
            held.reset();
        }

        @Override
        public synchronized OutputStream getOutputStream() throws IOException {
            if (wire == null) {
                wire = super.getOutputStream();
            }
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] bytes, int offset, int length) throws IOException {
                    synchronized (CorkedSocket.this) {
                        if (corked) {
                            held.write(bytes, offset, length);
                        } else {
                            wire.write(bytes, offset, length);
                        }
                    }
                }

                @Override
                public void flush() throws IOException {
                    synchronized (CorkedSocket.this) {
                        if (!corked) {
                            wire.flush();
                        }
                    }
                }
            };
        }
    }

    /** The service closes the connection (end of stream or reset); the socket timeout means it never did. */
    private static void assertClosedByTheService(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        try {
            while (in.read() != -1) {
                //What the service still sends does not matter; only the end does
            }
        } catch (SocketTimeoutException held) {
            fail("the service still holds a connection that stalled in the handshake");
        } catch (SocketException reset) {
            //Reset: closed as well
        }
    }

    private HttpResponse<String> post(HttpClient client, RemoteServiceIdentities.Identity identity, String path,
                                      String body) throws Exception {
        return client.send(request(identity, path).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpRequest.Builder request(RemoteServiceIdentities.Identity identity, String path) {
        return HttpRequest.newBuilder(URI.create(service.baseUrl() + path)).timeout(Duration.ofSeconds(10))
                .header("X-RMM-Adapter-Protocol", "4").header(RemoteService.BINDING_HEADER, identity.bindingId());
    }
}
