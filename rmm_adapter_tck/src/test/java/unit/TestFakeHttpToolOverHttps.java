package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.tck.ContractHost;
import com.rmm.adapter.tck.FakeHttpTool;
import com.rmm.adapter.tck.TckAdapterContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the fake tool over https with a required client certificate. The handshake fails without a
 * certificate or with an untrusted one; with the connection's identity every request shows the certificate
 * and the binding it names. With {@link FakeHttpTool#requireClientBindingIn} the tool answers only when a
 * header names exactly that binding. The TCK's context takes the binding identifier that the certificate
 * names, and the TCK's {@link ContractHost} passes the identity on.
 */
public class TestFakeHttpToolOverHttps {

    private static final String HEADER = "X-Test-Binding";
    private static TckTlsFixtures tls;
    private static String bindingId;
    private static TckTlsFixtures.Pair identity;
    private static TckTlsFixtures.Pair stranger;

    private FakeHttpTool tool;

    @BeforeAll
    static void certificates() {
        tls = TckTlsFixtures.generate();
        bindingId = UUID.randomUUID().toString();
        identity = TckTlsFixtures.bindingIdentity(bindingId);
        stranger = TckTlsFixtures.bindingIdentity(UUID.randomUUID().toString());
    }

    @BeforeEach
    public void start() throws Exception {
        tool = FakeHttpTool.startHttps(tls.leaf().withKeyPem(), identity.certificatePem());
        tool.text("/hello", "text/plain", "hello");
    }

    @AfterEach
    public void stop() {
        tool.close();
    }

    private static AdapterDescriptor descriptor(boolean withIdentity) {
        List<ConfigKey> keys = new java.util.ArrayList<>(List.of(
                ConfigKey.optional("tls.trust", ConfigType.CERTIFICATE, "", "The trust anchor of the tool")));
        if (withIdentity) {
            keys.add(ConfigKey.required("tls.client", ConfigType.CLIENT_IDENTITY, "The identity"));
        }
        return new AdapterDescriptor("HTTPSTOOL", "Https tool", "1.0.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT, false, false, false,
                        false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null), keys, List.of());
    }

    private TckAdapterContext context(TckTlsFixtures.Pair with) {
        TckAdapterContext.Builder builder = TckAdapterContext.builder(descriptor(with != null))
                .configuration(Map.of("tls.trust", tls.leaf().certificatePem()));
        if (with != null) {
            builder.clientIdentity(with.withKeyPem());
        }
        return builder.build();
    }

    private String get(TckAdapterContext context, Map<String, String> headers) throws AdapterException {
        AdapterRequest.Builder request = AdapterRequest.builder("GET", tool.uri("/hello"));
        headers.forEach(request::header);
        try {
            var response = context.http().send(request.build());
            return new String(response.body(), StandardCharsets.UTF_8) + " " + response.status();
        } finally {
            context.release();
        }
    }

    @Test
    public void theIdentityOfTheConnectionReachesTheToolWithItsBinding() throws Exception {
        TckAdapterContext context = context(identity);

        assertEquals(bindingId, context.binding().bindingId(), "the binding is the one the certificate names");
        assertEquals("hello 200", get(context, Map.of()));
        assertTrue(tool.uri("/").toString().startsWith("https://"));
        FakeHttpTool.Request seen = tool.requests().get(0);
        assertEquals(identity.certificate(), seen.clientCertificate());
        assertEquals(bindingId, seen.clientBinding());
        assertTrue(seen.stamp() != null, "the stamp of the network path stays required");
    }

    @Test
    public void withoutAClientCertificateTheHandshakeFails() {
        TckAdapterContext context = context(null);

        assertThrows(AdapterException.class, () -> get(context, Map.of()));

        assertEquals(List.of(), tool.requests(), "no request without a client certificate");
    }

    @Test
    public void aClientCertificateTheToolDoesNotTrustIsRefused() {
        TckAdapterContext context = context(stranger);

        assertThrows(AdapterException.class, () -> get(context, Map.of()));

        assertEquals(List.of(), tool.requests());
    }

    @Test
    public void theBindingHeaderMustNameTheBindingOfTheCertificate() throws Exception {
        tool.requireClientBindingIn(HEADER);

        assertEquals(403, Integer.parseInt(get(context(identity), Map.of()).replaceAll(".* ", "")));
        assertEquals(403, Integer.parseInt(get(context(identity), Map.of(HEADER, "someone-else"))
                .replaceAll(".* ", "")));
        assertEquals(2, tool.refusedBindings());
        assertEquals(0, tool.refused(), "the stamp was there");

        assertEquals("hello 200", get(context(identity), Map.of(HEADER, bindingId)));
    }

    @Test
    public void aBindingIdOfTheTestWinsOverTheCertificate() {
        TckAdapterContext context = TckAdapterContext.builder(descriptor(true)).bindingId("own")
                .clientIdentity(identity.withKeyPem()).build();
        try {
            assertEquals("own", context.binding().bindingId());
        } finally {
            context.release();
        }
    }

    @Test
    public void theHostOfTheTckPassesTheIdentityOnAndAnotherHostRefusesIt() {
        var context = ContractHost.tck().context(descriptor(true), Map.of(), Map.of(),
                Optional.of(identity.withKeyPem()));
        try {
            assertEquals(bindingId, context.binding().bindingId());
        } finally {
            context.release();
        }

        ContractHost other = (descriptor, configuration, platform) -> ContractHost.tck().context(descriptor,
                configuration, platform);
        assertThrows(UnsupportedOperationException.class, () -> other.context(descriptor(true), Map.of(), Map.of(),
                Optional.of(identity.withKeyPem())));
    }
}
