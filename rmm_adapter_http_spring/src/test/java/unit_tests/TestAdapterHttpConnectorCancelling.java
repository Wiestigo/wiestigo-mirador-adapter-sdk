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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cancelling a request that goes through {@link AdapterHttpConnector}: the
 * cancel aborts and closes the response stream, interrupts a waiting open,
 * closes a response nobody waits for and closes a streamed request body.
 */
class TestAdapterHttpConnectorCancelling extends AdapterHttpConnectorFixture {

    @Test
    void cancellingAbortsTheStream() throws InterruptedException {
        TrackedStream endless = http.tracked(new InputStream() {
            @Override
            public int read() {
                return 'z';
            }

            @Override
            public int read(byte[] buffer, int offset, int count) {
                Arrays.fill(buffer, offset, offset + count, (byte) 'z');
                return count;
            }
        });
        http.handler = request -> http.response(200, Map.of(), endless, OptionalLong.empty());

        client().get().uri("/download").retrieve().bodyToFlux(DataBuffer.class)
                .doOnNext(DataBufferUtils::release)
                .take(3)
                .blockLast(TIMEOUT);

        assertTrue(endless.closed.await(5, TimeUnit.SECONDS), "cancel closes the stream");
        assertFalse(endless.ended, "closed before its end = abort");
    }

    @Test
    void cancellingBeforeTheHeadersInterruptsTheOpen() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        http.handler = request -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException stop) {
                interrupted.countDown();
                throw new AdapterException("stopped");
            }
            return http.text(200, "late");
        };

        Disposable subscription = client().get().uri("/slow").retrieve().toBodilessEntity().subscribe();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        subscription.dispose();

        assertTrue(interrupted.await(5, TimeUnit.SECONDS), "cancel interrupts the waiting open");
    }

    @Test
    void aResponseAfterCancellingIsClosed() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TrackedStream late = http.tracked(new ByteArrayInputStream(new byte[100]));
        http.handler = request -> {
            entered.countDown();
            //A host that cannot be interrupted still delivers a response
            boolean done = false;
            while (!done) {
                try {
                    release.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    done = true;
                } catch (InterruptedException ignored) {
                    //keep waiting
                }
            }
            return http.response(200, Map.of(), late, OptionalLong.of(100));
        };

        Disposable subscription = client().get().uri("/slow").retrieve().toBodilessEntity().subscribe();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        subscription.dispose();
        release.countDown();

        assertTrue(late.closed.await(5, TimeUnit.SECONDS), "a response nobody waits for is closed");
    }

    /**
     * Cancelled before the subscription of the body ran on a connector
     * thread (the only one is busy): the stream is closed all the same.
     */
    @Test
    void cancellingBeforeTheFirstReadClosesTheStream() throws InterruptedException {
        TrackedStream first = http.tracked(new ByteArrayInputStream(new byte[100]));
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        http.handler = request -> {
            if (request.uri().getPath().equals("/first")) {
                return http.response(200, Map.of(), first, OptionalLong.of(100));
            }
            busy.countDown();
            release.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return http.text(200, "second");
        };
        WebClient client = client(1);

        ResponseEntity<Flux<DataBuffer>> entity = client.get().uri("/first").retrieve()
                .toEntityFlux(DataBuffer.class).block(TIMEOUT);
        assertNotNull(entity);
        Disposable second = client.get().uri("/second").retrieve().toBodilessEntity().subscribe();
        try {
            assertTrue(busy.await(5, TimeUnit.SECONDS), "the only thread is taken");
            entity.getBody().subscribe(DataBufferUtils::release).dispose();

            assertTrue(first.closed.await(5, TimeUnit.SECONDS), "cancel before the first read closes the stream");
            assertFalse(first.ended);
        } finally {
            release.countDown();
            second.dispose();
        }
    }

    /**
     * If the host reads the body on a thread of its own that the cancel does
     * not interrupt, its read ends all the same: the facade closes the
     * stream when the request ends.
     */
    @Test
    void cancellingClosesTheStreamedRequestBody() throws InterruptedException {
        int chunk = 64 * 1024;
        long size = AdapterHttpConnector.BUFFERED_BODY_LIMIT + 1L;
        CountDownLatch firstRead = new CountDownLatch(1);
        BlockingQueue<Object> readerOutcome = new LinkedBlockingQueue<>();
        http.handler = request -> {
            InputStream in = request.body().orElseThrow().open();
            Thread reader = new Thread(() -> {
                try {
                    in.readNBytes(chunk);
                    firstRead.countDown();
                    in.read();
                    readerOutcome.add("read returned");
                } catch (IOException closed) {
                    readerOutcome.add(closed);
                }
            }, "host-body-reader");
            reader.setDaemon(true);
            reader.start();
            reader.join();
            return http.text(200, "late");
        };

        Disposable subscription = client().put().uri("/attachment")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(size)
                .body(BodyInserters.fromDataBuffers(Flux.concat(Flux.just(buffer(new byte[chunk])), Flux.never())))
                .retrieve().toBodilessEntity().subscribe(ignored -> { }, ignored -> { });
        assertTrue(firstRead.await(5, TimeUnit.SECONDS));
        subscription.dispose();

        Object outcome = readerOutcome.poll(5, TimeUnit.SECONDS);
        assertInstanceOf(IOException.class, outcome, "the host's reader is released, not left waiting: " + outcome);
    }
}
