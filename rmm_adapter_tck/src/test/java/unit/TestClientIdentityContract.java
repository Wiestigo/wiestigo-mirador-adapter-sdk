package unit;

import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInitialisationException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.FakeHttpTool;
import com.rmm.adapter.tck.TckViolation;
import com.rmm.adapter.tck.reference.NetworkTool;
import com.rmm.adapter.tck.reference.NetworkToolAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the TCK's cases for an adapter with a client identity. The network reference adapter, declared with
 * a {@code CLIENT_IDENTITY} key and a trust anchor {@code tls.trust}, passes every case against a tool that
 * requires a client certificate over https ({@link FakeHttpTool#startHttps}). It uses the identity from
 * {@link #clientIdentity()} and, for case 1, a second one from {@link #secondBindingClientIdentity()}.
 * Without {@code clientIdentity()} the cases cannot build the context of such a type ("The connection has
 * no client identity"), and no request leaves over plain http.
 *
 * <p>The second binding of case 1 needs a different identity that names a different binding. With the same
 * identity (the default) or one whose certificate names the same binding, a service that keeps its data
 * per {@code X-RMM-Binding} would see the same binding twice and case 1 would prove nothing; it then fails
 * with a pointer to {@code secondBindingClientIdentity()}.</p>
 */
public class TestClientIdentityContract extends AdapterContractTest {

    private static String firstBinding;
    private static TckTlsFixtures.Pair first;
    private static TckTlsFixtures.Pair second;
    private static TckTlsFixtures.Pair toolLeaf;

    private NetworkTool tool;

    @BeforeAll
    static void identities() {
        firstBinding = UUID.randomUUID().toString();
        first = TckTlsFixtures.bindingIdentity(firstBinding);
        second = TckTlsFixtures.bindingIdentity(UUID.randomUUID().toString());
        toolLeaf = TckTlsFixtures.generate().leaf();
    }

    @BeforeEach
    public void startTheTool() throws IOException {
        tool = NetworkTool.startHttps(toolLeaf.withKeyPem(), first.certificatePem() + second.certificatePem());
    }

    @AfterEach
    public void stopTheTool() {
        tool.close();
    }

    @Override
    protected ToolAdapter adapter() {
        return new IdentityNetworkToolAdapter();
    }

    @Override
    protected Map<String, String> configuration() {
        return Map.of("tool.url", tool.address(NetworkTool.FIRST).toString(),
                "tool.token", tool.token(NetworkTool.FIRST), "tls.trust", toolLeaf.certificatePem());
    }

    @Override
    protected Optional<Map<String, String>> secondBindingConfiguration() {
        return Optional.of(Map.of("tool.url", tool.address(NetworkTool.SECOND).toString(),
                "tool.token", tool.token(NetworkTool.SECOND), "tls.trust", toolLeaf.certificatePem()));
    }

    @Override
    protected Optional<String> clientIdentity() {
        return Optional.of(first.withKeyPem());
    }

    @Override
    protected Optional<String> secondBindingClientIdentity() {
        return Optional.of(second.withKeyPem());
    }

    @Override
    protected Optional<FakeHttpTool> networkTool() {
        return Optional.of(tool.http());
    }

    @Override
    protected Optional<SyncRunContext> liveRunContext() {
        return Optional.of(new SyncRunContext(UUID.randomUUID(), "NET-PRJ"));
    }

    @Override
    protected void expireToolState() {
        //nothing expires
    }

    /** Case 3 scans the reference adapter and its wrapper, not the whole test tree. */
    @Override
    protected Set<Class<?>> adapterClasses() {
        return Set.of(IdentityNetworkToolAdapter.class, NetworkToolAdapter.class);
    }

    /**
     * Every request of a binding presents the identity of its connection, and the binding's identifier is
     * the one its certificate names, as in the host.
     */
    @Test
    public void eachBindingShowsTheIdentityOfItsConnection() throws Exception {
        var context = context(adapter().descriptor(), configuration());
        AdapterInstance instance = adapter().bind(context);
        try {
            SyncRunContext run = new SyncRunContext(UUID.randomUUID(), "NET-PRJ");
            context.inRun(run, () -> instance.reader().orElseThrow().readArtifacts(run,
                    com.rmm.adapter.api.ReadScope.full(), null));
        } finally {
            instance.close();
            context.release();
        }

        assertEquals(first.certificate().getSubjectAlternativeNames().iterator().next().get(1),
                "urn:rmm:binding:" + context.binding().bindingId(), "the binding is the one its certificate names");
        assertTrue(!tool.http().requests().isEmpty());
        for (FakeHttpTool.Request request : tool.http().requests()) {
            assertEquals(first.certificate(), request.clientCertificate(), request.toString());
            assertEquals(context.binding().bindingId(), request.clientBinding(), request.toString());
        }
    }

    /** Case 1 fails with the pointer when the second binding has no identity of its own (the default is the first). */
    @Test
    public void caseOneRefusesTheSameIdentityForTheSecondBinding() {
        TestClientIdentityContract sameIdentity = new TestClientIdentityContract() {
            @Override
            protected Optional<String> secondBindingClientIdentity() {
                return clientIdentity();
            }
        };
        sameIdentity.tool = tool;

        TckViolation violation = assertThrows(TckViolation.class,
                sameIdentity::twoBindingsWorkSideBySideWithoutCrossTalk);

        assertTrue(violation.getMessage().contains("secondBindingClientIdentity()"), violation.getMessage());
    }

    /** A different identity that names the same binding proves just as little: case 1 fails. */
    @Test
    public void caseOneRefusesASecondIdentityThatNamesTheSameBinding() {
        TckTlsFixtures.Pair sameBinding = TckTlsFixtures.bindingIdentity(firstBinding);
        TestClientIdentityContract sameName = new TestClientIdentityContract() {
            @Override
            protected Optional<String> secondBindingClientIdentity() {
                return Optional.of(sameBinding.withKeyPem());
            }
        };
        sameName.tool = tool;

        TckViolation violation = assertThrows(TckViolation.class, sameName::twoBindingsWorkSideBySideWithoutCrossTalk);

        assertTrue(violation.getMessage().contains("secondBindingClientIdentity()"), violation.getMessage());
        assertTrue(violation.getMessage().contains(firstBinding), violation.getMessage());
    }

    /** The network reference adapter with a trust anchor and a client identity; otherwise unchanged. */
    static final class IdentityNetworkToolAdapter implements ToolAdapter {

        private final NetworkToolAdapter reference = new NetworkToolAdapter();

        @Override
        public AdapterDescriptor descriptor() {
            AdapterDescriptor plain = reference.descriptor();
            List<ConfigKey> keys = new ArrayList<>(plain.configKeys());
            keys.add(ConfigKey.optional("tls.trust", ConfigType.CERTIFICATE, "", "The trust anchor of the tool"));
            keys.add(ConfigKey.required("tls.client", ConfigType.CLIENT_IDENTITY, "The identity of the connection"));
            return new AdapterDescriptor("IDENTITYTOOL", plain.displayName(), plain.adapterVersion(),
                    plain.apiMajorVersion(), plain.capabilities(), keys, plain.identifierKinds());
        }

        @Override
        public AdapterInstance bind(AdapterContext context) throws AdapterInitialisationException {
            return reference.bind(context);
        }
    }
}
