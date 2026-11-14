package unit_tests;

import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.Body;
import com.rmm.adapter.http.spring.AdapterHttpConnector;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bodies that travel through {@link AdapterHttpConnector}: they stream
 * in both directions; small request bodies are buffered and stay
 * repeatable; a declared length must match; a stalled or still unread
 * request body neither blocks the host nor is closed under it.
 */
class TestAdapterHttpConnectorBodies extends AdapterHttpConnectorFixture {

    // --- Streams ------------------------------------------------------------

    @Test
    void theResponseIsStreamed() throws InterruptedException {
        CountDownLatch rest = new CountDownLatch(1);
        InputStream slow = new InputStream() {
            private int phase;

            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] buffer, int offset, int count) throws IOException {
                if (phase == 0) {
                    phase = 1;
                    Arrays.fill(buffer, offset, offset + 100, (byte) 'a');
                    return 100;
                }
                if (phase == 1) {
                    phase = 2;
                    try {
                        rest.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        throw new IOException("interrupted", interrupted);
                    }
                    Arrays.fill(buffer, offset, offset + 50, (byte) 'b');
                    return 50;
                }
                return -1;
            }
        };
        http.handler = request -> http.response(200, Map.of(), http.tracked(slow), OptionalLong.empty());
        BlockingQueue<String> chunks = new LinkedBlockingQueue<>();

        Disposable subscription = client().get().uri("/download").retrieve().bodyToFlux(DataBuffer.class)
                .subscribe(buffer -> {
                    chunks.add(buffer.toString(StandardCharsets.US_ASCII));
                    DataBufferUtils.release(buffer);
                });
        try {
            String first = chunks.poll(5, TimeUnit.SECONDS);
            assertEquals("a".repeat(100), first, "the first piece arrives before the tool sent the rest");
            rest.countDown();
            assertEquals("b".repeat(50), chunks.poll(5, TimeUnit.SECONDS));
        } finally {
            rest.countDown();
            subscription.dispose();
        }
    }

    @Test
    void aLargeRequestBodyIsStreamed() {
        int chunk = 64 * 1024;
        int size = 128 * chunk;
        AtomicInteger produced = new AtomicInteger();
        AtomicInteger producedAtFirstRead = new AtomicInteger(-1);
        http.handler = request -> {
            Body body = request.body().orElseThrow();
            assertFalse(body.replayable());
            assertEquals(size, body.length());
            try (InputStream in = body.open()) {
                assertEquals(chunk, in.readNBytes(chunk).length);
                producedAtFirstRead.set(produced.get());
                assertEquals(size - chunk, in.transferTo(OutputStream.nullOutputStream()));
            }
            return http.response(201, Map.of(), InputStream.nullInputStream(), OptionalLong.of(0));
        };

        client().put().uri("/attachment")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(size)
                .body(BodyInserters.fromDataBuffers(chunks(size, chunk, produced)))
                .retrieve()
                .toBodilessEntity()
                .block(TIMEOUT);

        assertTrue(producedAtFirstRead.get() >= 1 && producedAtFirstRead.get() <= 3,
                "only a piece ahead is requested, not the whole body: " + producedAtFirstRead.get());
        assertEquals(128, produced.get());
        AdapterRequest sent = http.requests.get(0);
        assertFalse(sent.repeatable(), "a streamed upload is never repeated");
        assertEquals(Optional.of("application/octet-stream"), sent.body().orElseThrow().contentType());
    }

    @Test
    void smallBodiesAreBufferedAndStayRepeatable() throws IOException {
        http.handler = request -> http.text(200, "ok");

        client().put().uri("/rest/issue/1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"a\":1}").retrieve().toBodilessEntity().block(TIMEOUT);
        client().post().uri("/rest/search").contentType(MediaType.TEXT_PLAIN)
                .body(BodyInserters.fromDataBuffers(Flux.just(buffer("ab"), buffer("cd"))))
                .retrieve().toBodilessEntity().block(TIMEOUT);

        AdapterRequest put = http.requests.get(0);
        assertTrue(put.body().orElseThrow().replayable());
        assertTrue(put.repeatable(), "a buffered PUT stays repeatable");
        assertEquals(Optional.of("application/json"), put.body().orElseThrow().contentType());
        try (InputStream in = put.body().orElseThrow().open()) {
            assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
        AdapterRequest post = http.requests.get(1);
        try (InputStream in = post.body().orElseThrow().open()) {
            assertEquals("abcd", new String(in.readAllBytes(), StandardCharsets.UTF_8), "without Content-Length");
        }
        assertFalse(post.idempotent());
    }

    @Test
    void aLargeBodyWithoutLengthFailsWithAClearMessage() {
        http.handler = request -> http.text(200, "ok");
        int size = AdapterHttpConnector.BUFFERED_BODY_LIMIT + 1;

        WebClientRequestException failure = assertThrows(WebClientRequestException.class, () -> client().post()
                .uri("/upload").contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(BodyInserters.fromDataBuffers(chunks(size, 64 * 1024, new AtomicInteger())))
                .retrieve().toBodilessEntity().block(TIMEOUT));

        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("set Content-Length to stream it"),
                failure.getCause().getMessage());
        assertEquals(List.of(), http.requests);
    }

    @Test
    void multipartIsAlwaysAnUpload() throws IOException {
        http.handler = request -> http.text(200, "ok");
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("file", "hello");

        client().post().uri("/attachments").body(BodyInserters.fromMultipartData(parts.build()))
                .retrieve().toBodilessEntity().block(TIMEOUT);

        AdapterRequest sent = http.requests.get(0);
        Body body = sent.body().orElseThrow();
        assertTrue(body.contentType().orElseThrow().startsWith("multipart/form-data;"), body.contentType().get());
        assertFalse(body.replayable());
        assertFalse(sent.repeatable());
        try (InputStream in = body.open()) {
            assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("hello"));
        }
    }

    // --- Length and the reading thread of the host --------------------------------

    @Test
    void aWrongLengthFailsBeforeSending() {
        http.handler = request -> http.text(200, "ok");

        WebClientRequestException tooShort = assertThrows(WebClientRequestException.class, () -> client().post()
                .uri("/rest/x").contentType(MediaType.TEXT_PLAIN).contentLength(10)
                .body(BodyInserters.fromDataBuffers(Flux.just(buffer("abc"))))
                .retrieve().toBodilessEntity().block(TIMEOUT));
        WebClientRequestException tooLong = assertThrows(WebClientRequestException.class, () -> client().post()
                .uri("/rest/x").contentType(MediaType.TEXT_PLAIN).contentLength(2)
                .body(BodyInserters.fromDataBuffers(Flux.just(buffer("abc"))))
                .retrieve().toBodilessEntity().block(TIMEOUT));

        assertInstanceOf(IllegalArgumentException.class, tooShort.getCause());
        assertTrue(tooShort.getCause().getMessage().contains("has 3 bytes, but Content-Length declares 10"),
                tooShort.getCause().getMessage());
        assertInstanceOf(IllegalArgumentException.class, tooLong.getCause());
        assertTrue(tooLong.getCause().getMessage().contains("Content-Length declares 2"),
                tooLong.getCause().getMessage());
        assertEquals(List.of(), http.requests);
    }

    /** A stalled body does not leave the reading thread of the host hanging. */
    @Test
    void aStalledRequestBodyDoesNotLeaveTheHostHanging() throws InterruptedException {
        int chunk = 64 * 1024;
        long size = AdapterHttpConnector.BUFFERED_BODY_LIMIT + 1L;
        BlockingQueue<Object> outcome = new LinkedBlockingQueue<>();
        http.handler = request -> {
            try (InputStream in = request.body().orElseThrow().open()) {
                assertEquals(chunk, in.readNBytes(chunk).length);
                long start = System.nanoTime();
                try {
                    in.read();
                    outcome.add("read returned");
                } catch (IOException stalled) {
                    outcome.add(Duration.ofNanos(System.nanoTime() - start));
                }
            }
            return http.response(400, Map.of(), InputStream.nullInputStream(), OptionalLong.of(0));
        };

        client().put().uri("/attachment").contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(size)
                .attribute(AdapterHttpConnector.TIMEOUT, Duration.ofMillis(300))
                .body(BodyInserters.fromDataBuffers(Flux.concat(Flux.just(buffer(new byte[chunk])), Flux.never())))
                .retrieve().toBodilessEntity().onErrorResume(failure -> Mono.empty()).block(TIMEOUT);

        Object waited = outcome.poll(5, TimeUnit.SECONDS);
        Duration elapsed = assertInstanceOf(Duration.class, waited, String.valueOf(waited));
        assertTrue(elapsed.toMillis() >= 250 && elapsed.toMillis() < 5000, elapsed.toString());
    }

    /**
     * The response can be there before the host has read the end of the
     * streamed body: the tool answers after the last byte, and the reading
     * thread of the host fetches the end of the stream only afterwards. The
     * facade therefore does not close the stream with the response; the host
     * reads it to its end and closes it itself. If the facade closed it, the
     * body would fail at the host, and under load Reactor Netty would
     * discard the response it has already received.
     */
    @Test
    void theResponseDoesNotCloseTheStreamedRequestBody() throws Exception {
        int chunk = 64 * 1024;
        int size = AdapterHttpConnector.BUFFERED_BODY_LIMIT + 1;
        CountDownLatch firstRead = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        BlockingQueue<Object> readerOutcome = new LinkedBlockingQueue<>();
        http.handler = request -> {
            InputStream in = request.body().orElseThrow().open();
            Thread reader = new Thread(() -> {
                try (in) {
                    long read = in.readNBytes(chunk).length;
                    firstRead.countDown();
                    //Only after Spring has the response does the host read the rest to the end
                    assertTrue(answered.await(5, TimeUnit.SECONDS));
                    read += in.readAllBytes().length;
                    readerOutcome.add(read);
                } catch (IOException | InterruptedException | AssertionError failed) {
                    readerOutcome.add(failed);
                }
            }, "host-body-reader");
            reader.setDaemon(true);
            reader.start();
            assertTrue(firstRead.await(5, TimeUnit.SECONDS));
            return http.text(200, "ok");
        };

        ResponseEntity<String> answer = client().put().uri("/attachment")
                .contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(size)
                .body(BodyInserters.fromDataBuffers(Flux.just(buffer(new byte[chunk]),
                        buffer(new byte[size - chunk]))))
                .retrieve().toEntity(String.class).block(TIMEOUT);
        answered.countDown();

        assertNotNull(answer);
        assertEquals("ok", answer.getBody());
        Object outcome = readerOutcome.poll(5, TimeUnit.SECONDS);
        assertEquals((long) size, outcome, "the host reads the body to its end after the answer: " + outcome);
    }
}
