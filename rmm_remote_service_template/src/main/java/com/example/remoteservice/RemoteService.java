package com.example.remoteservice;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.TrustManager;

/**
 * Template of a remote adapter service for protocol version 4
 * ({@code docs/REMOTE_PROTOCOL.md}), using only the JDK: an
 * {@link HttpsServer} with mutual TLS, the pinned client certificates
 * ({@link PinStore}), and the version 4 endpoints backed by an in-memory tool
 * per binding ({@link InMemoryTool}). Tested with the TCK for remote services
 * ({@code rmm_remote_tck}, {@code TestRemoteServiceTemplateContract}).
 *
 * <p><b>Every request</b> passes three checks before an endpoint sees it:</p>
 * <ol>
 *   <li>TLS 1.3 or 1.2 with AEAD; the handshake requires a client certificate
 *       ({@code setNeedClientAuth}) and accepts only a pinned one.</li>
 *   <li>The request checks the presented certificate against the pin store
 *       again ({@link #authenticatedClient}); otherwise {@code 401}. A removed
 *       certificate therefore stops working with the next request, even on
 *       an open session.</li>
 *   <li>The header {@value #BINDING_HEADER} appears exactly once and names
 *       exactly the binding the certificate names
 *       ({@code URI:urn:rmm:binding:<bindingId>}; {@link #bindingOf});
 *       otherwise {@code 403}. The header only selects the binding; it
 *       grants nothing.</li>
 * </ol>
 *
 * <p>The request then works on the tool of ITS binding ({@link #toolOf}); two
 * connections (for example of two tenants) share neither data nor state. To
 * build your own service, adapt {@link #descriptor} and replace
 * {@link InMemoryTool} with calls to your tool; keep the three checks.</p>
 *
 * <p><b>Admission time limit:</b> the {@link HttpsServer} performs the TLS
 * handshake and reads the request line and headers on the {@value #THREADS}
 * worker threads, before any check applies, and the JDK sets no timeout for
 * this. A peer that sends half a ClientHello and then stays silent would
 * hold a thread forever, and {@value #THREADS} such peers the whole service.
 * The service therefore aborts every request that is not checked (handshake,
 * headers, binding) within {@link #admissionTimeLimit()} and frees its
 * thread.</p>
 *
 * <p><b>A refusal closes the connection:</b> the service answers a refused
 * request ({@code 401}, {@code 403}, {@code 404} for an unknown path,
 * {@code 405}, {@code 413}) without reading (the rest of) its body and then
 * closes the connection ({@code Connection: close}); {@link #refuse} explains
 * why.</p>
 */
public class RemoteService implements AutoCloseable {

    /** The binding header ({@code docs/REMOTE_PROTOCOL.md}, "Transport"). */
    public static final String BINDING_HEADER = "X-RMM-Binding";
    /** The protocol version the service speaks. */
    public static final int PROTOCOL_VERSION = 4;
    /** The only TLS protocols, as in Mirador. */
    static final List<String> PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");
    /** The only cipher suites, as in Mirador: TLS 1.3, and for TLS 1.2 ECDHE with AEAD. */
    static final List<String> CIPHER_SUITES = List.of(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256");
    /** Maximum size of a request body (a batch of 500 artifacts stays far below it). */
    static final int MAX_REQUEST_BYTES = 16 * 1024 * 1024;
    /** Number of requests the service handles concurrently. */
    static final int THREADS = 8;
    /** How long a request may take until its binding is checked (handshake, request line, headers). */
    static final Duration ADMISSION_TIME_LIMIT = Duration.ofSeconds(10);
    /** The admission of the request this worker thread is handling. */
    private static final ThreadLocal<Admission> ADMISSION = new ThreadLocal<>();

    private static final System.Logger LOG = System.getLogger(RemoteService.class.getName());

    /** The POST endpoints: path to the call on the binding's tool. */
    private static final Map<String, BiFunction<InMemoryTool, Map<String, Object>, Map<String, Object>>> POSTS =
            Map.ofEntries(
                    Map.entry("/read/artifacts", InMemoryTool::readArtifacts),
                    Map.entry("/read/relationships", InMemoryTool::readRelationships),
                    Map.entry("/read/comments", InMemoryTool::readComments),
                    Map.entry("/write/artifact/create", InMemoryTool::createArtifact),
                    Map.entry("/write/artifact/update", InMemoryTool::updateArtifact),
                    Map.entry("/write/relationship/create", InMemoryTool::createRelationship),
                    Map.entry("/write/comment/create", InMemoryTool::createComment),
                    Map.entry("/write/artifacts/create", InMemoryTool::createArtifacts),
                    Map.entry("/write/artifacts/update", InMemoryTool::updateArtifacts),
                    Map.entry("/write/artifacts/delete", InMemoryTool::deleteArtifacts),
                    Map.entry("/write/relationships/create", InMemoryTool::createRelationships),
                    Map.entry("/write/relationships/delete", InMemoryTool::deleteRelationships),
                    Map.entry("/write/comments/create", InMemoryTool::createComments));
    /**
     * The single creates, which report a failed element as non-2xx. Only
     * {@code /write/artifact/update} and {@code /write/comment/create} may
     * carry an {@code error} with 2xx ({@code docs/REMOTE_PROTOCOL.md},
     * "Write calls"). For a single create, Mirador would read only "no id"
     * instead of the reason.
     */
    private static final Set<String> SINGLE_CREATES = Set.of("/write/artifact/create", "/write/relationship/create");
    /** The single deletes: 2xx when the element existed, otherwise 404 (deletes are idempotent). */
    private static final Map<String, BiFunction<InMemoryTool, Map<String, Object>, Boolean>> DELETES = Map.of(
            "/write/artifact/delete", InMemoryTool::deleteArtifact,
            "/write/relationship/delete", InMemoryTool::deleteRelationship);
    private static final Set<String> GETS = Set.of("/descriptor", "/health");

    private final ServiceSettings settings;
    private final Map<String, InMemoryTool> tools = new ConcurrentHashMap<>();
    private HttpsServer server;
    private ExecutorService executor;
    private ScheduledThreadPoolExecutor deadlines;

    public RemoteService(ServiceSettings settings) {
        this.settings = settings;
    }

    /** Starts the service from the environment variables of {@link ServiceSettings#fromEnvironment}. */
    public static void main(String[] arguments) throws Exception {
        RemoteService service = new RemoteService(ServiceSettings.fromEnvironment(System.getenv())).start();
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "remote-service-stop"));
        LOG.log(System.Logger.Level.INFO, "remote service (protocol {0}) listening at {1}", PROTOCOL_VERSION,
                service.baseUrl());
    }

    /** Opens the port and accepts requests. */
    public synchronized RemoteService start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("the service is already running");
        }
        SSLContext tls = tlsContext();
        HttpsServer https = HttpsServer.create(settings.address(), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(tls) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters ssl = tls.getDefaultSSLParameters();
                configureTls(ssl, Set.of(tls.getSupportedSSLParameters().getCipherSuites()));
                parameters.setSSLParameters(ssl);
            }
        });
        AtomicInteger number = new AtomicInteger();
        executor = Executors.newFixedThreadPool(THREADS, task -> {
            Thread thread = new Thread(task, "remote-service-" + number.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        deadlines = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "remote-service-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        deadlines.setRemoveOnCancelPolicy(true);
        ExecutorService workers = executor;
        ScheduledThreadPoolExecutor cutter = deadlines;
        Duration limit = admissionTimeLimit();
        https.setExecutor(exchange -> workers.execute(() -> withAdmissionDeadline(exchange, cutter, limit)));
        https.createContext("/", this::handle);
        https.start();
        server = https;
        return this;
    }

    /** The address of the running service, {@code https://<host>:<port>}: the {@code url} of the connection. */
    public synchronized String baseUrl() {
        if (server == null) {
            throw new IllegalStateException("the service is not running");
        }
        InetSocketAddress address = server.getAddress();
        String host = address.getAddress().isAnyLocalAddress() ? "localhost" : address.getHostString();
        return "https://" + host + ":" + address.getPort();
    }

    /** Closes the port and ends every request. */
    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (deadlines != null) {
            deadlines.shutdownNow();
            deadlines = null;
        }
    }

    // ------------------------------------------------------------------
    // The three checks and the binding: overridable, never to be switched off
    // ------------------------------------------------------------------

    /**
     * The parameters of every handshake: TLS 1.3 and 1.2, only those cipher
     * suites of Mirador that the JDK supports, and a required client
     * certificate.
     */
    protected void configureTls(SSLParameters parameters, Set<String> supportedCipherSuites) {
        parameters.setProtocols(PROTOCOLS.toArray(String[]::new));
        parameters.setCipherSuites(CIPHER_SUITES.stream().filter(supportedCipherSuites::contains)
                .toArray(String[]::new));
        parameters.setNeedClientAuth(true);
    }

    /** The presented client certificate if it is pinned and valid NOW; otherwise null (answered with 401). */
    protected X509Certificate authenticatedClient(HttpsExchange exchange) {
        try {
            Certificate[] chain = exchange.getSSLSession().getPeerCertificates();
            if (chain.length > 0 && chain[0] instanceof X509Certificate leaf && settings.pins().isPinned(leaf)) {
                return leaf;
            }
            return null;
        } catch (SSLPeerUnverifiedException none) {
            return null;
        }
    }

    /**
     * The binding of the request: the header {@value #BINDING_HEADER} appears
     * exactly once and names the one binding of the certificate; otherwise
     * null (answered with 403).
     */
    protected String bindingOf(HttpsExchange exchange, X509Certificate client) {
        List<String> headers = exchange.getRequestHeaders().get(BINDING_HEADER);
        List<String> named = PinStore.bindingsOf(client);
        if (headers == null || headers.size() != 1 || named.size() != 1 || !named.get(0).equals(headers.get(0))) {
            return null;
        }
        return named.get(0);
    }

    /**
     * The admission time limit: how long a request may take from its first
     * byte on the worker thread until its binding is checked (TLS handshake,
     * request line, headers, the three checks). After that the service aborts
     * it and closes the connection. The body is read only after the checks,
     * from an authenticated connection, and does not count.
     */
    protected Duration admissionTimeLimit() {
        return ADMISSION_TIME_LIMIT;
    }

    /** The tool of the binding: one per binding, created on first use. */
    protected InMemoryTool toolOf(String binding) {
        return tools.computeIfAbsent(binding, any -> new InMemoryTool());
    }

    /**
     * The descriptor of the binding ({@code GET /descriptor}, version 4): both
     * directions, flat, artifacts, links and comments, and the bulk operations
     * of this template. A service may return a different descriptor per
     * binding, but only within the upper limit of the type {@code REMOTE}.
     */
    protected Map<String, Object> descriptor(String binding) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("directions", List.of("TO_RMM", "FROM_RMM"));
        capabilities.put("hierarchyModel", "FLAT");
        capabilities.put("supportsRelationshipUpdate", false);
        capabilities.put("supportsArtifactTypeChange", false);
        capabilities.put("supportsPartialUpdate", true);
        capabilities.put("supportsDeltaRead", false);
        capabilities.put("reads", List.of("ARTIFACTS", "RELATIONSHIPS", "COMMENTS"));
        capabilities.put("writes", List.of("ARTIFACTS", "RELATIONSHIPS", "COMMENTS"));
        capabilities.put("bulkOperations", List.of("ARTIFACT_CREATE", "ARTIFACT_UPDATE", "ARTIFACT_DELETE",
                "RELATIONSHIP_CREATE", "RELATIONSHIP_DELETE", "COMMENT_CREATE"));
        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("maxRequestsPerSecond", 50L);
        rateLimit.put("maxRetries", 2L);
        capabilities.put("rateLimit", rateLimit);
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("systemKey", "TEMPLATE");
        descriptor.put("displayName", "Remote Service Template");
        descriptor.put("adapterVersion", "1.0.0");
        descriptor.put("protocolVersion", (long) PROTOCOL_VERSION);
        descriptor.put("capabilities", capabilities);
        return descriptor;
    }

    /**
     * The values of the binding's keys from the envelope of a request (object
     * {@code connection}): for each key the descriptor declares under
     * {@code connectionKeys}, the value the tenant set at the connection, or
     * its default, as text, number ({@link Long}) or boolean. Mirador never
     * sends a {@code SECRET}. This template declares no keys; your service
     * reads its own here. Without {@code connection}: no values.
     *
     * @throws InMemoryTool.BadRequest if {@code connection} is not an object (the service answers 400)
     */
    public static Map<String, Object> connectionOf(Map<String, Object> request) {
        Object connection = request.get("connection");
        if (connection == null) {
            return Map.of();
        }
        if (!(connection instanceof Map<?, ?> values)) {
            throw new InMemoryTool.BadRequest("connection must be an object of the values of the binding's keys");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return java.util.Collections.unmodifiableMap(copy);
    }

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    private void handle(HttpExchange plain) throws IOException {
        try {
            if (!(plain instanceof HttpsExchange exchange)) {
                refuse(plain, 403, message("https only"));
                return;
            }
            X509Certificate client = authenticatedClient(exchange);
            if (client == null) {
                refuse(exchange, 401, message("a client certificate pinned at this service is required"));
                return;
            }
            String binding = bindingOf(exchange, client);
            if (binding == null) {
                LOG.log(System.Logger.Level.DEBUG, "refused {0}: the header {1} does not name the binding of"
                        + " the client certificate", exchange.getRequestURI().getPath(), BINDING_HEADER);
                refuse(exchange, 403, message("the header " + BINDING_HEADER + " must appear once and name the"
                        + " binding of the client certificate"));
                return;
            }
            Admission admission = ADMISSION.get();
            if (admission != null && !admission.admit()) {
                //The admission time limit has expired; the connection is already cut
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (!offers(exchange, path)) {
                return;
            }
            byte[] body = bodyOf(exchange);
            if (body == null) {
                refuse(exchange, 413, message("the request body exceeds " + MAX_REQUEST_BYTES + " bytes"));
                return;
            }
            route(exchange, binding, path, body);
        } catch (IOException gone) {
            //The peer gave up; nothing left to answer
            LOG.log(System.Logger.Level.DEBUG, "connection lost: {0}", gone.getMessage());
        } finally {
            plain.close();
        }
    }

    /**
     * Whether the service offers {@code path} with the method of the
     * request. If not, the request is refused before its body is read:
     * {@code 404} for an unknown path, {@code 405} for the wrong method.
     */
    private static boolean offers(HttpsExchange exchange, String path) throws IOException {
        boolean get = GETS.contains(path);
        boolean post = POSTS.containsKey(path) || DELETES.containsKey(path);
        if (!get && !post) {
            refuse(exchange, 404, message("no endpoint " + path + " at this service"));
            return false;
        }
        if (!exchange.getRequestMethod().equals(get ? "GET" : "POST")) {
            exchange.getResponseHeaders().set("Allow", get ? "GET" : "POST");
            refuse(exchange, 405, message(path + " takes " + (get ? "GET" : "POST")));
            return false;
        }
        return true;
    }

    /** Answers a request to an endpoint the service offers, on the tool of its binding. */
    private void route(HttpsExchange exchange, String binding, String path, byte[] body) throws IOException {
        try {
            if (path.equals("/descriptor")) {
                respond(exchange, 200, descriptor(binding));
            } else if (path.equals("/health")) {
                respond(exchange, 200, Map.of("status", "UP"));
            } else {
                Map<String, Object> request = Json.parseObject(new String(body, StandardCharsets.UTF_8));
                //The envelope carries the values of the binding's keys; anything but an object is a bad request
                connectionOf(request);
                InMemoryTool tool = toolOf(binding);
                if (DELETES.containsKey(path)) {
                    boolean known = DELETES.get(path).apply(tool, request);
                    respond(exchange, known ? 200 : 404, known ? Map.of() : message("no such element"));
                } else {
                    Map<String, Object> answer = POSTS.get(path).apply(tool, request);
                    if (SINGLE_CREATES.contains(path) && answer.get("error") instanceof String reason) {
                        respond(exchange, 422, message(reason));
                    } else {
                        respond(exchange, 200, answer);
                    }
                }
            }
        } catch (Json.Unreadable | InMemoryTool.BadRequest refused) {
            respond(exchange, 400, message(refused.getMessage()));
        } catch (RuntimeException failed) {
            //Never send the stack trace to the peer; the operator reads it in the log
            LOG.log(System.Logger.Level.ERROR, "the request " + path + " failed", failed);
            respond(exchange, 500, message("the service failed on this request"));
        }
    }

    /**
     * Handles {@code exchange} (a request of the {@link HttpsServer}) under
     * the admission time limit. If it expires before {@link #handle} has
     * checked the binding, the service interrupts the thread: the blocked
     * channel operation (handshake, headers) then closes the connection, and
     * the thread is free for the next request.
     */
    private static void withAdmissionDeadline(Runnable exchange, ScheduledThreadPoolExecutor deadlines,
                                              Duration limit) {
        Admission admission = new Admission(Thread.currentThread());
        ScheduledFuture<?> deadline = deadlines.schedule(admission::cut, limit.toMillis(),
                TimeUnit.MILLISECONDS);
        ADMISSION.set(admission);
        try {
            exchange.run();
        } finally {
            ADMISSION.remove();
            deadline.cancel(false);
            admission.close();
        }
    }

    /**
     * The admission of one request: open until {@link #handle} admits it
     * ({@link #admit}) or the time limit cuts it ({@link #cut}), exactly one
     * of the two. Both and {@link #close} hold the same lock: after
     * {@code close}, {@code cut} never interrupts the thread, and
     * {@code close} clears an earlier interrupt, so the next request on the
     * thread inherits none.
     */
    private static final class Admission {

        private final Thread worker;
        private boolean settled;
        private boolean cut;

        Admission(Thread worker) {
            this.worker = worker;
        }

        /** The time limit has expired: cut the request unless it is already admitted. */
        synchronized void cut() {
            if (!settled) {
                settled = true;
                cut = true;
                LOG.log(System.Logger.Level.DEBUG, "a request was not admitted in time; its connection is closed");
                worker.interrupt();
            }
        }

        /** Admits the request; false when the time limit has already cut it. */
        synchronized boolean admit() {
            if (settled) {
                return !cut;
            }
            settled = true;
            return true;
        }

        /** The request has ended. */
        void close() {
            synchronized (this) {
                settled = true;
            }
            Thread.interrupted();
        }
    }

    /** The request body; null when it exceeds {@link #MAX_REQUEST_BYTES}. */
    private static byte[] bodyOf(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_REQUEST_BYTES + 1);
            return body.length > MAX_REQUEST_BYTES ? null : body;
        }
    }

    private static Map<String, Object> message(String text) {
        return Map.of("message", text);
    }

    /**
     * Refuses the request without reading (the rest of) its body and then
     * closes the connection ({@code Connection: close}). If the connection
     * stayed open, the {@link HttpsServer} would read the unread body only
     * after the answer has gone out. If that read also picks up the next
     * request of the peer (its client already has the answer), the server
     * takes the encrypted TLS record of that request from the socket,
     * decrypts only the body and then waits on the socket for bytes that
     * already sit in its buffer: the next request would stay unanswered until
     * the peer gives up. After {@code Connection: close} a client opens a new
     * connection (Mirador and the TCK do).
     */
    private static void refuse(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        exchange.getResponseHeaders().set("Connection", "close");
        respond(exchange, status, body);
    }

    private static void respond(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private SSLContext tlsContext() throws IOException {
        try (InputStream in = Files.newInputStream(settings.keyStore())) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, settings.keyStorePassword());
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, settings.keyStorePassword());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), new TrustManager[]{settings.pins().trustManager()}, null);
            return context;
        } catch (GeneralSecurityException unusable) {
            throw new IOException("the key store " + settings.keyStore() + " is not usable ("
                    + unusable.getClass().getSimpleName() + ")", unusable);
        }
    }
}
