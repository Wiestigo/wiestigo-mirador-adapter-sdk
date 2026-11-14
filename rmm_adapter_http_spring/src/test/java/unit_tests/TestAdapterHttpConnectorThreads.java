package unit_tests;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.http.spring.AdapterHttpConnector;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The threads of {@link AdapterHttpConnector}: blocking work runs only on
 * connector threads (by thread name, never on a non-blocking thread); every
 * connector has its own threads and releases them; a connector thread logs
 * an uncaught exception itself. {@code close()} also ends what still waits
 * for a connector thread: a queued request and a response body whose read
 * is queued.
 */
class TestAdapterHttpConnectorThreads extends AdapterHttpConnectorFixture {

    // --- Blocking work -------------------------------------------------------

    @Test
    void sendingAndReadingBlockOnlyOnConnectorThreads() {
        int size = 5 * 1024 * 1024 + 7;
        byte[] answer = new byte[3 * 8192 + 5];
        Arrays.fill(answer, (byte) 'x');
        List<String> producers = new CopyOnWriteArrayList<>();
        AtomicInteger bodyReads = new AtomicInteger();
        http.handler = request -> {
            try (InputStream in = http.recorded(request.body().orElseThrow().open(), bodyReads)) {
                assertEquals(size, in.transferTo(OutputStream.nullOutputStream()));
            }
            return http.response(200, Map.of(), http.tracked(new ByteArrayInputStream(answer)),
                    OptionalLong.of(answer.length));
        };

        Long received = client(delayed()).post().uri("/upload")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .body(BodyInserters.fromDataBuffers(chunks(size, 64 * 1024, new AtomicInteger(), producers)))
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                //Every further demand comes from a non-blocking thread
                .publishOn(Schedulers.parallel(), 1)
                .map(buffer -> {
                    long n = buffer.readableByteCount();
                    DataBufferUtils.release(buffer);
                    return n;
                })
                .reduce(0L, Long::sum)
                .block(TIMEOUT);

        assertEquals(answer.length, received);
        assertTrue(bodyReads.get() > 1, "the request body reads were recorded: " + bodyReads.get());
        assertTrue(http.responseReads.get() >= 4, "every response read was recorded: " + http.responseReads.get());
        assertEquals(1 + bodyReads.get() + http.responseReads.get(), http.blockingThreads.size(),
                "open, request body and response reads");
        assertFalse(producers.isEmpty());
        List<String> all = new ArrayList<>(http.blockingThreads);
        all.addAll(producers);
        for (String thread : all) {
            assertTrue(onConnectorThread(thread), "blocking work ran on " + thread);
        }
        assertEquals(List.of(), http.nonBlockingThreads, "blocking work on a non-blocking thread (event loop, timer)");
    }

    /** Even after {@code Mono.delay}, a buffered body is not produced on the timer thread. */
    @Test
    void aBufferedBodyIsProducedOnAConnectorThread() {
        http.handler = request -> http.text(200, "ok");
        List<String> producers = new CopyOnWriteArrayList<>();

        client(delayed()).post().uri("/rest/search").contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromDataBuffers(chunks(3000, 1000, new AtomicInteger(), producers)))
                .retrieve().toBodilessEntity().block(TIMEOUT);

        assertEquals(3, producers.size(), producers.toString());
        for (String thread : producers) {
            assertTrue(onConnectorThread(thread), "the buffered body was produced on " + thread);
        }
        assertTrue(http.requests.get(0).body().orElseThrow().replayable());
    }

    // --- Threads per connector ---------------------------------------------------

    /** Threads per connector, bounded, and {@code close} releases them. */
    @Test
    void everyConnectorHasItsOwnBoundedThreadsAndCloseReleasesThem() throws InterruptedException {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        http.handler = request -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (request.uri().getPath().equals("/slow")) {
                    release.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                }
                return http.text(200, Thread.currentThread().getName());
            } finally {
                active.decrementAndGet();
            }
        };
        WebClient first = client(2);
        AdapterHttpConnector firstConnector = connectors.get(connectors.size() - 1);
        WebClient second = client(2);

        List<Mono<String>> slow = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            slow.add(first.get().uri("/slow").retrieve().bodyToMono(String.class));
        }
        //Released only once both threads are in /slow (not after a fixed time: under load the second one
        //can arrive late); after that a third thread still gets time to show up
        Thread releaser = new Thread(() -> {
            try {
                long until = System.nanoTime() + TIMEOUT.toNanos();
                while (peak.get() < 2 && System.nanoTime() < until) {
                    Thread.sleep(10);
                }
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                //release right away
            }
            release.countDown();
        });
        releaser.start();
        List<String> firstThreads = Flux.merge(slow).collectList().block(TIMEOUT);
        assertNotNull(firstThreads);
        assertEquals(2, peak.get(), "at most the connector's threads open at once");
        String secondThread = second.get().uri("/fast").retrieve().bodyToMono(String.class).block(TIMEOUT);
        assertNotNull(secondThread);
        assertEquals(1, firstThreads.stream().map(TestAdapterHttpConnectorThreads::poolOf).distinct().count(),
                firstThreads.toString());
        assertFalse(firstThreads.stream().map(TestAdapterHttpConnectorThreads::poolOf).toList()
                        .contains(poolOf(secondThread)),
                "two connectors share no threads: " + firstThreads + " / " + secondThread);
        List<Thread> firstPool = http.openThreads.stream()
                .filter(thread -> poolOf(thread.getName()).equals(poolOf(firstThreads.get(0)))).toList();
        assertFalse(firstPool.isEmpty());

        firstConnector.close();

        for (Thread thread : firstPool) {
            thread.join(5000);
            assertFalse(thread.isAlive(), thread.getName() + " still runs after close");
        }
        WebClientRequestException closed = assertThrows(WebClientRequestException.class,
                () -> first.get().uri("/fast").retrieve().toBodilessEntity().block(TIMEOUT));
        assertInstanceOf(IllegalStateException.class, closed.getCause());
        assertNotNull(second.get().uri("/fast").retrieve().bodyToMono(String.class).block(TIMEOUT),
                "the other connector keeps working");
    }

    /**
     * A connector thread logs an exception that a task does not catch
     * itself, as the threads of the Reactor schedulers do. Without a handler
     * of its own the exception would go to the {@code ThreadGroup}, which
     * passes it on to the default handler or to {@code System.err}.
     */
    @Test
    void aConnectorThreadLogsAnUncaughtExceptionItself() {
        http.handler = request -> http.text(200, "ok");
        client().get().uri("/any").retrieve().toBodilessEntity().block(TIMEOUT);
        Thread connectorThread = http.openThreads.get(0);
        assertTrue(onConnectorThread(connectorThread.getName()), connectorThread.getName());
        Thread.UncaughtExceptionHandler handler = connectorThread.getUncaughtExceptionHandler();
        assertFalse(handler instanceof ThreadGroup, "a handler of its own, not the thread group: " + handler);

        List<Throwable> passedOn = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> passedOn.add(failure));
        try {
            handler.uncaughtException(connectorThread, new IllegalStateException("escaped a task (expected by the test)"));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before);
        }
        assertEquals(List.of(), passedOn, "logged by the thread itself, not passed on");
    }

    /** {@code rmm-http-3-1} belongs to the connector {@code rmm-http-3}. */
    private static String poolOf(String thread) {
        return thread.substring(0, thread.lastIndexOf('-'));
    }

    // --- Close ---------------------------------------------------------------

    /**
     * {@code close()} while requests still wait for a connector thread
     * (opening, collecting a body): the scheduler drops their tasks without
     * a signal, yet they fail with {@link IllegalStateException} instead of
     * waiting forever, and every handle ends. The running request is
     * interrupted.
     */
    @Test
    void closeAlsoEndsQueuedRequests() throws InterruptedException {
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        http.handler = request -> {
            busy.countDown();
            try {
                new CountDownLatch(1).await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException closing) {
                interrupted.countDown();
                throw new AdapterException("interrupted", null, false);
            }
            return http.text(200, "late");
        };
        WebClient client = client(1);
        AdapterHttpConnector connector = connectors.get(connectors.size() - 1);
        BlockingQueue<Object> running = new LinkedBlockingQueue<>();
        BlockingQueue<Object> queued = new LinkedBlockingQueue<>();
        client.get().uri("/busy").retrieve().toBodilessEntity().subscribe(running::add, running::add);
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        client.get().uri("/queued").retrieve().toBodilessEntity().subscribe(queued::add, queued::add);
        client.post().uri("/queued-body").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .retrieve().toBodilessEntity().subscribe(queued::add, queued::add);
        assertEquals(1, http.requests.size(), "the other two wait for the connector's only thread");

        connector.close();

        for (int i = 0; i < 2; i++) {
            Object outcome = queued.poll(5, TimeUnit.SECONDS);
            WebClientRequestException failed = assertInstanceOf(WebClientRequestException.class, outcome,
                    "a queued request ends with the connector: " + outcome);
            assertInstanceOf(IllegalStateException.class, failed.getCause());
        }
        assertInstanceOf(WebClientRequestException.class, running.poll(5, TimeUnit.SECONDS));
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the running one was interrupted");
        assertEquals(3, http.awaitingThreads.size());
        http.awaitClosed(3);
        assertEquals(1, http.requests.size(), "the queued ones were never sent");
    }

    /**
     * {@code close()} while the read of a response body still waits for the
     * only connector thread: the scheduler drops the task without a signal,
     * yet the body ends with {@link IllegalStateException} instead of
     * leaving the reader waiting forever, and its stream is closed.
     */
    @Test
    void closeAlsoEndsABodyWhoseReadIsStillQueued() throws InterruptedException {
        CountDownLatch busy = new CountDownLatch(1);
        List<TrackedStream> bodies = new CopyOnWriteArrayList<>();
        http.handler = request -> {
            if (request.uri().getPath().equals("/busy")) {
                busy.countDown();
                try {
                    new CountDownLatch(1).await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException closing) {
                    throw new AdapterException("interrupted", null, false);
                }
            }
            TrackedStream body = http.tracked(new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)));
            bodies.add(body);
            return http.response(200, Map.of(), body, OptionalLong.of(5));
        };
        WebClient client = client(1);
        AdapterHttpConnector connector = connectors.get(connectors.size() - 1);
        ResponseEntity<Flux<DataBuffer>> first = client.get().uri("/first").retrieve()
                .toEntityFlux(DataBuffer.class).block(TIMEOUT);
        assertNotNull(first);
        client.get().uri("/busy").retrieve().toBodilessEntity().subscribe(ignored -> { }, ignored -> { });
        assertTrue(busy.await(5, TimeUnit.SECONDS), "the only thread is busy");
        BlockingQueue<Object> read = new LinkedBlockingQueue<>();
        first.getBody().subscribe(buffer -> {
            DataBufferUtils.release(buffer);
            read.add("chunk");
        }, read::add, () -> read.add("complete"));

        connector.close();

        Object outcome = read.poll(5, TimeUnit.SECONDS);
        //Spring wraps the failure of the body in the response it belongs to
        Throwable failure = assertInstanceOf(Throwable.class, outcome,
                "the queued read ends with the connector: " + outcome);
        while (failure != null && !(failure instanceof IllegalStateException)) {
            failure = failure.getCause();
        }
        assertNotNull(failure, String.valueOf(outcome));
        assertEquals("AdapterHttpConnector is closed", failure.getMessage());
        assertTrue(bodies.get(0).closed.await(5, TimeUnit.SECONDS), "its stream is closed");
        assertFalse(bodies.get(0).ended, "it was never read");
    }
}
