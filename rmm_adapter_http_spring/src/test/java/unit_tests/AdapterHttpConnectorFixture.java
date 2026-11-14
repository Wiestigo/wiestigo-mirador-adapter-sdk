package unit_tests;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.Auth;
import com.rmm.adapter.api.StreamResponse;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.http.spring.AdapterHttpConnector;
import org.junit.jupiter.api.AfterEach;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.service.annotation.GetExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shared fixture of the tests of {@link AdapterHttpConnector}: a JDK
 * stand-in for the host's HTTP path ({@link FakeHttp}), web clients
 * configured like an adapter's, and the bodies the tests send. A test class
 * extends it; the connectors of a test are closed after it.
 */
abstract class AdapterHttpConnectorFixture {

    static final Duration TIMEOUT = Duration.ofSeconds(10);
    static final URI TOOL = URI.create("https://tool.test");
    static final SyncRunContext RUN = new SyncRunContext(UUID.randomUUID(), "PRJ");
    static final SyncRunContext OTHER_RUN = new SyncRunContext(UUID.randomUUID(), "PRJ");
    private static final String THREAD_PREFIX = "rmm-http-";

    /** The stand-in for the host's HTTP path that every client of the test sends through. */
    final FakeHttp http = new FakeHttp();
    /** The connectors of the clients built in the test, closed after it. */
    final List<AdapterHttpConnector> connectors = new CopyOnWriteArrayList<>();

    @AfterEach
    void unbind() {
        FakeHttp.BOUND.remove();
        connectors.forEach(AdapterHttpConnector::close);
    }

    WebClient client(ExchangeFilterFunction... filters) {
        return client(AdapterHttpConnector.DEFAULT_THREADS, filters);
    }

    /** Like an adapter: its own filters first, then {@code configure} (capturing the run outermost). */
    WebClient client(int threads, ExchangeFilterFunction... filters) {
        WebClient.Builder builder = WebClient.builder().baseUrl(TOOL.toString());
        Arrays.stream(filters).forEach(builder::filter);
        connectors.add(AdapterHttpConnector.configure(builder, http, threads));
        return builder.build();
    }

    /** Like a throttle in an adapter: the request itself goes out only after a pause on the timer thread. */
    static ExchangeFilterFunction delayed() {
        return (request, next) -> Mono.delay(Duration.ofMillis(20)).then(next.exchange(request));
    }

    /**
     * A throttle that also retries: a pause on the timer thread, then the
     * request, resubscribed with {@code retryWhen} after a 503, all inside
     * the filter.
     */
    static ExchangeFilterFunction throttleWithRetry() {
        return (request, next) -> Mono.delay(Duration.ofMillis(20))
                .then(next.exchange(request))
                .flatMap(response -> response.statusCode().value() == 503
                        ? response.releaseBody().then(Mono.<ClientResponse>error(new IllegalStateException("busy")))
                        : Mono.just(response))
                .retryWhen(Retry.fixedDelay(2, Duration.ofMillis(10)));
    }

    static boolean onConnectorThread(String thread) {
        return thread.startsWith(THREAD_PREFIX);
    }

    /** Spring itself blocks in the proxy of a synchronous method, which leaves no place for contextWrite. */
    interface SyncToolApi {
        @GetExchange("/rest/issue/{key}")
        ResponseEntity<String> issue(@PathVariable("key") String key);
    }

    /** {@code size} bytes in pieces of {@code chunk}; counts the pieces produced. */
    static Flux<DataBuffer> chunks(int size, int chunk, AtomicInteger produced) {
        return chunks(size, chunk, produced, new CopyOnWriteArrayList<>());
    }

    /** As above; also records the thread each piece was produced on. */
    static Flux<DataBuffer> chunks(int size, int chunk, AtomicInteger produced, List<String> threads) {
        return Flux.generate(() -> 0, (sent, sink) -> {
            if (sent >= size) {
                sink.complete();
                return sent;
            }
            int n = Math.min(chunk, size - sent);
            produced.incrementAndGet();
            threads.add(Thread.currentThread().getName() + (Schedulers.isInNonBlockingThread() ? " (non-blocking)" : ""));
            sink.next(DefaultDataBufferFactory.sharedInstance.wrap(new byte[n]));
            return sent + n;
        });
    }

    static DataBuffer buffer(String text) {
        return buffer(text.getBytes(StandardCharsets.UTF_8));
    }

    static DataBuffer buffer(byte[] bytes) {
        return DefaultDataBufferFactory.sharedInstance.wrap(bytes);
    }

    /** A body that records whether it was read to its end and whether it was closed. */
    static final class TrackedStream extends InputStream {

        private final InputStream delegate;
        private final FakeHttp http;
        final CountDownLatch closed = new CountDownLatch(1);
        volatile boolean ended;

        TrackedStream(InputStream delegate, FakeHttp http) {
            this.delegate = delegate;
            this.http = http;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int n = read(single, 0, 1);
            return n < 0 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int count) throws IOException {
            http.blocking();
            http.responseReads.incrementAndGet();
            int n = delegate.read(buffer, offset, count);
            if (n < 0) {
                ended = true;
            }
            return n;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    /**
     * JDK stand-in for the HTTP path of the host. Without a run in the
     * request it takes, like the host, the one bound to the thread
     * ({@link #BOUND}).
     */
    static final class FakeHttp implements AdapterHttp {

        static final ThreadLocal<SyncRunContext> BOUND = new ThreadLocal<>();

        interface Handler {
            StreamResponse answer(AdapterRequest request) throws Exception;
        }

        volatile Handler handler;
        final List<AdapterRequest> requests = new CopyOnWriteArrayList<>();
        final List<Optional<SyncRunContext>> resolvedRuns = new CopyOnWriteArrayList<>();
        final List<String> blockingThreads = new CopyOnWriteArrayList<>();
        final List<String> nonBlockingThreads = new CopyOnWriteArrayList<>();
        final List<Thread> openThreads = new CopyOnWriteArrayList<>();
        final AtomicInteger responseReads = new AtomicInteger();
        final AtomicInteger currentRunCalls = new AtomicInteger();
        private final CookieStore cookies = new CookieManager().getCookieStore();

        void blocking() {
            String thread = Thread.currentThread().getName();
            blockingThreads.add(thread);
            if (Schedulers.isInNonBlockingThread()) {
                nonBlockingThreads.add(thread);
            }
        }

        TrackedStream tracked(InputStream body) {
            return new TrackedStream(body, this);
        }

        /** A request body whose reads are recorded as blocking work. */
        InputStream recorded(InputStream body, AtomicInteger reads) {
            FakeHttp fake = this;
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    byte[] single = new byte[1];
                    int n = read(single, 0, 1);
                    return n < 0 ? -1 : single[0] & 0xff;
                }

                @Override
                public int read(byte[] buffer, int offset, int count) throws IOException {
                    fake.blocking();
                    reads.incrementAndGet();
                    return body.read(buffer, offset, count);
                }

                @Override
                public void close() throws IOException {
                    body.close();
                }
            };
        }

        StreamResponse response(int status, Map<String, List<String>> headers, InputStream body, OptionalLong length) {
            return new StreamResponse(status, URI.create("https://tool.test/answer"), headers, length, body);
        }

        StreamResponse text(int status, String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return response(status, Map.of("Content-Type", List.of("text/plain;charset=UTF-8")),
                    tracked(new ByteArrayInputStream(bytes)), OptionalLong.of(bytes.length));
        }

        @Override
        public StreamResponse open(AdapterRequest request) throws AdapterException {
            blocking();
            openThreads.add(Thread.currentThread());
            awaitingOpenAtOpen.add(awaitingOpen.get());
            requests.add(request);
            resolvedRuns.add(request.run().or(() -> Optional.ofNullable(BOUND.get())));
            try {
                return handler.answer(request);
            } catch (AdapterException | RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new AdapterException("fake failed", failure);
            }
        }

        @Override
        public AdapterResponse send(AdapterRequest request) {
            throw new UnsupportedOperationException("the facade always streams");
        }

        @Override
        public void auth(URI origin, Auth auth) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CookieStore cookies() {
            return cookies;
        }

        /** Like the host: the run the calling thread is bound to. */
        @Override
        public Optional<SyncRunContext> currentRun() {
            currentRunCalls.incrementAndGet();
            RuntimeException failure = currentRunFails;
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(BOUND.get());
        }

        /** If set, reading the run throws it (after the handle is taken). */
        volatile RuntimeException currentRunFails;

        /** The threads that took a handle, in order. */
        final List<String> awaitingThreads = new CopyOnWriteArrayList<>();
        /** How many handles are closed (each once) and on which thread. */
        final AtomicInteger awaitingClosed = new AtomicInteger();
        final List<String> awaitingClosedOn = new CopyOnWriteArrayList<>();
        /** How many handles were open when an attempt was sent (the host would see the thread waiting). */
        final List<Integer> awaitingOpenAtOpen = new CopyOnWriteArrayList<>();
        /** If set, taking a handle throws it (the soft limit of a stopped run). */
        volatile RuntimeException awaitingFails;
        private final AtomicInteger awaitingOpen = new AtomicInteger();

        /** Like the host: records the thread and the close; each handle closes once. */
        @Override
        public Awaiting awaitingOwnRequests() {
            awaitingThreads.add(Thread.currentThread().getName()
                    + (Schedulers.isInNonBlockingThread() ? " (non-blocking)" : ""));
            RuntimeException failure = awaitingFails;
            if (failure != null) {
                throw failure;
            }
            awaitingOpen.incrementAndGet();
            AtomicBoolean closed = new AtomicBoolean();
            return () -> {
                if (closed.compareAndSet(false, true)) {
                    awaitingOpen.decrementAndGet();
                    awaitingClosedOn.add(Thread.currentThread().getName());
                    awaitingClosed.incrementAndGet();
                }
            };
        }

        /** Waits until {@code count} handles are closed (after the response, on its thread). */
        void awaitClosed(int count) throws InterruptedException {
            long until = System.nanoTime() + TIMEOUT.toNanos();
            while (awaitingClosed.get() < count) {
                if (System.nanoTime() - until > 0) {
                    throw new AssertionError("only " + awaitingClosed.get() + " of " + count + " handles closed");
                }
                Thread.sleep(1);
            }
        }
    }
}
