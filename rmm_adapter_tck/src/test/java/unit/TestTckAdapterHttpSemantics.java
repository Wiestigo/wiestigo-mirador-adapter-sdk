package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.Body;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.StreamResponse;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.tck.AdapterHttpSemantics;
import com.rmm.adapter.tck.FakeHttpTool;
import com.rmm.adapter.tck.TckAdapterContext;
import com.rmm.adapter.tck.TckAdapterHttp;
import com.rmm.adapter.tck.TckTlsClockTestAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the semantics of the host's HTTP path ({@link AdapterHttpSemantics}) against the HTTP path of the TCK.
 * The host runs the same checks, so the HTTP path an adapter is tested with cannot differ from the one it gets
 * in the host. The tests declared here cover what concerns only the HTTP path of the TCK.
 */
public class TestTckAdapterHttpSemantics extends AdapterHttpSemantics {

    private static AdapterDescriptor descriptor() {
        return new AdapterDescriptor("semtool", "Semantics tool", "1.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
                        false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null),
                List.of(), List.of());
    }

    @Override
    protected Subject subject(Limits limits) {
        TckAdapterContext context = TckAdapterContext.builder(descriptor())
                .network(new TckAdapterHttp.Settings(limits.responseTimeout(), limits.maxResponseBytes()))
                .build();
        return new Subject() {
            @Override
            public AdapterHttp http() {
                return context.http();
            }

            @Override
            public <T> T inRun(Duration remaining, RunWork<T> work) throws Exception {
                SyncRunContext run = new SyncRunContext(UUID.randomUUID(), "SEM-PRJ", Instant.now().plus(remaining));
                return context.inRun(run, () -> work.run(run));
            }

            @Override
            public long attempts() {
                return context.httpRequests();
            }

            @Override
            public List<Duration> retryWaits() {
                return ((TckAdapterHttp) context.http()).retryWaits();
            }

            @Override
            public void close() {
                context.release();
            }
        };
    }

    // ------------------------------------------------------------------
    // Only the HTTP path of the TCK
    // ------------------------------------------------------------------

    private final List<AutoCloseable> own = new CopyOnWriteArrayList<>();

    @AfterEach
    public void closeOwn() throws Exception {
        for (AutoCloseable closeable : own) {
            closeable.close();
        }
        own.clear();
    }

    private FakeHttpTool ownTool() throws IOException {
        FakeHttpTool tool = FakeHttpTool.startWithoutBindingHeader();
        own.add(tool);
        return tool;
    }

    private TckAdapterContext ownContext() {
        TckAdapterContext context = TckAdapterContext.of(descriptor(), Map.of());
        own.add(context::release);
        return context;
    }

    /**
     * The failure messages of a response stream name method and address, without the query. The tool stalls
     * after the first byte until the test ends, so the idle timeout of 2 s fires whatever the load is.
     */
    @Test
    public void theFailuresOfAResponseStreamNameMethodAndAddress() throws Exception {
        CountDownLatch endOfTest = new CountDownLatch(1);
        FakeHttpTool tool = ownTool().route("/stalling", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write('a');
            out.flush();
            try {
                endOfTest.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            out.close();
        });
        own.add(0, endOfTest::countDown);
        TckAdapterContext context = ownContext();

        try (StreamResponse stream = context.http().open(AdapterRequest.builder("GET",
                tool.uri("/stalling?token=secret-4711")).timeout(Duration.ofSeconds(2)).build())) {
            stream.body().read();
            IOException idle = assertThrows(IOException.class, () -> stream.body().read());
            assertTrue(idle.getMessage().startsWith("GET " + AdapterRequest.forLog(tool.uri("/stalling?q"))
                    + ": no data within"), idle.getMessage());
            assertTrue(!idle.getMessage().contains("secret-4711"), idle.getMessage());
        }
    }

    /**
     * The stream bound: an ordinary request through {@code send} has the response timeout (here 1 s) and then
     * fails as retryable; a request through {@code open} and an upload (body as a stream) have the stream
     * bound (here 4 s) and wait for the answer that comes after 2 s. Without a stream bound of its own, the
     * response timeout applies to both; the defaults are those of the host (120 s and 300 s).
     */
    @Test
    public void streamsAndUploadsHaveTheStreamBoundOrdinaryRequestsTheResponseTimeout() throws Exception {
        FakeHttpTool tool = ownTool().route("/slow", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            FakeHttpTool.respond(exchange, 200, "text/plain", bytes("late"));
        });
        TckAdapterContext context = TckAdapterContext.builder(descriptor())
                .network(new TckAdapterHttp.Settings(Duration.ofSeconds(1), 32 * 1024 * 1024, 3,
                        TckAdapterHttp.Settings.SHORTENED_WAIT, Duration.ofSeconds(4)))
                .build();
        own.add(context::release);
        AdapterHttp http = context.http();

        long started = System.nanoTime();
        com.rmm.adapter.api.AdapterException ordinary = assertThrows(com.rmm.adapter.api.AdapterException.class,
                () -> http.send(AdapterRequest.get(tool.uri("/slow"))));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(ordinary.isRetryable(), ordinary.getMessage());
        assertTrue(ordinary.getMessage().contains("no answer within 1 s"), ordinary.getMessage());
        assertTrue(elapsedMillis < 1900, "the response timeout, not the stream bound: " + elapsedMillis + " ms");

        try (StreamResponse stream = http.open(AdapterRequest.get(tool.uri("/slow")))) {
            assertEquals("late", new String(stream.body().readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] file = bytes("upload");
        AdapterResponse upload = http.send(AdapterRequest.post(tool.uri("/slow"), Body.ofStream(file.length,
                "application/octet-stream", () -> new ByteArrayInputStream(file))));
        assertEquals(200, upload.status());

        assertEquals(Duration.ofSeconds(300), TckAdapterHttp.Settings.defaults().streamResponseTimeout());
        assertEquals(Duration.ofSeconds(120), TckAdapterHttp.Settings.defaults().responseTimeout());
        assertEquals(Duration.ofSeconds(7), new TckAdapterHttp.Settings(Duration.ofSeconds(7), 1)
                .streamResponseTimeout(), "without a stream bound of its own, one timeout for both");
        assertEquals(Duration.ofSeconds(9), TckAdapterHttp.Settings.defaults()
                .withStreamResponseTimeout(Duration.ofSeconds(9)).forRateLimit(null)
                .forConnection(null, java.util.OptionalInt.empty()).streamResponseTimeout(),
                "the stream bound survives the tailoring to RateLimit and http.retries");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // TLS material, client identity and pinning
    // ------------------------------------------------------------------

    /** The keys of the trust anchor and of the client identity in the descriptor of these tests. */
    private static final String ANCHOR_KEY = "tls.anchor";
    private static final String IDENTITY_KEY = "tls.client";

    /** The certificates, generated once per class with the keytool of the JDK. */
    private static TckTlsFixtures tls;

    @BeforeAll
    static void generateTheCertificates() {
        tls = TckTlsFixtures.generate();
    }

    /** A type with a trust anchor ({@code CERTIFICATE}) and, if requested, a client identity. */
    private static AdapterDescriptor tlsDescriptor(boolean withIdentity) {
        List<ConfigKey> keys = new ArrayList<>(List.of(
                ConfigKey.optional(ANCHOR_KEY, ConfigType.CERTIFICATE, "", "Trust anchor")));
        if (withIdentity) {
            keys.add(ConfigKey.required(IDENTITY_KEY, ConfigType.CLIENT_IDENTITY, "Client identity"));
        }
        return new AdapterDescriptor("tlstool", "TLS tool", "1.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
                        false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null),
                keys, List.of());
    }

    private TckAdapterContext tlsContext(boolean withIdentity, TckTlsFixtures.Pair anchor,
                                         TckTlsFixtures.Pair identity) {
        TckAdapterContext.Builder builder = TckAdapterContext.builder(tlsDescriptor(withIdentity))
                .configuration(anchor == null ? Map.of() : Map.of(ANCHOR_KEY, anchor.certificatePem()));
        if (identity != null) {
            builder.clientIdentity(identity.withKeyPem());
        }
        TckAdapterContext context = builder.build();
        own.add(context::release);
        return context;
    }

    /**
     * A tool over {@code https} on {@code 127.0.0.1} that presents {@code served} as its certificate (with
     * chain); with {@code clientTrust} it requires a client certificate and trusts only that one.
     */
    private TlsTool tlsTool(TckTlsFixtures.Pair served, X509Certificate clientTrust) throws Exception {
        return tlsTool(served, clientTrust, exchange -> 200);
    }

    /** Like {@link #tlsTool(TckTlsFixtures.Pair, X509Certificate)}, with the status that {@code status} chooses. */
    private TlsTool tlsTool(TckTlsFixtures.Pair served, X509Certificate clientTrust, TlsTool.Status status)
            throws Exception {
        TlsTool tool = new TlsTool(served, clientTrust, status);
        own.add(tool);
        return tool;
    }

    /** A tool without TLS on a port that the HTTP path addresses with {@code https}. */
    private NoTlsTool noTlsTool(NoTlsTool.Answer answer) throws IOException {
        NoTlsTool tool = new NoTlsTool(answer);
        own.add(tool);
        return tool;
    }

    private static AdapterRequest getOf(URI uri) {
        return AdapterRequest.builder("GET", uri).timeout(Duration.ofSeconds(20)).build();
    }

    /**
     * Without an identity there is no binding and no request: if the type declares a client identity, the TCK
     * builds no context without an identity or with an expired one, as the host refuses before the adapter's
     * {@code bind} ({@code NOT_CONFIGURED ACCESS_NOT_CONFIGURED} or {@code DEFECT CERTIFICATE_EXPIRED}). Over
     * {@code http} the HTTP path sends nothing and fails for good, without an attempt. The tool sees no request.
     */
    @Test
    public void withoutAClientIdentityNoRequestIsSent() throws Exception {
        TlsTool tool = tlsTool(tls.issuedForIp(), null);
        FakeHttpTool plain = ownTool().route("/", exchange -> FakeHttpTool.respond(exchange, 200, "text/plain",
                bytes("ok")));

        IllegalStateException none = assertThrows(IllegalStateException.class,
                () -> tlsContext(true, tls.root(), null));
        assertTrue(none.getMessage().contains("has no client identity"), none.getMessage());
        assertTrue(none.getMessage().contains("NOT_CONFIGURED"), none.getMessage());

        IllegalStateException old = assertThrows(IllegalStateException.class,
                () -> tlsContext(true, tls.root(), tls.expiredIdentity()));
        assertTrue(old.getMessage().contains("client certificate") && old.getMessage().contains("has expired"),
                old.getMessage());
        assertTrue(old.getMessage().contains("CERTIFICATE_EXPIRED"), old.getMessage());
        assertFalse(old.getMessage().contains("PRIVATE KEY"), old.getMessage());

        TckAdapterContext overHttp = tlsContext(true, tls.root(), tls.identity());
        AdapterException http = assertThrows(AdapterException.class,
                () -> overHttp.http().send(getOf(plain.uri("/"))));
        assertFalse(http.isRetryable(), http.getMessage());
        assertTrue(http.getMessage().contains("only over https"), http.getMessage());
        assertEquals(0, overHttp.httpRequests());

        assertEquals(0, tool.requests(), "the tool over https saw no request");
        assertTrue(plain.requests().isEmpty(), "the tool over http saw no request");
    }

    /**
     * With an identity the HTTP path presents it at every {@code https} request; through {@code config()} the
     * adapter sees only the public certificate. A second value with a private key makes the material unusable.
     */
    @Test
    public void theActiveClientIdentityIsPresentedAtAnHttpsRequest() throws Exception {
        TlsTool tool = tlsTool(tls.issuedForIp(), tls.identity().certificate());
        TckAdapterContext context = tlsContext(true, tls.root(), tls.identity());

        AdapterResponse response = context.http().send(getOf(tool.uri()));

        assertEquals(200, response.status());
        assertArrayEquals(tls.identity().certificate().getEncoded(), tool.lastClient().getEncoded(),
                "the tool saw the client identity of the connection");
        assertEquals(List.of(tls.identity().certificate()), context.config().certificate(IDENTITY_KEY));
        String shown = context.config().string(IDENTITY_KEY);
        assertTrue(shown.contains("BEGIN CERTIFICATE") && !shown.contains("PRIVATE KEY"), "only the certificate");

        TckAdapterContext twoKeys = TckAdapterContext.builder(tlsDescriptor(true))
                .configuration(Map.of(ANCHOR_KEY, tls.otherLeaf().withKeyPem()))
                .clientIdentity(tls.identity().withKeyPem()).build();
        own.add(twoKeys::release);
        AdapterException unusable = assertThrows(AdapterException.class, () -> twoKeys.http().send(getOf(tool.uri())));
        assertFalse(unusable.isRetryable(), unusable.getMessage());
        assertTrue(unusable.getMessage().contains("more than one certificate value holds a private key"),
                unusable.getMessage());
        assertEquals(1, tool.requests(), "only the request with usable material");
    }

    /**
     * As in the host, only {@code notAfter} of the identity counts: one that becomes valid tomorrow binds and
     * is presented; whether the tool accepts it is for the tool to decide.
     */
    @Test
    public void anIdentityThatIsNotValidYetIsPresentedLikeInTheHost() throws Exception {
        TckTlsFixtures.Pair early = tls.notYetValidIdentity();
        TlsTool tool = tlsTool(tls.issuedForIp(), early.certificate());
        TckAdapterContext context = tlsContext(true, tls.root(), early);

        assertEquals(200, context.http().send(getOf(tool.uri())).status());
        assertArrayEquals(early.certificate().getEncoded(), tool.lastClient().getEncoded());
    }

    /**
     * The identity is checked per attempt, not per hop: if it expires while the tool answers the first request
     * with 503, the retry is not sent and the request fails for good with "has expired". The test controls the
     * clock of the TLS material: the tool sets it to {@code notAfter} before it answers, so nothing waits for
     * the wall clock.
     */
    @Test
    public void anIdentityThatExpiresBetweenTwoAttemptsSendsNothingMore() throws Exception {
        TckTlsFixtures.Pair identity = tls.identity();
        Instant notAfter = identity.certificate().getNotAfter().toInstant();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
        try (TckTlsClockTestAccess stopped = TckTlsClockTestAccess.use(now::get)) {
            TlsTool tool = tlsTool(tls.issuedForIp(), identity.certificate(), exchange -> {
                now.set(notAfter);
                return 503;
            });
            TckAdapterContext context = tlsContext(true, tls.root(), identity);

            AdapterException refused = assertThrows(AdapterException.class, () -> context.http().send(
                    AdapterRequest.builder("GET", tool.uri()).timeout(Duration.ofSeconds(40)).build()));

            assertFalse(refused.isRetryable(), refused.getMessage());
            assertTrue(refused.getMessage().contains("client identity")
                    && refused.getMessage().contains("has expired"), refused.getMessage());
            assertEquals(1, tool.requests(), "the retry was not sent");
            assertEquals(1, context.httpRequests());
        }
    }

    /**
     * Pinning: if the anchor is a self-signed leaf and the tool presents exactly that leaf, the connection is
     * accepted without a name check: the leaf names {@code other.test}, the address is {@code 127.0.0.1}.
     */
    @Test
    public void aPinnedLeafForAnotherNamePasses() throws Exception {
        TlsTool pinned = tlsTool(tls.leaf(), null);
        TckAdapterContext context = tlsContext(false, tls.leaf(), null);

        assertEquals(200, context.http().send(getOf(pinned.uri())).status(), "the same leaf, byte for byte");
        assertEquals(1, pinned.requests());
    }

    /** Another leaf with the pinned public key (SPKI) is accepted as well. */
    @Test
    public void aLeafWithThePinnedPublicKeyPasses() throws Exception {
        TlsTool renewed = tlsTool(tls.renewedLeaf(), null);
        TckAdapterContext context = tlsContext(false, tls.leaf(), null);

        assertFalse(Arrays.equals(tls.leaf().certificate().getEncoded(),
                tls.renewedLeaf().certificate().getEncoded()));
        assertEquals(200, context.http().send(getOf(renewed.uri())).status(), "the same public key");
    }

    /** Another leaf with the same name fails for good, and the tool sees no request. */
    @Test
    public void anotherLeafIsRefusedWhereALeafIsPinned() throws Exception {
        TlsTool other = tlsTool(tls.otherLeaf(), null);
        TckAdapterContext context = tlsContext(false, tls.leaf(), null);

        AdapterException refused = assertThrows(AdapterException.class, () -> context.http().send(getOf(other.uri())));

        assertFalse(refused.isRetryable(), refused.getMessage());
        //Same name as the anchor: the JDK looks the anchor up by name and finds the signature wrong ("refused");
        //with another name the message would say "not trusted". Both fail for good
        assertTrue(refused.getMessage().contains("the certificate of the tool"), refused.getMessage());
        assertEquals(0, other.requests());
        assertEquals(1, context.httpRequests(), "no retry");
    }

    /**
     * A pinned leaf counts only while it is valid: once expired it fails for good with "has expired", without
     * a request.
     */
    @Test
    public void anExpiredPinnedLeafIsRefused() throws Exception {
        TlsTool expired = tlsTool(tls.expiredLeaf(), null);
        TckAdapterContext context = tlsContext(false, tls.expiredLeaf(), null);

        AdapterException refused = assertThrows(AdapterException.class,
                () -> context.http().send(getOf(expired.uri())));

        assertFalse(refused.isRetryable(), refused.getMessage());
        assertTrue(refused.getMessage().contains("has expired"), refused.getMessage());
        assertEquals(0, expired.requests());
        assertEquals(1, context.httpRequests(), "no retry");
    }

    /**
     * A root with the CA basic constraint as anchor keeps the name check: its leaf for {@code 127.0.0.1}
     * passes, its leaf for {@code other.test} fails.
     */
    @Test
    public void aRootCaAnchorWithAWrongNameIsRefused() throws Exception {
        TlsTool right = tlsTool(tls.issuedForIp(), null);
        TlsTool wrong = tlsTool(tls.issuedForOther(), null);
        TckAdapterContext context = tlsContext(false, tls.root(), null);

        assertEquals(200, context.http().send(getOf(right.uri())).status(), "the right name");
        AdapterException refused = assertThrows(AdapterException.class, () -> context.http().send(getOf(wrong.uri())));
        assertFalse(refused.isRetryable(), refused.getMessage());
        assertTrue(refused.getMessage().contains("it is not issued for the host of the address"),
                refused.getMessage());
        assertEquals(0, wrong.requests());
    }

    /**
     * A self-signed root keeps the name check even when the tool presents the root itself as its end-entity
     * certificate: it is the anchor byte for byte, but not a leaf.
     */
    @Test
    public void aSelfSignedRootCaKeepsTheNameCheckEvenWhenTheToolShowsItself() throws Exception {
        TlsTool itself = tlsTool(tls.root(), null);
        TckAdapterContext context = tlsContext(false, tls.root(), null);

        AdapterException refused = assertThrows(AdapterException.class, () -> context.http().send(getOf(itself.uri())));

        assertFalse(refused.isRetryable(), refused.getMessage());
        assertTrue(refused.getMessage().contains("it is not issued for the host of the address"),
                refused.getMessage());
        assertEquals(0, itself.requests());
    }

    /**
     * A leaf issued by a CA does not pin when it is the anchor, even if the tool presents exactly that leaf:
     * it is not self-signed, so the name check stays.
     */
    @Test
    public void aLeafOfACaAsAnchorKeepsTheNameCheck() throws Exception {
        TlsTool tool = tlsTool(tls.issuedForOther(), null);
        TckAdapterContext context = tlsContext(false, tls.issuedForOther(), null);

        AdapterException refused = assertThrows(AdapterException.class, () -> context.http().send(getOf(tool.uri())));

        assertFalse(refused.isRetryable(), refused.getMessage());
        assertTrue(refused.getMessage().contains("it is not issued for the host of the address"),
                refused.getMessage());
        assertEquals(0, tool.requests());
    }

    /**
     * A certificate with the CA basic constraint and the pinned public key is not a pinned leaf: it goes
     * through the PKIX check, which refuses it because the anchor is a leaf and may not issue anything. The
     * request fails for good and is never sent.
     */
    @Test
    public void aCaCertificateWithThePinnedPublicKeyIsNotPinned() throws Exception {
        TlsTool tool = tlsTool(tls.authorityWithLeafKey(), null);
        TckAdapterContext context = tlsContext(false, tls.leaf(), null);

        assertTrue(tls.authorityWithLeafKey().certificate().getBasicConstraints() >= 0, "a CA");
        assertArrayEquals(tls.leaf().certificate().getPublicKey().getEncoded(),
                tls.authorityWithLeafKey().certificate().getPublicKey().getEncoded(), "the pinned SPKI");
        AdapterException refused = assertThrows(AdapterException.class, () -> context.http().send(getOf(tool.uri())));

        assertFalse(refused.isRetryable(), refused.getMessage());
        assertTrue(refused.getMessage().contains("the certificate of the tool was refused"), refused.getMessage());
        assertEquals(0, tool.requests());
        assertEquals(1, context.httpRequests(), "no retry");
    }

    /**
     * Conversely, a self-signed CA as anchor does not pin a leaf with its key (only a self-signed leaf pins):
     * the leaf goes through the PKIX check, which checks the name.
     */
    @Test
    public void aSelfSignedCaAnchorDoesNotPinALeafWithItsPublicKey() throws Exception {
        TlsTool tool = tlsTool(tls.leaf(), null);
        TckAdapterContext context = tlsContext(false, tls.authorityWithLeafKey(), null);

        AdapterException refused = assertThrows(AdapterException.class, () -> context.http().send(getOf(tool.uri())));

        assertFalse(refused.isRetryable(), refused.getMessage());
        assertTrue(refused.getMessage().contains("it is not issued for the host of the address"),
                refused.getMessage());
        assertEquals(0, tool.requests());
    }

    // ------------------------------------------------------------------
    // Retryable or final, as in the host
    // ------------------------------------------------------------------

    /** At most two retries, without really waiting. */
    private TckAdapterContext retryingContext() {
        TckAdapterContext context = TckAdapterContext.builder(descriptor())
                .network(new TckAdapterHttp.Settings(Duration.ofSeconds(10), 1 << 20, 2,
                        TckAdapterHttp.Settings.SHORTENED_WAIT))
                .build();
        own.add(context::release);
        return context;
    }

    /**
     * A peer without TLS on the {@code https} port fails for good, as in the host: it answers as {@code http}
     * or with bytes that are no TLS record.
     */
    @Test
    public void aToolWithoutTlsOnTheHttpsPortFailsForGood() throws Exception {
        for (NoTlsTool.Answer answer : List.of(NoTlsTool.Answer.PLAIN_HTTP, NoTlsTool.Answer.GARBAGE)) {
            NoTlsTool tool = noTlsTool(answer);
            TckAdapterContext context = retryingContext();

            AdapterException refused = assertThrows(AdapterException.class, () -> context.http().send(getOf(
                    tool.httpsUri())));

            assertFalse(refused.isRetryable(), answer + ": " + refused.getMessage());
            assertTrue(refused.getMessage().contains("the tool does not speak TLS on this port"),
                    refused.getMessage());
            assertEquals(1, context.httpRequests(), answer + ": no retry");
            assertEquals(1, tool.connections(), answer.toString());
        }
    }

    /**
     * A handshake broken by a network error (the tool resets the connection) is retryable, as in the host:
     * retried as often as the setting allows.
     */
    @Test
    public void aHandshakeBrokenByTheNetworkIsRetried() throws Exception {
        NoTlsTool tool = noTlsTool(NoTlsTool.Answer.RESET);
        TckAdapterContext context = retryingContext();

        AdapterException failed = assertThrows(AdapterException.class, () -> context.http().send(getOf(
                tool.httpsUri())));

        assertTrue(failed.isRetryable(), failed.getMessage());
        assertEquals(3, context.httpRequests(), "the attempt and two retries");
        assertEquals(3, tool.connections());
    }

    /**
     * If the tool refuses the client certificate (under TLS 1.3 an alert that arrives only after the client's
     * handshake), the failure is final, as in the host: no retry cures it, and the message names the client
     * certificate. The tool is an {@code SSLServerSocket} because the HTTPS server of the JDK ({@link TlsTool})
     * closed the connection without sending the alert up to Java 21 (since Java 25 it sends it).
     */
    @Test
    public void aRefusedClientCertificateFailsForGood() throws Exception {
        RefusingTlsTool tool = new RefusingTlsTool(tls.issuedForIp(), tls.otherLeaf().certificate());
        own.add(tool);
        TckAdapterContext context = tlsContext(true, tls.root(), tls.identity());

        AdapterException refused = assertThrows(AdapterException.class, () -> context.http().send(getOf(tool.uri())));

        assertFalse(refused.isRetryable(), refused.getMessage());
        assertTrue(refused.getMessage().contains("the tool refused the client certificate of this connection"),
                refused.getMessage());
        assertEquals(1, context.httpRequests(), "no retry");
        assertEquals(0, tool.requests());
    }

    // ------------------------------------------------------------------
    // Release
    // ------------------------------------------------------------------

    /** The {@code SelectorManager} threads of JDK HTTP clients that are currently running. */
    private static Set<String> selectorThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(Thread::isAlive).map(Thread::getName)
                .filter(name -> name.startsWith("HttpClient-") && name.endsWith("-SelectorManager"))
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * When the TCK releases the binding, its HTTP path lets go of its own client: even while the context is
     * still reachable, the JVM can collect the client, and its {@code SelectorManager} ends.
     */
    @Test
    public void releasingTheBindingLetsItsOwnClientGo() throws Exception {
        TlsTool tool = tlsTool(tls.leaf(), null);
        TckAdapterContext context = tlsContext(false, tls.leaf(), null);
        TckAdapterHttp http = (TckAdapterHttp) context.http();
        Set<String> before = selectorThreads();
        assertEquals(200, http.send(getOf(tool.uri())).status());
        Set<String> started = new java.util.HashSet<>(selectorThreads());
        started.removeAll(before);
        assertEquals(1, started.size(), "the client of the binding itself: " + started);

        context.release();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (selectorThreads().stream().anyMatch(started::contains) && System.nanoTime() - end < 0) {
            System.gc();
            Thread.sleep(100);
        }

        assertTrue(selectorThreads().stream().noneMatch(started::contains),
                "the client of the released binding does not stay attached to the HTTP path: " + started);
        assertTrue(http.isClosed(), "the HTTP path is still reachable and released");
    }
}
