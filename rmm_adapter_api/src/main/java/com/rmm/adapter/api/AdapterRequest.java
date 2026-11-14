package com.rmm.adapter.api;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * A request through {@link AdapterHttp}; immutable.
 *
 * <ul>
 *   <li><b>Address:</b> absolute, {@code http} or {@code https}, with a host
 *       and without credentials. Host names with an underscore (container
 *       names) are allowed ({@link #host()}); internationalized names are in
 *       Punycode ({@code xn--…}). The host checks at send time whether the
 *       address may be reached (address protection; multi-tenant operation
 *       allows only {@code https}).</li>
 *   <li><b>Methods:</b> GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS. GET and
 *       HEAD carry no body.</li>
 *   <li><b>Headers:</b> free, several values per name. The host sets the
 *       transport headers ({@code Host}, {@code Content-Length},
 *       {@code Transfer-Encoding}, {@code Connection}, {@code Accept-Encoding}
 *       …); {@code Content-Type} comes from the {@link Body}. Because the host
 *       owns {@code Accept-Encoding}, the adapter always receives the body
 *       decompressed, and the maximum size of a buffered response counts
 *       decompressed bytes.</li>
 *   <li><b>Credentials in headers</b> ({@link #sensitiveHeaders()}):
 *       {@code Cookie}, every name containing {@code auth}, {@code token},
 *       {@code secret}, {@code password}, {@code session}, {@code credential},
 *       {@code signature}, {@code api-key}, {@code api_key} or {@code apikey}
 *       (so also {@code Authorization}, {@code X-API-Key} and
 *       {@code PRIVATE-TOKEN}), and every header set through
 *       {@link Builder#secretHeader}. The host sends them only to the origin
 *       of the request and drops them once a redirect leaves it
 *       ({@link RedirectPolicy}).</li>
 *   <li><b>Idempotent</b> ({@link #idempotent()}): by default GET, HEAD, PUT,
 *       DELETE and OPTIONS are, POST and PATCH are not. A reading POST (such
 *       as a search) may be marked idempotent
 *       ({@link Builder#idempotent(boolean)}). An upload (a body from
 *       {@link Body#ofStream} or {@link Multipart}) never is, not even as PUT.
 *       The host retries only {@link #repeatable()} requests.</li>
 *   <li><b>Deadline</b> ({@link #timeout()}; the host's default if not set):
 *       <ul>
 *         <li>{@link AdapterHttp#send}: covers the whole request up to the
 *             fully buffered response, including connection setup, redirects,
 *             the resend after a 401, fetching an OAuth token, waiting at the
 *             rate limit and at the cap per target host, and the delays before
 *             retries. What could only be sent after the deadline fails as
 *             transient without being sent, so do not set it tight for
 *             parallel requests of a binding with a {@link RateLimit}.</li>
 *         <li>An upload through {@code send} (a body from
 *             {@link Body#ofStream} or {@link Multipart}, even one built from
 *             bytes): an idle timeout instead. It applies until the first
 *             chunk is sent, to every stall while the body is sent (the peer
 *             accepts nothing or the source delivers nothing), from the end of
 *             the body to the response headers, and between two chunks of the
 *             response. An upload that makes progress is limited only by a
 *             maximum duration the operator may set (default: none) and,
 *             without an active run, by 60 min from {@code send()}; both are
 *             final. A stall is transient
 *             ({@link AdapterException#isRetryable()}), but the host never
 *             retries an upload. For the duties of the stream source see
 *             {@link Body#ofStream}.</li>
 *         <li>{@link AdapterHttp#open}: applies until the response headers
 *             (with a stream body: for sending and waiting together, without
 *             an idle timeout while sending) and then as an idle timeout
 *             between two chunks read. The whole transfer has no limit, so
 *             that gigabyte downloads complete, other than the same maximum
 *             durations counted from {@code open()} and the end of a stream
 *             the adapter has not read from for 60 min.</li>
 *       </ul>
 *       The host caps the deadline at its maximum (for uploads and streams at
 *       its stream limit) and at the deadline of the run; reaching the run's
 *       deadline is final.</li>
 *   <li><b>Run</b> ({@link #run()}): assigns the request to a run (deadline,
 *       stop, metrics). If not set, the host uses the run the calling thread
 *       is bound to (a hook call or a task from
 *       {@link AdapterExecutor#submit}); facades that send on other threads
 *       pass it here. The host uses only {@link SyncRunContext#runId()} and
 *       throws {@link IllegalStateException} if that run is not active in this
 *       binding (a run of another binding, a finished or an invented run). It
 *       takes the deadline from its own clock, never from
 *       {@link SyncRunContext#deadline()}, which may be stale.</li>
 * </ul>
 *
 * <p>{@link #toString()} shows neither headers nor body nor the query of the
 * address; {@link #forLog(URI)} gives the same form for the adapter's own
 * messages.
 */
public final class AdapterRequest {

    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS");
    private static final Set<String> HOST_HEADERS = Collections.unmodifiableSet(caseInsensitive(Set.of(
            "Host", "Content-Length", "Transfer-Encoding", "Connection", "Keep-Alive", "Upgrade",
            "TE", "Trailer", "Expect", "Proxy-Authorization", "Proxy-Connection", "Accept-Encoding")));

    private final String method;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final Set<String> secretHeaders;
    private final Set<String> sensitiveHeaders;
    private final Body body;
    private final Duration timeout;
    private final RedirectPolicy redirects;
    private final Boolean idempotentOverride;
    private final boolean idempotent;
    private final SyncRunContext run;

    private AdapterRequest(Builder builder) {
        this.method = builder.method;
        this.uri = builder.uri;
        this.headers = HttpRules.copyHeaders(builder.headers);
        this.secretHeaders = Collections.unmodifiableSet(caseInsensitive(builder.secretHeaders));
        Set<String> sensitive = caseInsensitive(Set.of());
        for (String name : headers.keySet()) {
            if (secretHeaders.contains(name) || HttpRules.isSensitiveHeader(name)) {
                sensitive.add(name);
            }
        }
        this.sensitiveHeaders = Collections.unmodifiableSet(sensitive);
        this.body = builder.body;
        this.timeout = builder.timeout;
        this.redirects = builder.redirects;
        this.idempotentOverride = builder.idempotent;
        boolean upload = body != null && body.upload();
        boolean byMethod = idempotentOverride != null ? idempotentOverride : IDEMPOTENT_METHODS.contains(method);
        this.idempotent = !upload && byMethod;
        this.run = builder.run;
    }

    public static AdapterRequest get(URI uri) {
        return builder("GET", uri).build();
    }

    public static AdapterRequest head(URI uri) {
        return builder("HEAD", uri).build();
    }

    public static AdapterRequest delete(URI uri) {
        return builder("DELETE", uri).build();
    }

    public static AdapterRequest post(URI uri, Body body) {
        return builder("POST", uri).body(Objects.requireNonNull(body, "body")).build();
    }

    public static AdapterRequest put(URI uri, Body body) {
        return builder("PUT", uri).body(Objects.requireNonNull(body, "body")).build();
    }

    /** The general form; the method is kept in upper case. */
    public static Builder builder(String method, URI uri) {
        return new Builder(method, uri);
    }

    /**
     * An address for logs and messages: scheme, host, port and path; a query
     * only as {@code ?…}, no fragment. Queries often carry secrets
     * ({@code api_key=…}, signed addresses behind a redirect).
     */
    public static String forLog(URI uri) {
        return HttpRules.forLog(uri);
    }

    /**
     * A copy to modify, for example with another header. An explicit mark as
     * (not) idempotent is kept; the default by method and body is applied anew
     * to the copy.
     */
    public Builder toBuilder() {
        Builder builder = new Builder(method, uri);
        headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        builder.secretHeaders.addAll(secretHeaders);
        builder.body = body;
        builder.timeout = timeout;
        builder.redirects = redirects;
        builder.idempotent = idempotentOverride;
        builder.run = run;
        return builder;
    }

    public String method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    /**
     * The host of the address in lower case, also for names with an underscore
     * ({@code jira_server}), for which {@link URI#getHost()} returns
     * {@code null}.
     */
    public String host() {
        return HttpRules.hostOf(uri);
    }

    /** The port of the address; if not given, 443 for {@code https}, otherwise 80. */
    public int port() {
        return HttpRules.portOf(uri);
    }

    /** All headers; names are case-insensitive. */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /** The first value of the header. */
    public Optional<String> header(String name) {
        return HttpRules.firstHeader(headers, name);
    }

    /** The names of the headers of this request that may carry credentials (see the class); case-insensitive. */
    public Set<String> sensitiveHeaders() {
        return sensitiveHeaders;
    }

    public Optional<Body> body() {
        return Optional.ofNullable(body);
    }

    public Optional<Duration> timeout() {
        return Optional.ofNullable(timeout);
    }

    public RedirectPolicy redirects() {
        return redirects;
    }

    /** Whether the request may be repeated without harm; see the class description. */
    public boolean idempotent() {
        return idempotent;
    }

    /**
     * Idempotent and either without a body or with one that can be read again:
     * only then may the host send the request once more (429, 503, timeout).
     * An upload never is.
     */
    public boolean repeatable() {
        return idempotent && (body == null || body.replayable());
    }

    public Optional<SyncRunContext> run() {
        return Optional.ofNullable(run);
    }

    /** Without headers, body and the query of the address: they may carry secrets. */
    @Override
    public String toString() {
        return "AdapterRequest[" + method + " " + forLog(uri) + "]";
    }

    /** Builds an {@link AdapterRequest}; validates every input immediately. */
    public static final class Builder {

        private final String method;
        private final URI uri;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private final Set<String> secretHeaders = caseInsensitive(Set.of());
        private Body body;
        private Duration timeout;
        private RedirectPolicy redirects = RedirectPolicy.never();
        private Boolean idempotent;
        private SyncRunContext run;

        private Builder(String method, URI uri) {
            String normalized = method == null ? null : method.trim().toUpperCase(Locale.ROOT);
            if (normalized == null || !METHODS.contains(normalized)) {
                throw new IllegalArgumentException("method <" + method + "> is not supported — use one of "
                        + new TreeSet<>(METHODS));
            }
            this.method = normalized;
            this.uri = HttpRules.requireHttpUri(uri, "request URL");
        }

        /** Appends a value; several values per name are kept. */
        public Builder header(String name, String value) {
            if (!HttpRules.isToken(name)) {
                throw new IllegalArgumentException("header name <" + name + "> is not a valid token");
            }
            if (HOST_HEADERS.contains(name)) {
                throw new IllegalArgumentException("header <" + name + "> is set by the host");
            }
            if (name.equalsIgnoreCase("Content-Type")) {
                throw new IllegalArgumentException("header <Content-Type> comes from the body");
            }
            if (!HttpRules.isHeaderValue(value)) {
                throw new IllegalArgumentException("header <" + name + "> has no value or contains control characters");
            }
            String key = headers.keySet().stream().filter(name::equalsIgnoreCase).findFirst().orElse(name);
            headers.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            return this;
        }

        /**
         * Appends a header carrying a secret whose name is none of the known
         * ones (for example {@code X-Tool-Key}). It counts among the
         * {@link AdapterRequest#sensitiveHeaders()}: the host removes it when
         * the origin changes.
         */
        public Builder secretHeader(String name, SecretValue value) {
            Objects.requireNonNull(value, "value");
            header(name, value.reveal());
            secretHeaders.add(name);
            return this;
        }

        public Builder body(Body body) {
            this.body = Objects.requireNonNull(body, "body");
            return this;
        }

        /** Deadline for the response, greater than 0; its meaning with {@code send} or {@code open}: see the class. */
        public Builder timeout(Duration timeout) {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive: " + timeout);
            }
            this.timeout = timeout;
            return this;
        }

        /** The redirect policy; a request with a body follows no redirect in any case. */
        public Builder redirects(RedirectPolicy redirects) {
            this.redirects = Objects.requireNonNull(redirects, "redirects");
            return this;
        }

        /**
         * Overrides the default by method, for example for a reading POST.
         * {@code true} with an upload fails in {@link #build()}.
         */
        public Builder idempotent(boolean idempotent) {
            this.idempotent = idempotent;
            return this;
        }

        /** The run of the request; the host uses only its identifier, see the class. */
        public Builder run(SyncRunContext run) {
            this.run = Objects.requireNonNull(run, "run");
            return this;
        }

        public AdapterRequest build() {
            if (body != null && (method.equals("GET") || method.equals("HEAD"))) {
                throw new IllegalStateException(method + " request must not have a body");
            }
            if (Boolean.TRUE.equals(idempotent) && body != null && body.upload()) {
                throw new IllegalStateException("an upload is never repeated — it cannot be marked idempotent");
            }
            return new AdapterRequest(this);
        }
    }

    private static Set<String> caseInsensitive(Set<String> names) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(names);
        return set;
    }
}
