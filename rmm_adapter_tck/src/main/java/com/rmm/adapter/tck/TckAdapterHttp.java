package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.Auth;
import com.rmm.adapter.api.Body;
import com.rmm.adapter.api.RateLimit;
import com.rmm.adapter.api.RedirectPolicy;
import com.rmm.adapter.api.StreamResponse;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TokenProvider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.CookieStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLContext;

import static com.rmm.adapter.tck.TckHttpMessages.describe;
import static com.rmm.adapter.tck.TckHttpMessages.message;

/**
 * The host's HTTP path of a binding in the TCK ({@link TckAdapterContext#http()}), built on the JDK
 * {@link HttpClient} with the semantics that the contract and the host define. {@link AdapterHttpSemantics} runs
 * the same checks against this implementation and against the host's, so that the two do not drift apart and an
 * adapter sees the same behavior in tests as in the host.
 *
 * <ul>
 *   <li><b>Binding marker:</b> every request carries the header {@value #BINDING_HEADER} with a random value per
 *       binding. Only this HTTP path sets it; the fake tool ({@link FakeHttpTool}) does not answer without it
 *       (case 2).</li>
 *   <li><b>Run and deadline:</b> a request belongs to the active run it names (otherwise
 *       {@link IllegalStateException}) or to the run of its thread. Its deadline is at most the limit of the HTTP
 *       path: as in the host, the response timeout ({@link Settings#responseTimeout()}) for an ordinary request
 *       and the stream limit ({@link Settings#streamResponseTimeout()}) for a request whose response
 *       ({@link #open}) or body is a stream. It is also at most the rest of the run's deadline: with nothing left
 *       the HTTP path sends nothing, and running into the run's deadline is final. For {@link #send} the deadline
 *       covers the whole request; for {@link #open} it runs until the headers and then acts as idle timeout
 *       between two chunks. As in the host it is ONE deadline per adapter request: redirect hops, resending after
 *       a 401, fetching the token (including the provider's requests) and waiting for it share it. A stream stays
 *       open for at most {@link #MAX_STREAM_OPEN}.</li>
 *   <li><b>Size:</b> a buffered response counts the decompressed bytes against the maximum size while reading; a
 *       response that announces too large a size fails before the first byte. The HTTP path sets
 *       {@code Accept-Encoding: gzip} itself.</li>
 *   <li><b>Redirects</b> follow {@link RedirectPolicy}; {@code Location} is resolved per RFC 3986, without
 *       fragment. A request with a body follows none: the 3xx response goes to the adapter. Once a hop leaves the
 *       origin of the request, that hop and all later ones (even back to the origin) go without
 *       {@link AdapterRequest#sensitiveHeaders()}, without any sign-in from {@link #auth} (including that of the
 *       new origin) and without cookies from the store, and cookies of such hops are not stored, as in the
 *       host.</li>
 *   <li><b>Sign-in and cookies</b> apply per origin (scheme, host, port; for cookies stricter than a browser, per
 *       RFC 6265, {@link TckCookieJar}). OAuth fetches the token through a view without sign-ins and without
 *       cookie store, never concurrently for the same binding, renews it before it expires (30 s ahead, at most
 *       half its lifetime) and after a 401 at most once per adapter request; the request is then sent a second
 *       time only if it is {@link AdapterRequest#repeatable()}, otherwise the token is just dropped. A provider
 *       that uses this HTTP path instead of its view gets {@link IllegalStateException}. As in the host,
 *       {@code Authorization} (and {@code Cookie}) is set only if the request does not carry one itself.</li>
 *   <li><b>Counting and release:</b> every attempt counts ({@link #attempts()}); streams from {@link #open} are
 *       tracked ({@link #openStreams()}) until the adapter closes them. After {@link #close()}, when the TCK
 *       releases the binding, every request throws {@link IllegalStateException}.</li>
 *   <li><b>Retries</b> as in the host: only {@link AdapterRequest#repeatable()} requests, per hop and per resend
 *       after a 401, after 429, 503 or a transient transport error. The wait is {@code Retry-After}, at most
 *       {@link #MAX_RETRY_AFTER}, otherwise 2, 4, 8 s. There are at most {@link Settings#maxRetries()} retries
 *       (default {@link #MAX_RETRIES}; {@link Settings#forRateLimit} applies the adapter's {@link RateLimit},
 *       {@link Settings#forConnection} also the connection's value of the reserved key {@code http.retries}). No
 *       wait is started that would end after the request's deadline or, as an active wait (not after 429), exceed
 *       the run's deadline. The TCK sleeps at most {@link Settings#longestRealWait()} per wait but charges the
 *       whole wait to the request's deadline ({@link #retryWaits()}), so the number of attempts is the same as in
 *       the host.</li>
 *   <li><b>TLS material, client identity and pinning</b> as in the host ({@link TckTlsMaterial}): trust anchors
 *       and client certificate come from the connection's {@code CERTIFICATE} values, the client identity from
 *       {@code TckAdapterContext.Builder#clientIdentity}. A self-signed leaf used as anchor that the peer presents
 *       validly is accepted without a host name check; every other anchor requires it. If the adapter declares a
 *       {@code CLIENT_IDENTITY} key, the TCK builds the context only with an identity that has not expired (the
 *       host binds only then: otherwise {@code NOT_CONFIGURED ACCESS_NOT_CONFIGURED} or
 *       {@code DEFECT CERTIFICATE_EXPIRED}), requests go out over {@code https} only, and the HTTP path checks the
 *       identity's {@code notAfter} before EVERY attempt, including retries; if the check fails it sends nothing
 *       and the failure is final. A handshake that fails because of the certificate, the name, the protocol, a
 *       peer without TLS or the rejection of the client certificate (TLS 1.3: an alert after the handshake) is
 *       final; one interrupted by a network error is transient. This is the host's classification. TLS 1.3 and
 *       1.2 only.</li>
 * </ul>
 *
 * Differences from the host:
 * <ul>
 *   <li>No address check (the TCK only reaches its own fake tools), no rate limit of the binding and no cap per
 *       target host; {@code http.rate.per.second} therefore does not throttle here.</li>
 *   <li>A binding with TLS material has its own client, whose {@code SelectorManager} thread ends under Java 17
 *       only when the JVM collects the client; {@link #close()} drops the client for that reason (case 4 does not
 *       count the thread).</li>
 *   <li>An upload through {@link #send} (body from {@code Body.ofStream} or {@code Multipart}) has the stream
 *       limit as ONE deadline until the complete response. In the host it is an idle timeout while sending, until
 *       the headers and between two chunks of the response. An upload that makes progress but takes longer than
 *       the limit therefore fails as transient in the TCK and succeeds in the host.</li>
 *   <li>Every stream stays open for at most {@link #MAX_STREAM_OPEN}, also within a run (in a run the host has no
 *       maximum unless the operator sets one).</li>
 *   <li>The run's deadline keeps running while waiting after a 429 (the host stops its clock), and the shortened
 *       waits consume it only for as long as the TCK really waits.</li>
 *   <li>The adapter's {@link RateLimit} and {@code http.retries} reduce the retries only through
 *       {@link Settings#forRateLimit} and {@link Settings#forConnection} (the TCK's context applies both).</li>
 *   <li>The connect timeout is fixed ({@link Settings#CONNECT_TIMEOUT}).</li>
 *   <li>The JDK client cannot reach host names with an underscore.</li>
 *   <li>All bindings share one client with its own threads ({@code rmm-tck-http-<n>} and the JDK's
 *       {@code SelectorManager}), comparable to the event loop threads of the host's HTTP client; case 4 does not
 *       count them.</li>
 *   <li>Messages are not masked (the TCK checks for foreign secrets in case 2 itself), and the cancellation point
 *       of a paused run is missing (also before a retry).</li>
 * </ul>
 *
 * {@link #toString()} shows neither sign-ins nor the binding marker.
 */
public final class TckAdapterHttp implements AdapterHttp {

    /**
     * The header that only the context's HTTP path sets (case 2); a host under the TCK ({@link ContractHost})
     * stamps it as well.
     */
    public static final String BINDING_HEADER = "X-RMM-TCK-Binding";

    /** The longest time a stream stays open; same as the host. */
    static final Duration MAX_STREAM_OPEN = Duration.ofHours(1);
    /** The maximum number of retries of an attempt; same as the host. */
    public static final int MAX_RETRIES = 3;
    /** The longest {@code Retry-After} the HTTP path honors; same as the host. */
    public static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);

    /**
     * The limits of the HTTP path, with the host's defaults.
     *
     * @param responseTimeout  longest deadline of an ordinary request
     *                         ({@code RMM_MODEL_ADAPTER_HTTP_RESPONSE_TIMEOUT_SECONDS})
     * @param maxResponseBytes maximum size of a buffered response
     *                         ({@code RMM_MODEL_ADAPTER_HTTP_MAX_RESPONSE_BYTES})
     * @param maxRetries       maximum number of retries of an attempt (0 to {@link TckAdapterHttp#MAX_RETRIES}); in
     *                         the host this is the adapter's {@link RateLimit#maxRetries()}, or
     *                         {@link TckAdapterHttp#MAX_RETRIES} if it declares none ({@link #forRateLimit})
     * @param longestRealWait  the longest time the TCK really sleeps before a retry; the request's deadline is
     *                         still charged the whole wait (as in the host), so only the check itself stays fast
     * @param streamResponseTimeout longest deadline of a request whose response ({@link TckAdapterHttp#open}) or
     *                         body is a stream ({@code RMM_MODEL_ADAPTER_HTTP_STREAM_RESPONSE_TIMEOUT_SECONDS}); for
     *                         {@code open} also the idle timeout between two chunks
     */
    public record Settings(Duration responseTimeout, int maxResponseBytes, int maxRetries, Duration longestRealWait,
                           Duration streamResponseTimeout) {

        /** Connect timeout; fixed in the TCK. */
        public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
        /** Default for {@link #longestRealWait}. */
        public static final Duration SHORTENED_WAIT = Duration.ofMillis(10);
        /** Default stream limit; the host uses the same value when the operator sets none. */
        public static final Duration STREAM_RESPONSE_TIMEOUT = Duration.ofSeconds(300);

        public Settings {
            if (responseTimeout == null || responseTimeout.isZero() || responseTimeout.isNegative()) {
                throw new IllegalArgumentException("responseTimeout must be positive: " + responseTimeout);
            }
            if (maxResponseBytes < 1) {
                throw new IllegalArgumentException("maxResponseBytes must be positive: " + maxResponseBytes);
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must not be negative: " + maxRetries);
            }
            if (longestRealWait == null || longestRealWait.isNegative()) {
                throw new IllegalArgumentException("longestRealWait must not be negative: " + longestRealWait);
            }
            if (streamResponseTimeout == null || streamResponseTimeout.isZero() || streamResponseTimeout.isNegative()) {
                throw new IllegalArgumentException("streamResponseTimeout must be positive: " + streamResponseTimeout);
            }
            maxRetries = Math.min(maxRetries, MAX_RETRIES);
        }

        /** With ONE deadline for every request: the stream limit equals the response timeout. */
        public Settings(Duration responseTimeout, int maxResponseBytes, int maxRetries, Duration longestRealWait) {
            this(responseTimeout, maxResponseBytes, maxRetries, longestRealWait, responseTimeout);
        }

        /**
         * With the host's retries when no {@link RateLimit} is declared, shortened waiting and ONE deadline for
         * every request (the stream limit equals the response timeout).
         */
        public Settings(Duration responseTimeout, int maxResponseBytes) {
            this(responseTimeout, maxResponseBytes, MAX_RETRIES, SHORTENED_WAIT);
        }

        /** The host's defaults: 120 s, 32 MiB, three retries and a stream limit of 300 s. */
        public static Settings defaults() {
            return new Settings(Duration.ofSeconds(120), 32 * 1024 * 1024, MAX_RETRIES, SHORTENED_WAIT,
                    STREAM_RESPONSE_TIMEOUT);
        }

        /** The same limits with this stream limit (default and upper bound of a stream's deadline). */
        public Settings withStreamResponseTimeout(Duration streamResponseTimeout) {
            return new Settings(responseTimeout, maxResponseBytes, maxRetries, longestRealWait, streamResponseTimeout);
        }

        /** The same limits with the retries the host performs for this {@link RateLimit}. */
        public Settings forRateLimit(RateLimit declared) {
            return new Settings(responseTimeout, maxResponseBytes, declared == null ? MAX_RETRIES
                    : declared.maxRetries(), longestRealWait, streamResponseTimeout);
        }

        /**
         * The same limits with the retries the host performs for this {@link RateLimit} and this connection value
         * of the reserved key {@code http.retries}: {@code min(http.retries, RateLimit#maxRetries())}, at most
         * {@link TckAdapterHttp#MAX_RETRIES}. Without a {@link RateLimit} and without a connection value, the
         * retries of these limits apply.
         *
         * <p>The {@link #maxRetries()} of these limits always cap the result as well. The host has no such
         * setting; with the defaults ({@link #defaults()}, three) the result is exactly the host's. A check that
         * sets fewer retries through {@code TckAdapterContext.Builder#network} keeps them even with a
         * {@link RateLimit} or {@code http.retries}, so the TCK never retries more often than the host.</p>
         *
         * @param declared the adapter's declared rate limit; {@code null} = none
         * @param retries  the connection's value, already capped by {@code http.retries.max}; empty = none
         */
        public Settings forConnection(RateLimit declared, OptionalInt retries) {
            int most = maxRetries;
            if (declared != null) {
                most = Math.min(most, declared.maxRetries());
            }
            if (retries.isPresent()) {
                most = Math.min(most, retries.getAsInt());
            }
            return new Settings(responseTimeout, maxResponseBytes, most, longestRealWait, streamResponseTimeout);
        }
    }

    /** The runs of the binding as the TCK, acting as the host, knows them. */
    interface Runs {
        /** The run the calling thread is bound to. */
        Optional<SyncRunContext> current();

        /**
         * The active run with this identifier, with its deadline taken from the TCK's clock.
         *
         * @throws IllegalStateException if it is not active in this binding
         */
        SyncRunContext active(UUID runId);
    }

    private final String bindingId;
    private final String stamp;
    private final Settings settings;
    private final Runs runs;
    private final AtomicLong attempts;
    private final TckHttpSignIns signIns;
    private final TckCookieJar cookies = new TckCookieJar();
    private final Set<TrackedStream> openStreams = ConcurrentHashMap.newKeySet();
    private final TckHttpRetries retries;
    private final Tls tls;
    /** This binding's client for {@code https} with TLS material; built on first use. */
    private volatile HttpClient tlsClient;
    private volatile boolean closed;

    /**
     * The TLS material of a binding.
     *
     * @param material         trust anchors and client certificates of the connection
     * @param identityRequired whether the adapter declares a {@code CLIENT_IDENTITY} key
     * @param identity         the certificate of the client identity; empty if there is none
     */
    record Tls(TckTlsMaterial material, boolean identityRequired, Optional<X509Certificate> identity) {

        /** Without material and without identity: the shared client. */
        static Tls none(String bindingId) {
            return new Tls(new TckTlsMaterial(bindingId, List.of(), Optional.empty()), false, Optional.empty());
        }
    }

    TckAdapterHttp(String bindingId, String stamp, Settings settings, Runs runs, AtomicLong attempts) {
        this(bindingId, stamp, settings, runs, attempts, Tls.none(bindingId));
    }

    TckAdapterHttp(String bindingId, String stamp, Settings settings, Runs runs, AtomicLong attempts, Tls tls) {
        this.bindingId = bindingId;
        this.stamp = stamp;
        this.settings = settings;
        this.runs = runs;
        this.attempts = attempts;
        this.tls = Objects.requireNonNull(tls, "tls");
        this.signIns = new TckHttpSignIns(bindingId, settings.responseTimeout(), new Unauthenticated());
        this.retries = new TckHttpRetries(settings.maxRetries(), settings.longestRealWait());
    }

    @Override
    public AdapterResponse send(AdapterRequest request) throws AdapterException {
        return (AdapterResponse) exchange(request, false, true);
    }

    @Override
    public StreamResponse open(AdapterRequest request) throws AdapterException {
        return (StreamResponse) exchange(request, true, true);
    }

    @Override
    public void auth(URI origin, Auth auth) {
        Objects.requireNonNull(auth, "auth");
        //The same validation as for a request: absolute, http or https, without credentials
        AdapterRequest.get(Objects.requireNonNull(origin, "origin"));
        signIns.register(originOf(origin), auth);
    }

    /** The sign-in values of this binding, including fetched tokens. */
    Set<String> signIns() {
        return signIns.values();
    }

    /**
     * Whether a thread belongs to a client of the HTTP path: the {@value TckHttpClients#THREAD_PREFIX}-<n> threads
     * and the {@code SelectorManager} threads that the JDK starts for the shared client and for the clients of
     * bindings with TLS material ({@code HttpClient-<n>-SelectorManager}). Case 4 does not count them.
     */
    static boolean ownsThread(String name) {
        return name.startsWith(TckHttpClients.THREAD_PREFIX) || TckHttpClients.ownsSelector(name);
    }

    @Override
    public CookieStore cookies() {
        return cookies;
    }

    @Override
    public Optional<SyncRunContext> currentRun() {
        return runs.current();
    }

    /** Number of attempts that should have reached the tool, across all bindings of the connection. */
    public long attempts() {
        return attempts.get();
    }

    /**
     * The waits before retries of this binding so far, as the host would have waited them ({@code Retry-After}
     * at most {@link #MAX_RETRY_AFTER}, otherwise 2, 4, 8 s); the TCK itself waited at most
     * {@link Settings#longestRealWait()} each time.
     */
    public List<Duration> retryWaits() {
        return retries.waits();
    }

    /** Number of streams from {@link #open} that the adapter has not closed yet. */
    public int openStreams() {
        return openStreams.size();
    }

    /** Whether the TCK has released the binding. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Releases the HTTP path, as the host does when the last lease of a binding is returned: afterwards every
     * request throws {@link IllegalStateException}, and streams that are still open are aborted. Drops the
     * binding's own client so that the JVM can collect it together with its {@code SelectorManager} thread.
     */
    void close() {
        synchronized (this) {
            closed = true;
            tlsClient = null;
        }
        for (TrackedStream stream : List.copyOf(openStreams)) {
            try {
                stream.close();
            } catch (IOException ignored) {
                //already aborted; nothing more to do
            }
        }
    }

    /** Without addresses, sign-ins and the binding marker. */
    @Override
    public String toString() {
        return "TckAdapterHttp[connection " + bindingId + ", " + attempts.get() + " attempts"
                + (closed ? ", closed" : "") + "]";
    }

    // ------------------------------------------------------------------
    // Request flow
    // ------------------------------------------------------------------

    /**
     * One request including the permitted redirects and at most one renewal of an OAuth token, with ONE deadline
     * for all of it (as in the host).
     *
     * @param withAuth whether the binding's sign-ins and cookies apply ({@code false}: the view without sign-in)
     */
    private Object exchange(AdapterRequest request, boolean stream, boolean withAuth) throws AdapterException {
        Objects.requireNonNull(request, "request");
        requireOpen();
        SyncRunContext run = request.run().map(named -> runs.active(named.runId())).or(runs::current).orElse(null);
        Duration limit = limitOf(request, stream);
        long deadline = System.nanoTime() + limit.toNanos();
        Long outer = withAuth ? null : signIns.fetchDeadline();
        if (outer != null && outer - deadline < 0) {
            deadline = outer;
            //The messages then state the time the provider's request had left
            limit = Duration.ofNanos(Math.max(1, outer - System.nanoTime()));
        }
        URI target = request.uri();
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(request.headers());
        boolean left = false;
        boolean renewed = false;
        int hop = 0;
        while (true) {
            requireOpen();
            //Once the chain has left the origin: no sign-in, no cookies, nothing stored (as in the host)
            boolean credentials = withAuth && !left;
            Timing timing = timing(request, run, limit, deadline);
            TckHttpSignIns.Signed signed = credentials && !headers.containsKey("Authorization")
                    ? signIns.authorization(originOf(target), run, deadline) : TckHttpSignIns.Signed.NONE;
            //Before every hop: without https (with an identity) or with unusable material nothing is sent (as in
            //the host)
            HttpClient client = clientFor(request, target);
            TckHttpAnswer answer;
            //Retried as in the host, per hop and per resend: only repeatable(), after 429, 503 or a transient
            //transport error, as often and as long as the host would
            for (int retry = 1; ; retry++) {
                //Before every attempt, including a retry: the identity check, as in the host
                requireUsableIdentity(request);
                HttpRequest prepared = prepare(request, target, headers, signed.value(), credentials);
                attempts.incrementAndGet();
                Optional<Duration> wait;
                try {
                    answer = stream ? openOnce(client, request, target, prepared, timing)
                            : sendOnce(client, request, prepared, timing);
                } catch (AdapterException failed) {
                    wait = retries.afterFailure(request, run, deadline, failed, retry);
                    if (wait.isEmpty()) {
                        throw failed;
                    }
                    deadline = retries.waitBeforeRetry(request, wait.get(), deadline);
                    timing = timing(request, run, limit, deadline);
                    continue;
                }
                if (credentials) {
                    cookies.remember(target, answer.allHeaders("Set-Cookie"));
                }
                wait = retries.afterAnswer(request, run, deadline, answer, retry);
                if (wait.isEmpty()) {
                    break;
                }
                answer.discard();
                deadline = retries.waitBeforeRetry(request, wait.get(), deadline);
                timing = timing(request, run, limit, deadline);
            }
            if (answer.status() == 401 && signed.token() != null) {
                String origin = originOf(target);
                if (renewed || !request.repeatable() || !signIns.usesOAuth(origin)) {
                    //Not sent again: the token is dropped and the next request fetches a new one
                    signIns.drop(origin, signed.token());
                    return answer.result(target);
                }
                //At most once per adapter request, across all hops; if another thread has already fetched a new
                //token, that one is used
                answer.discard();
                signIns.drop(origin, signed.token());
                renewed = true;
                continue;
            }
            Optional<URI> next = TckHttpRedirect.target(request, answer, target, hop + 1);
            if (next.isEmpty()) {
                return answer.result(target);
            }
            answer.discard();
            if (!left && !RedirectPolicy.sameOrigin(request.uri(), next.get())) {
                left = true;
                headers.keySet().removeIf(request.sensitiveHeaders()::contains);
            }
            target = next.get();
            hop++;
        }
    }

    /**
     * The request's deadline, at most the limit of the HTTP path. As in the host, that limit is the stream limit
     * for a request whose response ({@code open}) or body is a stream and the response timeout otherwise. A
     * timeout of the request itself can only shorten it.
     */
    private Duration limitOf(AdapterRequest request, boolean stream) {
        Duration cap = stream || streamsItsBody(request) ? settings.streamResponseTimeout()
                : settings.responseTimeout();
        return request.timeout().filter(wanted -> wanted.compareTo(cap) < 0).orElse(cap);
    }

    /**
     * Whether the request body is a stream, decided as in the host: it comes from {@code Body.ofStream} (not
     * replayable) or is a {@code Multipart} (recognized by its media type {@code multipart/...}).
     */
    private static boolean streamsItsBody(AdapterRequest request) {
        return request.body().map(body -> !body.replayable() || body.contentType()
                .map(type -> type.regionMatches(true, 0, "multipart/", 0, "multipart/".length())).orElse(false))
                .orElse(false);
    }

    /**
     * The deadline of the attempt: the rest of the adapter request's deadline until {@code deadline}
     * ({@link System#nanoTime()}), shortened to the rest of the run's deadline.
     */
    private Timing timing(AdapterRequest request, SyncRunContext run, Duration limit, long deadline)
            throws AdapterException {
        long leftNanos = deadline - System.nanoTime();
        if (leftNanos <= 0) {
            throw new AdapterException(message(request, "no answer within " + describe(limit)
                    + " (earlier attempts, redirects, the token or waiting before a retry took the whole time)"), null,
                    true);
        }
        Duration own = Duration.ofNanos(leftNanos);
        if (run == null || run.deadline() == null) {
            return new Timing(limit, own, null, false);
        }
        Duration left = Duration.between(Instant.now(), run.deadline());
        if (left.isZero() || left.isNegative()) {
            throw new AdapterException(message(request, "the run has reached its deadline"), null, false);
        }
        return left.compareTo(own) < 0 ? new Timing(limit, left, run.deadline(), true)
                : new Timing(limit, own, run.deadline(), false);
    }

    /**
     * The request for the client: headers of the adapter and of the body, the binding marker, sign-in, cookies.
     *
     * @param authorization the HTTP path's {@code Authorization} value; {@code null} = none
     * @param credentials   whether the cookies of the store are sent
     */
    private HttpRequest prepare(AdapterRequest request, URI target, Map<String, List<String>> headers,
                                String authorization, boolean credentials) throws AdapterException {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(target);
            headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        } catch (IllegalArgumentException unsupported) {
            //Without the client's message: it names the whole address including the query
            throw new AdapterException(message(request, "the network path of the TCK cannot send this request"
                    + " (host names with an underscore are not supported)"), null, false);
        }
        builder.setHeader(BINDING_HEADER, stamp);
        builder.setHeader("Accept-Encoding", "gzip");
        request.body().flatMap(Body::contentType).ifPresent(type -> builder.setHeader("Content-Type", type));
        //As in the host: a sign-in or cookie that the request carries itself takes precedence
        if (authorization != null && !headers.containsKey("Authorization")) {
            builder.setHeader("Authorization", authorization);
        }
        if (credentials && !headers.containsKey("Cookie")) {
            cookies.header(target).ifPresent(cookie -> builder.setHeader("Cookie", cookie));
        }
        builder.method(request.method(), TckHttpRequestBody.publisher(request));
        return builder.build();
    }

    /**
     * The client for a request to {@code target}, chosen as in the host: if the adapter declares a client
     * identity, only over {@code https}; with TLS material of the connection the binding's own client
     * ({@link TckTlsMaterial}), otherwise the shared one. Anything else fails for good, without an attempt.
     */
    private HttpClient clientFor(AdapterRequest request, URI target) throws AdapterException {
        boolean https = "https".equalsIgnoreCase(target.getScheme());
        if (tls.identityRequired() && !https) {
            throw new AdapterException(message(request, "connection <" + bindingId + "> presents a client"
                    + " identity and connects only over https - no request is sent"), null, false);
        }
        if (!https || tls.material().isEmpty()) {
            return TckHttpClients.shared();
        }
        HttpClient known = tlsClient;
        if (known != null) {
            return known;
        }
        synchronized (this) {
            //No new client after close(): nobody would drop it anymore
            requireOpen();
            if (tlsClient == null) {
                SSLContext context;
                try {
                    context = tls.material().sslContext();
                } catch (TckTlsMaterial.Unusable unusable) {
                    throw new AdapterException(message(request, unusable.getMessage()), null, false);
                }
                tlsClient = TckHttpClients.with(context);
            }
            return tlsClient;
        }
    }

    /**
     * Runs before every attempt: if the adapter declares a client identity, nothing is sent without it and
     * nothing with one whose {@code notAfter} has been reached. The host checks this per attempt as well; as
     * there, only {@code notAfter} counts, not {@code notBefore}, by the clock of the material
     * ({@link TckTlsMaterial#expired}). Fails for good, without an attempt.
     */
    private void requireUsableIdentity(AdapterRequest request) throws AdapterException {
        if (!tls.identityRequired()) {
            return;
        }
        X509Certificate identity = tls.identity().orElseThrow(() -> new AdapterException(message(request,
                "the client identity of connection <" + bindingId + "> is not usable (the connection has no client"
                        + " identity) - nothing was sent"), null, false));
        if (tls.material().expired(identity)) {
            throw new AdapterException(message(request, "the client identity of connection <" + bindingId
                    + "> has expired - renew its certificate; nothing was sent"), null, false);
        }
    }

    /** One attempt with a buffered response: the deadline applies until the last byte. */
    private TckHttpAnswer sendOnce(HttpClient client, AdapterRequest request, HttpRequest prepared, Timing timing)
            throws AdapterException {
        int limit = settings.maxResponseBytes();
        boolean head = request.method().equals("HEAD");
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(prepared,
                TckHttpResponseBody.buffered(head, limit));
        HttpResponse<byte[]> response;
        try {
            response = await(future, request, timing);
        } finally {
            //The response is read completely or the request has failed: an upload that the tool did not read to
            //the end is not sent any further
            TckHttpRequestBody.closeSourceOf(prepared);
        }
        Map<String, List<String>> headers = response.headers().map();
        byte[] body = response.body();
        if (TckHttpResponseBody.gzipped(response.headers())) {
            body = TckHttpResponseBody.gunzip(request, body, limit);
            headers = TckHttpResponseBody.withoutEncoding(headers);
        }
        return new TckHttpAnswer(response.statusCode(), headers, body, null);
    }

    /** One attempt with a streamed response: returns with the headers; afterwards idle timeout between chunks. */
    private TckHttpAnswer openOnce(HttpClient client, AdapterRequest request, URI target, HttpRequest prepared,
                                   Timing timing) throws AdapterException {
        TckHttpResponseStream body = new TckHttpResponseStream(timing.limit(), timing.deadline(),
                message(request, ""));
        CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(prepared, info -> body);
        HttpResponse<InputStream> response;
        try {
            response = await(future, request, timing);
        } catch (AdapterException failed) {
            body.close();
            TckHttpRequestBody.closeSourceOf(prepared);
            throw failed;
        }
        Map<String, List<String>> headers = response.headers().map();
        OptionalLong length = response.headers().firstValueAsLong("Content-Length");
        InputStream content = body;
        if (TckHttpResponseBody.gzipped(response.headers())) {
            try {
                content = new GZIPInputStream(body);
            } catch (IOException invalid) {
                body.close();
                TckHttpRequestBody.closeSourceOf(prepared);
                throw new AdapterException(message(request, "the tool sent an invalid response ("
                        + describe(invalid) + ")"), null, false);
            }
            headers = TckHttpResponseBody.withoutEncoding(headers);
            length = OptionalLong.empty();
        }
        //When the adapter closes the response, it counts as read: then the source of an upload is closed too
        TrackedStream tracked = new TrackedStream(content, () -> TckHttpRequestBody.closeSourceOf(prepared));
        openStreams.add(tracked);
        try {
            StreamResponse stream = new StreamResponse(response.statusCode(), target, headers, length, tracked);
            return new TckHttpAnswer(response.statusCode(), headers, null, stream);
        } catch (IllegalArgumentException invalid) {
            try {
                tracked.close();
            } catch (IOException ignored) {
                //aborted
            }
            throw new AdapterException(message(request, "the tool sent an invalid response ("
                    + invalid.getMessage() + ")"), null, false);
        }
    }

    /** Waits at most the deadline of the attempt; a failure becomes a transient or final {@link AdapterException}. */
    private <T> HttpResponse<T> await(CompletableFuture<HttpResponse<T>> future, AdapterRequest request,
                                      Timing timing) throws AdapterException {
        try {
            return future.get(timing.timeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException late) {
            future.cancel(true);
            throw timing.atRunDeadline()
                    ? new AdapterException(message(request, "stopped at the deadline of the run after "
                    + describe(timing.timeout())), null, false)
                    : new AdapterException(message(request, "no answer within " + describe(timing.limit())), null,
                    true);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AdapterException(message(request, "interrupted"), null, false);
        } catch (ExecutionException failed) {
            throw failure(request, failed.getCause());
        }
    }

    /** A failure of the exchange as {@link AdapterException}: transient or final, as in the host. */
    private AdapterException failure(AdapterRequest request, Throwable cause) {
        if (TckHttpResponseBody.exceededLimit(cause)) {
            return TckHttpResponseBody.tooLarge(request, settings.maxResponseBytes());
        }
        Throwable unwrapped = cause;
        while (unwrapped instanceof CompletionException && unwrapped.getCause() != null) {
            unwrapped = unwrapped.getCause();
        }
        if (unwrapped instanceof HttpConnectTimeoutException) {
            return new AdapterException(message(request, "no connection within "
                    + describe(Settings.CONNECT_TIMEOUT)), unwrapped, true);
        }
        if (TckTlsHandshakeFailure.matches(unwrapped)) {
            //Final as in the host: no retry fixes the certificate, the name, the protocol, a peer without TLS or a
            //rejected client certificate
            return new AdapterException(message(request, TckTlsHandshakeFailure.describe(unwrapped)), unwrapped,
                    false);
        }
        if (unwrapped instanceof UncheckedIOException || unwrapped instanceof IllegalStateException) {
            //The body could not be read (too short, too long, opened a second time)
            return new AdapterException(message(request, "the request body cannot be read ("
                    + describe(unwrapped) + ")"), null, false);
        }
        if (unwrapped instanceof IOException) {
            return new AdapterException(message(request, "the tool is not reachable (" + describe(unwrapped) + ")"),
                    unwrapped, true);
        }
        return new AdapterException(message(request, "failed (" + describe(unwrapped) + ")"), unwrapped, false);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The network path of connection <" + bindingId + "> is closed");
        }
    }

    /** Scheme, host and port (80 or 443 if not given) in lower case. */
    static String originOf(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? String.valueOf(uri.getRawAuthority()) : uri.getHost();
        int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /**
     * The deadline of an attempt.
     *
     * @param limit         the request's deadline (idle timeout of a stream)
     * @param timeout       what is left of it for this attempt, shortened to the run's deadline
     * @param deadline      the run's deadline ({@code null} = none)
     * @param atRunDeadline whether the run's deadline shortened it
     */
    private record Timing(Duration limit, Duration timeout, Instant deadline, boolean atRunDeadline) {
    }

    /**
     * The view without registered sign-ins for {@link TokenProvider#fetch}: no sign-in, no token and no cookies
     * from the store (none are stored either); its {@code cookies()} throws, as in the host.
     */
    private final class Unauthenticated implements AdapterHttp {

        @Override
        public AdapterResponse send(AdapterRequest request) throws AdapterException {
            return (AdapterResponse) exchange(request, false, false);
        }

        @Override
        public StreamResponse open(AdapterRequest request) throws AdapterException {
            return (StreamResponse) exchange(request, true, false);
        }

        @Override
        public void auth(URI origin, Auth auth) {
            throw new UnsupportedOperationException("The network path for fetching a token has no auth - the token"
                    + " provider puts the credentials of the client into its request");
        }

        @Override
        public CookieStore cookies() {
            throw new UnsupportedOperationException("The network path for fetching a token has no cookie store");
        }

        @Override
        public Optional<SyncRunContext> currentRun() {
            return runs.current();
        }

        @Override
        public String toString() {
            return "TckAdapterHttp[connection " + bindingId + ", without sign-in]";
        }
    }

    /** A stream that the HTTP path tracks until the adapter closes it. */
    private final class TrackedStream extends FilterInputStream {

        private boolean released;
        private final Runnable onClose;

        private TrackedStream(InputStream in, Runnable onClose) {
            super(in);
            this.onClose = onClose;
        }

        @Override
        public void close() throws IOException {
            synchronized (this) {
                if (released) {
                    return;
                }
                released = true;
            }
            openStreams.remove(this);
            try {
                super.close();
            } finally {
                onClose.run();
            }
        }
    }
}
