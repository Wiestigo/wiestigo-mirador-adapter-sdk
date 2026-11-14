package unit_tests;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.http.spring.AdapterHttpConnector;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handle from {@code awaitingOwnRequests()} behind
 * {@link AdapterHttpConnector}: taken once per subscription on the
 * subscribing thread; closed after response, failure and cancel, also when
 * building the exchange fails; neither a non-blocking thread nor a
 * connector thread takes one.
 */
class TestAdapterHttpConnectorAwaiting extends AdapterHttpConnectorFixture {

    /**
     * A synchronous method with a filter that resubscribes inside the filter
     * after a 503: one handle per subscription, taken on the waiting thread,
     * open while requests are sent and closed after the response.
     */
    @Test
    void aSynchronousMethodTakesOneHandleOnTheWaitingThread() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        http.handler = request -> calls.incrementAndGet() == 1
                ? http.response(503, Map.of(), InputStream.nullInputStream(), OptionalLong.of(0))
                : http.text(200, "issue");
        SyncToolApi api = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client(throttleWithRetry())))
                .build()
                .createClient(SyncToolApi.class);
        FakeHttp.BOUND.set(RUN);

        assertEquals("issue", api.issue("REQ-7").getBody());

        assertEquals(List.of(Thread.currentThread().getName()), http.awaitingThreads,
                "taken once, on the thread that waits in the proxy, not again for the retry inside the filter");
        assertEquals(List.of(1, 1), http.awaitingOpenAtOpen, "open while both attempts were sent");
        http.awaitClosed(1);
        assertEquals(1, http.awaitingClosed.get());
    }

    /** A request with a body takes the handle too: buffered (JSON) and streamed above 4 MiB. */
    @Test
    void aRequestWithABodyAlsoTakesTheHandle() throws InterruptedException {
        http.handler = request -> {
            try (InputStream in = request.body().orElseThrow().open()) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            return http.text(200, "ok");
        };
        FakeHttp.BOUND.set(RUN);
        WebClient client = client();

        client.post().uri("/rest/search").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"jql\":\"x\"}")
                .retrieve().toBodilessEntity().block(TIMEOUT);
        //The handle closes after the response, on its thread: block() may return first
        http.awaitClosed(1);
        int size = AdapterHttpConnector.BUFFERED_BODY_LIMIT + 1;
        client.put().uri("/attachment").contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(size)
                .body(BodyInserters.fromDataBuffers(chunks(size, 64 * 1024, new AtomicInteger())))
                .retrieve().toBodilessEntity().block(TIMEOUT);

        assertTrue(http.requests.get(0).body().orElseThrow().replayable(), "the first body was buffered");
        assertFalse(http.requests.get(1).body().orElseThrow().replayable(), "the second one streamed");
        String me = Thread.currentThread().getName();
        assertEquals(List.of(me, me), http.awaitingThreads);
        assertEquals(List.of(1, 1), http.awaitingOpenAtOpen);
        http.awaitClosed(2);
    }

    /** Also with the run from {@code contextWrite}: the subscribing thread still waits. */
    @Test
    void withTheRunInTheContextTheWaitingThreadStillTakesTheHandle() throws InterruptedException {
        http.handler = request -> http.text(200, "ok");

        assertEquals("ok", client().get().uri("/rest/x").retrieve().bodyToMono(String.class)
                .contextWrite(AdapterHttpConnector.run(RUN)).block(TIMEOUT));

        assertEquals(List.of(Thread.currentThread().getName()), http.awaitingThreads);
        http.awaitClosed(1);
    }

    @Test
    void theHandleEndsWithAFailure() throws InterruptedException {
        http.handler = request -> {
            throw new AdapterException("tool not reachable", null, true);
        };

        assertThrows(WebClientRequestException.class,
                () -> client().get().uri("/rest/x").retrieve().toBodilessEntity().block(TIMEOUT));

        assertEquals(1, http.awaitingThreads.size());
        http.awaitClosed(1);
        assertEquals(1, http.awaitingClosed.get());
    }

    /** Cancelling before the headers closes the handle with the cancel, on the cancelling thread. */
    @Test
    void theHandleEndsOnCancel() throws InterruptedException {
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        http.handler = request -> {
            opened.countDown();
            try {
                new CountDownLatch(1).await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                throw new AdapterException("interrupted", null, false);
            }
            return http.text(200, "late");
        };

        Disposable subscription = client().get().uri("/rest/slow").retrieve().toBodilessEntity()
                .subscribe(ignored -> { }, ignored -> { });
        assertTrue(opened.await(5, TimeUnit.SECONDS));
        assertEquals(0, http.awaitingClosed.get(), "open while the request waits");
        subscription.dispose();

        assertEquals(1, http.awaitingClosed.get(), "closed with the cancel");
        assertEquals(List.of(Thread.currentThread().getName()), http.awaitingClosedOn);
        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "the cancel itself stays as it was");
    }

    /** A non-blocking thread (timer, parallel scheduler) does not wait: no handle. */
    @Test
    void aNonBlockingThreadTakesNoHandle() {
        http.handler = request -> http.text(200, "ok");
        WebClient client = client();

        assertEquals("ok", Mono.delay(Duration.ofMillis(5))
                .then(client.get().uri("/rest/x").retrieve().bodyToMono(String.class))
                .block(TIMEOUT));
        assertEquals("ok", Mono.defer(() -> client.get().uri("/rest/y").retrieve().bodyToMono(String.class))
                .subscribeOn(Schedulers.parallel())
                .block(TIMEOUT));

        assertEquals(List.of(), http.awaitingThreads, "an event loop or timer takes none");
        assertEquals(2, http.requests.size());
    }

    /** If taking the handle fails (soft limit, run stopped), nothing is sent. */
    @Test
    void whenTakingTheHandleFailsNothingIsSent() {
        http.handler = request -> http.text(200, "ok");
        IllegalStateException stopped = new IllegalStateException("Stopped: tenant SUSPENDED");
        http.awaitingFails = stopped;

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> client().get().uri("/rest/x").retrieve().toBodilessEntity().block(TIMEOUT));

        Throwable cause = failure;
        while (cause != null && cause != stopped) {
            cause = cause.getCause();
        }
        assertSame(stopped, cause, String.valueOf(failure));
        assertEquals(List.of(), http.requests, "nothing was sent");
        assertEquals(0, http.currentRunCalls.get(), "the run is read only after the handle");
    }

    /**
     * If building the exchange fails after the handle is taken (here:
     * reading the run), the filter closes the handle exactly once and
     * nothing is sent.
     */
    @Test
    void whenBuildingTheExchangeFailsTheHandleEndsOnce() throws InterruptedException {
        http.handler = request -> http.text(200, "ok");
        IllegalStateException broken = new IllegalStateException("run not readable");
        http.currentRunFails = broken;

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> client().get().uri("/rest/x").retrieve().toBodilessEntity().block(TIMEOUT));

        Throwable cause = failure;
        while (cause != null && cause != broken) {
            cause = cause.getCause();
        }
        assertSame(broken, cause, String.valueOf(failure));
        assertEquals(List.of(Thread.currentThread().getName()), http.awaitingThreads);
        http.awaitClosed(1);
        assertEquals(1, http.awaitingClosed.get(), "closed exactly once");
        assertEquals(List.of(), http.requests, "nothing was sent");
    }

    /** Two subscriptions of the same thread: two handles, each closed once. */
    @Test
    void twoSubscriptionsOfTheSameThreadTakeTwoHandles() throws InterruptedException {
        http.handler = request -> http.text(200, request.uri().getPath());
        WebClient client = client();

        assertEquals("/a/b", Mono.zip(client.get().uri("/a").retrieve().bodyToMono(String.class),
                client.get().uri("/b").retrieve().bodyToMono(String.class), String::concat).block(TIMEOUT));

        String me = Thread.currentThread().getName();
        assertEquals(List.of(me, me), http.awaitingThreads);
        http.awaitClosed(2);
        assertEquals(2, http.awaitingClosed.get());
    }

    /**
     * A request subscribed by a connector thread (here a {@code concatMap}
     * over the body it is reading) takes no handle: the thread does not wait
     * for the request, and the request must not suspend the slot the thread
     * holds for the run. The thread waiting outside takes its handle as
     * usual.
     */
    @Test
    void aConnectorThreadTakesNoHandle() throws InterruptedException {
        List<String> innerSubscribedOn = new CopyOnWriteArrayList<>();
        http.handler = request -> http.text(200, request.uri().getPath());
        WebClient client = client();
        FakeHttp.BOUND.set(RUN);

        String inner = client.get().uri("/outer").retrieve().bodyToFlux(DataBuffer.class)
                .concatMap(buffer -> {
                    DataBufferUtils.release(buffer);
                    return Mono.defer(() -> {
                        innerSubscribedOn.add(Thread.currentThread().getName());
                        return client.get().uri("/inner").retrieve().bodyToMono(String.class);
                    });
                })
                .blockLast(TIMEOUT);

        assertEquals("/inner", inner);
        assertEquals(1, innerSubscribedOn.size());
        assertTrue(onConnectorThread(innerSubscribedOn.get(0)), "subscribed while the body was read: "
                + innerSubscribedOn);
        assertEquals(List.of(Thread.currentThread().getName()), http.awaitingThreads,
                "only the waiting thread outside took a handle, the connector thread none");
        assertEquals(2, http.requests.size());
        http.awaitClosed(1);
    }
}
