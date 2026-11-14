package com.rmm.adapter.tck;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * A fake tool over HTTP for tests without a real tool (TCK case 2 and
 * {@link AdapterHttpSemantics}): a JDK server ({@code com.sun.net.httpserver})
 * on a free port of {@code 127.0.0.1} whose responses the test defines as
 * one {@link HttpHandler} per path.
 *
 * <p><b>Only through {@code context.http()}:</b> started with
 * {@link #start()}, the tool answers only requests that carry the binding's
 * stamp, a header that only the TCK's HTTP path ({@link TckAdapterHttp})
 * sets. Any other request gets 403 and counts as refused
 * ({@link #refused()}); this is how case 2 detects an adapter that sends
 * with a client of its own. {@link #startWithoutBindingHeader()} skips the
 * check, for tests of an HTTP path itself (for example a host's).</p>
 *
 * <p>The tool records every accepted request ({@link #requests()}). A test
 * registers tool-side state (search sessions, result sets) with
 * {@link #onExpiry}; {@link #expireState()} expires it, which is how the TCK
 * simulates a long pause between two pages.</p>
 *
 * <p><b>Over https:</b> {@link #startHttps} serves TLS 1.3 and 1.2 and
 * requires a client certificate that the test names as trusted; without it
 * the handshake fails. A request records the certificate and the binding it
 * names ({@link Request#clientBinding()}). After
 * {@link #requireClientBindingIn} the tool also answers 403 unless a header
 * names exactly that binding (the way a remote service compares
 * {@code X-RMM-Binding} with the certificate), counted in
 * {@link #refusedBindings()}. The stamp rule applies here too.</p>
 *
 * <p>Threads: a fixed pool named {@value #THREAD_PREFIX}-…, created at
 * start; case 4 attributes it to the tool, not to the binding.</p>
 */
public final class FakeHttpTool implements AutoCloseable {

    /** Name prefix of the tool's threads. */
    static final String THREAD_PREFIX = "rmm-tck-fake-tool";
    private static final int WORKERS = 8;
    private static final AtomicInteger THREADS = new AtomicInteger();

    /**
     * An accepted request.
     *
     * @param method  method
     * @param path    path
     * @param query   query without {@code ?}; {@code null} if absent
     * @param headers headers (names are case-insensitive)
     * @param body    body
     * @param stamp   the binding's stamp; {@code null} if absent
     * @param clientCertificate the presented client certificate ({@link #startHttps}); {@code null} without TLS
     */
    public record Request(String method, String path, String query, Map<String, List<String>> headers, byte[] body,
                          String stamp, X509Certificate clientCertificate) {

        /**
         * The binding the client certificate names
         * ({@code URI:urn:rmm:binding:<bindingId>}); {@code null} without a
         * certificate or without such a name.
         */
        public String clientBinding() {
            return clientCertificate == null ? null
                    : TckClientIdentity.bindingNamedBy(clientCertificate).orElse(null);
        }

        /** The first value of the header; {@code null} if absent. */
        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        /** Omits headers and body. */
        @Override
        public String toString() {
            return "Request[" + method + " " + path + "]";
        }
    }

    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final boolean requireStamp;
    /** Whether the tool speaks https ({@link #startHttps}). */
    private final boolean tls;
    /** The header that must name the certificate's binding; {@code null}: none ({@link #requireClientBindingIn}). */
    private volatile String bindingHeader;
    private final AtomicInteger refusedBindings = new AtomicInteger();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger refused = new AtomicInteger();
    private final AtomicInteger refusedSinceStart = new AtomicInteger();
    private final List<Runnable> expiry = new CopyOnWriteArrayList<>();

    private FakeHttpTool(boolean requireStamp) throws IOException {
        this(requireStamp, null);
    }

    /** @param tls the tool's TLS context; {@code null}: http */
    private FakeHttpTool(boolean requireStamp, SSLContext tls) throws IOException {
        this.requireStamp = requireStamp;
        this.tls = tls != null;
        InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        if (tls == null) {
            this.server = HttpServer.create(loopback, 0);
        } else {
            HttpsServer secure = HttpsServer.create(loopback, 0);
            secure.setHttpsConfigurator(new HttpsConfigurator(tls) {
                @Override
                public void configure(HttpsParameters parameters) {
                    SSLParameters ssl = TckTlsMaterial.parameters(tls);
                    ssl.setNeedClientAuth(true);
                    parameters.setSSLParameters(ssl);
                }
            });
            this.server = secure;
        }
        this.workers = new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, THREAD_PREFIX + "-" + THREADS.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        workers.prestartAllCoreThreads();
        server.setExecutor(workers);
        server.start();
    }

    /** A tool that answers only requests sent through {@code context.http()} of the TCK. */
    public static FakeHttpTool start() throws IOException {
        return new FakeHttpTool(true);
    }

    /** A tool that does not check the stamp, for tests of an HTTP path itself. */
    public static FakeHttpTool startWithoutBindingHeader() throws IOException {
        return new FakeHttpTool(false);
    }

    /**
     * Like {@link #start()}, over https with a required client certificate.
     * The tool identifies itself with {@code toolIdentityPem} (certificate
     * and unencrypted private key, PKCS#8) and accepts only a client
     * certificate from {@code trustedClientsPem}, compared byte for byte; a
     * test lists the identities of its connections there. The connection
     * trusts the tool through the certificate from {@code toolIdentityPem}
     * as anchor (a self-signed leaf is pinned).
     *
     * @throws IllegalArgumentException if one of the PEMs is unusable or the tool's key is missing
     */
    public static FakeHttpTool startHttps(String toolIdentityPem, String trustedClientsPem) throws IOException {
        TckPem.Material identity = TckPem.parse(toolIdentityPem, "The identity of the fake tool");
        if (identity.privateKey().isEmpty()) {
            throw new IllegalArgumentException("The identity of the fake tool holds no private key - pass the"
                    + " certificate and its unencrypted PKCS#8 key (BEGIN PRIVATE KEY)");
        }
        List<X509Certificate> trusted = TckPem.parse(trustedClientsPem, "The trusted client certificates")
                .certificates();
        SSLContext context;
        try {
            context = SSLContext.getInstance("TLS");
            context.init(TckTlsMaterial.keyManagersOf(identity), new TrustManager[]{new TrustedClients(trusted)},
                    null);
        } catch (GeneralSecurityException unusable) {
            throw new IllegalArgumentException("The TLS material of the fake tool is not usable ("
                    + unusable.getClass().getSimpleName() + ")");
        }
        return new FakeHttpTool(true, context);
    }

    /**
     * From now on the tool ({@link #startHttps}) answers only requests whose
     * header {@code header} names the binding of the client certificate
     * ({@link Request#clientBinding()}); any other gets 403 and counts in
     * {@link #refusedBindings()}, not in {@link #refused()}.
     */
    public FakeHttpTool requireClientBindingIn(String header) {
        if (!tls) {
            throw new IllegalStateException("Only a fake tool over https (startHttps) sees a client certificate");
        }
        this.bindingHeader = header;
        return this;
    }

    /** Number of requests refused for a missing or different binding header ({@link #requireClientBindingIn}). */
    public int refusedBindings() {
        return refusedBindings.get();
    }

    /** Trusts exactly the given client certificates (byte for byte); as a server, the tool verifies no peer. */
    private static final class TrustedClients implements X509TrustManager {

        private final X509Certificate[] trusted;

        private TrustedClients(List<X509Certificate> trusted) {
            this.trusted = trusted.toArray(X509Certificate[]::new);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("no client certificate");
            }
            byte[] presented = chain[0].getEncoded();
            for (X509Certificate known : trusted) {
                if (Arrays.equals(presented, known.getEncoded())) {
                    return;
                }
            }
            throw new CertificateException("the client certificate is not one the fake tool trusts");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("the fake tool is no client");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return trusted.clone();
        }
    }

    /**
     * Answers requests to {@code path} and everything below it (as
     * {@link HttpServer#createContext} does). The request body has already
     * been read; the handler reads it as usual from
     * {@link HttpExchange#getRequestBody()}.
     */
    public FakeHttpTool route(String path, HttpHandler handler) {
        server.createContext(path, exchange -> handle(exchange, handler));
        return this;
    }

    /** Answers every GET on {@code path} with 200 and this text in UTF-8. */
    public FakeHttpTool text(String path, String contentType, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return route(path, exchange -> respond(exchange, 200, contentType, bytes));
    }

    /** The URI of a path on this tool ({@code /…}, optionally with a query). */
    public URI uri(String pathAndQuery) {
        return URI.create((tls ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort()
                + pathAndQuery);
    }

    /** The accepted requests, in order. */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Number of requests refused for a missing binding stamp. */
    public int refused() {
        return refused.get();
    }

    /**
     * Number of requests refused for a missing stamp since the start;
     * {@link #forget()} does not reset it. The TCK checks it after every
     * case (case 2 across all cases).
     */
    public int refusedSinceStart() {
        return refusedSinceStart.get();
    }

    /** Forgets the recorded and the refused requests ({@link #refusedSinceStart()} remains). */
    public void forget() {
        requests.clear();
        refused.set(0);
        refusedBindings.set(0);
    }

    /** Registers tool-side state that {@link #expireState()} lets expire. */
    public FakeHttpTool onExpiry(Runnable expire) {
        expiry.add(expire);
        return this;
    }

    /** Lets all registered state expire, as after a long pause. */
    public void expireState() {
        expiry.forEach(Runnable::run);
    }

    /** Number of states registered with {@link #onExpiry}; with none, the pause check verifies nothing. */
    public int expiryHandlers() {
        return expiry.size();
    }

    /** Responds with status, media type and body (known length). */
    public static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        boolean empty = body.length == 0 || exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(status, empty ? -1 : body.length);
        if (!empty) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }

    @Override
    public String toString() {
        return "FakeHttpTool[" + uri("/") + (requireStamp ? ", only through context.http()" : "")
                + (tls ? ", client certificate required" : "") + "]";
    }

    /** The presented client certificate; {@code null} without TLS. */
    private static X509Certificate clientCertificateOf(HttpExchange exchange) {
        if (!(exchange instanceof HttpsExchange secure)) {
            return null;
        }
        try {
            Certificate[] chain = secure.getSSLSession().getPeerCertificates();
            return chain.length > 0 && chain[0] instanceof X509Certificate first ? first : null;
        } catch (SSLPeerUnverifiedException none) {
            return null;
        }
    }

    private void handle(HttpExchange exchange, HttpHandler handler) throws IOException {
        try {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
            List<String> stamps = headers.get(TckAdapterHttp.BINDING_HEADER);
            String stamp = stamps == null || stamps.isEmpty() ? null : stamps.get(0);
            if (requireStamp && stamp == null) {
                refused.incrementAndGet();
                refusedSinceStart.incrementAndGet();
                respond(exchange, 403, "text/plain", ("This fake tool answers only requests sent through"
                        + " context.http() of the TCK").getBytes(StandardCharsets.UTF_8));
                return;
            }
            URI uri = exchange.getRequestURI();
            X509Certificate client = clientCertificateOf(exchange);
            String header = bindingHeader;
            if (header != null) {
                List<String> named = headers.get(header);
                String binding = client == null ? null
                        : TckClientIdentity.bindingNamedBy(client).orElse(null);
                if (binding == null || named == null || named.size() != 1 || !binding.equals(named.get(0))) {
                    refusedBindings.incrementAndGet();
                    respond(exchange, 403, "text/plain", ("The header " + header + " does not name the binding of"
                            + " the client certificate").getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            requests.add(new Request(exchange.getRequestMethod(), uri.getPath(), uri.getRawQuery(), headers, body,
                    stamp, client));
            exchange.setStreams(new ByteArrayInputStream(body), null);
            handler.handle(exchange);
        } catch (IOException | RuntimeException failed) {
            //The client aborted or the handler failed: the connection ends
            exchange.close();
            if (failed instanceof RuntimeException runtime) {
                throw runtime;
            }
        }
    }
}
