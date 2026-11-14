package com.rmm.adapter.http.spring;

import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.Body;
import com.rmm.adapter.api.SyncRunContext;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.AbstractClientHttpRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.context.ContextView;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * The request that Spring writes into. It does not send by itself: Spring
 * sets headers, attributes and body, then {@link #toAdapterRequest} builds
 * the {@link AdapterRequest} for the host from them. Until then the body
 * stays a {@link Publisher} that nobody has subscribed to.
 */
final class AdapterClientHttpRequest extends AbstractClientHttpRequest {

    private static final Log LOG = LogFactory.getLog(AdapterClientHttpRequest.class);

    /** Transport headers; the host sets them (see {@link AdapterRequest}). */
    private static final Set<String> HOST_HEADERS = Collections.unmodifiableSet(caseInsensitive(Set.of(
            "Host", "Content-Length", "Transfer-Encoding", "Connection", "Keep-Alive", "Upgrade",
            "TE", "Trailer", "Expect", "Proxy-Authorization", "Proxy-Connection", "Accept-Encoding")));

    private final HttpMethod method;
    private final URI uri;
    private final DataBufferFactory bufferFactory;
    private final Scheduler scheduler;
    private final List<PublisherInputStream> streams = new CopyOnWriteArrayList<>();
    private Publisher<? extends DataBuffer> body;
    private AdapterRequest built;

    AdapterClientHttpRequest(HttpMethod method, URI uri, DataBufferFactory bufferFactory, Scheduler scheduler) {
        this.method = method;
        this.uri = uri;
        this.bufferFactory = bufferFactory;
        this.scheduler = scheduler;
    }

    @Override
    public HttpMethod getMethod() {
        return method;
    }

    @Override
    public URI getURI() {
        return uri;
    }

    @Override
    public DataBufferFactory bufferFactory() {
        return bufferFactory;
    }

    /**
     * The {@link AdapterRequest} once it is built; {@code null} before that,
     * including in callbacks of {@code httpRequest(…)}.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getNativeRequest() {
        return (T) built;
    }

    @Override
    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return doCommit(() -> {
            this.body = body;
            return Mono.empty();
        });
    }

    @Override
    public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        return writeWith(Flux.from(body).concatMap(Flux::from));
    }

    @Override
    public Mono<Void> setComplete() {
        return doCommit();
    }

    @Override
    protected void applyHeaders() {
        //toAdapterRequest reads the headers; there is no client of its own here
    }

    /**
     * Cookies of the {@code WebClient} go out as a {@code Cookie} header; the
     * host adds those of its store (see {@link AdapterHttpConnector}).
     */
    @Override
    protected void applyCookies() {
        String cookies = getCookies().values().stream()
                .flatMap(List::stream)
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
        if (!cookies.isEmpty()) {
            getHeaders().add(HttpHeaders.COOKIE, cookies);
        }
    }

    /**
     * Builds the request for the host after Spring has written. A large body
     * of known length flows as a stream; every other body is collected on a
     * connector thread, never on the subscribing one.
     */
    Mono<AdapterRequest> toAdapterRequest(SyncRunContext run, ContextView context) {
        Mono<Void> commit = isCommitted() ? Mono.empty() : doCommit();
        return commit.then(Mono.defer(() -> {
            HttpHeaders headers = getHeaders();
            String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
            long length = headers.getContentLength();
            boolean multipart = contentType != null
                    && contentType.trim().toLowerCase(Locale.ROOT).startsWith("multipart/");
            Boolean idempotent = attribute(AdapterHttpConnector.IDEMPOTENT, Boolean.class);
            Duration timeout = attribute(AdapterHttpConnector.TIMEOUT, Duration.class);
            Publisher<? extends DataBuffer> source = body;
            if (source == null) {
                return Mono.fromCallable(() -> build(headers, null, false, run, idempotent, timeout));
            }
            if (length > AdapterHttpConnector.BUFFERED_BODY_LIMIT || (multipart && length >= 0)) {
                Duration wait = timeout != null ? timeout : AdapterHttpConnector.BODY_WAIT;
                Body stream = Body.ofStream(length, contentType, () -> {
                    PublisherInputStream opened = new PublisherInputStream(source, context, wait);
                    streams.add(opened);
                    return opened;
                });
                return Mono.fromCallable(() -> build(headers, stream, true, run, idempotent, timeout));
            }
            return Flux.from(source)
                    //Also after Mono.delay (timer thread) the source produces its chunks here
                    .subscribeOn(scheduler)
                    .collect(() -> new Collected(length), Collected::add)
                    .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                    .map(collected -> build(headers, bodyOf(collected.bytes(), contentType, multipart), multipart,
                            run, idempotent, timeout));
        }));
    }

    /** Closes every streamed body of this request; a waiting read of the host ends. */
    void closeStreams() {
        streams.forEach(PublisherInputStream::close);
    }

    /** Empty and without a media type (or GET/HEAD) means: no body. */
    private Body bodyOf(byte[] bytes, String contentType, boolean upload) {
        if (bytes.length == 0 && (contentType == null || method == HttpMethod.GET || method == HttpMethod.HEAD)) {
            return null;
        }
        if (upload) {
            return Body.ofStream(bytes.length, contentType, () -> new ByteArrayInputStream(bytes));
        }
        return Body.ofBytes(bytes, contentType);
    }

    private AdapterRequest build(HttpHeaders headers, Body requestBody, boolean upload, SyncRunContext run,
                                 Boolean idempotent, Duration timeout) {
        AdapterRequest.Builder builder = AdapterRequest.builder(method.name(), uri);
        headers.forEach((name, values) -> {
            if (!HOST_HEADERS.contains(name) && !HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                values.forEach(value -> builder.header(name, value));
            }
        });
        if (requestBody != null) {
            builder.body(requestBody);
        }
        if (idempotent != null) {
            if (idempotent && upload && requestBody != null) {
                //An upload is never repeated, even if it is marked idempotent
                LOG.debug("request marked idempotent carries an upload; it stays non-idempotent: "
                        + method.name() + " " + AdapterRequest.forLog(uri));
            } else {
                builder.idempotent(idempotent);
            }
        }
        if (timeout != null) {
            builder.timeout(timeout);
        }
        if (run != null) {
            builder.run(run);
        }
        built = builder.build();
        return built;
    }

    private <T> T attribute(String name, Class<T> type) {
        Object value = getAttributes().get(name);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("request attribute " + name + " must be a " + type.getSimpleName()
                    + ", not " + value.getClass().getName());
        }
        return type.cast(value);
    }

    private static Set<String> caseInsensitive(Set<String> names) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(names);
        return set;
    }

    /**
     * Collects the body directly into an array, with a known length into
     * exactly one of that size; every chunk is released at once. Without a
     * length the array grows up to at most
     * {@link AdapterHttpConnector#BUFFERED_BODY_LIMIT}.
     */
    private static final class Collected {

        private final long declared;
        private byte[] bytes;
        private int size;

        Collected(long declared) {
            this.declared = declared;
            this.bytes = new byte[declared >= 0 ? (int) declared : 8192];
        }

        void add(DataBuffer buffer) {
            try {
                int n = buffer.readableByteCount();
                long total = (long) size + n;
                if (declared >= 0 && total > declared) {
                    throw new IllegalArgumentException("request body has more than " + declared
                            + " bytes, but Content-Length declares " + declared);
                }
                if (total > AdapterHttpConnector.BUFFERED_BODY_LIMIT) {
                    throw new IllegalArgumentException("request body without Content-Length is larger than "
                            + AdapterHttpConnector.BUFFERED_BODY_LIMIT + " bytes; set Content-Length to stream it");
                }
                if (total > bytes.length) {
                    bytes = Arrays.copyOf(bytes,
                            (int) Math.min(AdapterHttpConnector.BUFFERED_BODY_LIMIT, Math.max(total, 2L * bytes.length)));
                }
                buffer.read(bytes, size, n);
                size += n;
            } finally {
                DataBufferUtils.release(buffer);
            }
        }

        byte[] bytes() {
            if (declared >= 0 && size != declared) {
                throw new IllegalArgumentException("request body has " + size
                        + " bytes, but Content-Length declares " + declared);
            }
            return size == bytes.length ? bytes : Arrays.copyOf(bytes, size);
        }
    }
}
