package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.Auth;
import com.rmm.adapter.api.Body;
import com.rmm.adapter.api.RedirectPolicy;
import com.rmm.adapter.api.ResponseTooLargeException;
import com.rmm.adapter.api.SecretValue;
import com.rmm.adapter.api.StreamResponse;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.rmm.adapter.tck.FakeToolHandlers.acceptingOnly;
import static com.rmm.adapter.tck.FakeToolHandlers.announceWithoutSending;
import static com.rmm.adapter.tck.FakeToolHandlers.failingFirst;
import static com.rmm.adapter.tck.FakeToolHandlers.gzipped;
import static com.rmm.adapter.tck.FakeToolHandlers.pause;
import static com.rmm.adapter.tck.FakeToolHandlers.redirect;
import static com.rmm.adapter.tck.FakeToolHandlers.respondSettingCookies;
import static com.rmm.adapter.tck.FakeToolHandlers.stall;
import static com.rmm.adapter.tck.FakeToolHandlers.trickle;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The semantics of {@link AdapterHttp} as an adapter experiences them, as an abstract test class that runs against
 * the TCK's HTTP path ({@link TckAdapterHttp}) AND against the host's. The two therefore cannot drift apart: what
 * an adapter passes in the TCK holds in the host as well.
 *
 * The checks cover what the contract and the host define:
 * <ul>
 *   <li>every status comes back as a response, including 3xx, 4xx and 5xx;</li>
 *   <li>deadlines: for {@code send} of an ordinary request the timeout covers the whole exchange; for {@code open}
 *       it runs until the headers and then acts as idle timeout between two chunks; a timeout of the request
 *       itself is transient. By contract ({@code AdapterRequest}) an upload through {@code send} has it as idle
 *       timeout. These shared checks do not cover that, because the TCK's HTTP path gives an upload the stream
 *       limit as ONE deadline until the complete response (a limitation of the TCK, see {@link TckAdapterHttp}).
 *       The checks run with ONE deadline for every request ({@link Limits}); a separate test per HTTP path
 *       covers the stream limit;</li>
 *   <li>the run's deadline shortens the request, running into it is final, and with nothing left nothing is
 *       sent; waiting for a chunk of a response stream ends at it too;</li>
 *   <li>the maximum size applies while reading, in decompressed bytes; a response that announces too large a size
 *       fails before the first byte;</li>
 *   <li>a request with a body follows no redirect; without permission none is followed;</li>
 *   <li>a streamed body is sent exactly once and with its length; closing a response stream aborts the
 *       transfer;</li>
 *   <li>every attempt counts; {@code currentRun()} names the run of the thread, and a run that is not active is a
 *       programming error;</li>
 *   <li>the sign-in goes only to its origin, an {@code Authorization} that the request carries itself takes
 *       precedence, and the HTTP path's {@code toString()} does not show it;</li>
 *   <li>a released HTTP path no longer sends;</li>
 *   <li>redirects: as far as the policy allows, {@code Location} per RFC 3986 without fragment; once the chain has
 *       left the origin, no sensitive headers, no sign-in (not even that of the new origin) and no cookies are
 *       sent anymore, even on a hop back, and nothing is stored;</li>
 *   <li>OAuth: fetching through a view without sign-in and without cookies, one renewal per request across all
 *       hops, a second send only for {@code repeatable()}, a rejected token is dropped, an {@code Authorization}
 *       of the request itself is never renewed, renewal before expiry (at most half the lifetime ahead), a
 *       provider that goes through {@code context.http()} fails instead of waiting, and provider failures keep
 *       their kind (transient or final, HTTP status);</li>
 *   <li>cookies: per origin, per RFC 6265 (path, default path, {@code Secure}, deletion also with a negative
 *       {@code Max-Age}), sent as {@code name=value}, the length of the header line bounded;</li>
 *   <li>retries: only a repeatable request, after 429 or 503 at most three times, after {@code Retry-After} (at
 *       most 60 s) or 2, 4, 8 s, never beyond the request's deadline; a create and an upload never, not even
 *       after 429; every attempt counts. The waits are checked against what the HTTP path plans
 *       ({@link Subject#retryWaits()}): during the checks the waiting is shortened (TCK) or runs on a fake clock
 *       (host).</li>
 * </ul>
 * Only the host has the rate limit of the binding and the cap per target host; they change neither the responses
 * nor the number of attempts.
 *
 * <p>An implementation supplies the HTTP path of a binding ({@link #subject}) and, if it cannot reach
 * {@code 127.0.0.1} (the host's address check blocks loopback addresses), a reachable address
 * ({@link #reachable}). The tools are {@link FakeHttpTool} instances that do not check the binding marker.</p>
 */
public abstract class AdapterHttpSemantics {

    /**
     * The limits of the HTTP path for a check.
     *
     * @param responseTimeout  longest deadline of a request, including a stream (the checks give the HTTP path
     *                         ONE deadline for every request)
     * @param maxResponseBytes maximum size of a buffered response
     */
    protected record Limits(Duration responseTimeout, int maxResponseBytes) {
    }

    /** Work in an active run. */
    @FunctionalInterface
    protected interface RunWork<T> {
        T run(SyncRunContext run) throws Exception;
    }

    /** The HTTP path of a binding under test. */
    protected interface Subject extends AutoCloseable {

        /** The HTTP path as the adapter gets it from {@code context.http()}. */
        AdapterHttp http();

        /**
         * Runs {@code work} in an active run of this binding to which the calling thread is bound and which has
         * {@code remaining} time left ({@link Duration#ZERO}: none).
         */
        <T> T inRun(Duration remaining, RunWork<T> work) throws Exception;

        /** The attempts counted for this binding so far. */
        long attempts();

        /** The waits before retries of this binding so far, as the HTTP path plans them; empty if it never retries. */
        List<Duration> retryWaits();

        /** Releases the HTTP path, as after the last lease of the binding is returned. */
        @Override
        void close();
    }

    /** The limits for checks that need none of their own. */
    protected static final Limits DEFAULT_LIMITS = new Limits(Duration.ofSeconds(10), 64 * 1024);
    /** Limits for the checks of the maximum size: a buffered response of at most 1000 bytes. */
    private static final Limits SMALL_LIMITS = new Limits(Duration.ofSeconds(10), 1000);
    /** Limits for the tools on a raw socket: long enough for a cold start, shorter than the default. */
    private static final Limits RAW_LIMITS = new Limits(Duration.ofSeconds(8), 64 * 1024);

    private final List<AutoCloseable> opened = new ArrayList<>();

    /** The HTTP path of a new binding with these limits. */
    protected abstract Subject subject(Limits limits) throws Exception;

    /**
     * Whether the HTTP path under test retries as the host does. Only the host's bare transport (without the rest
     * of its request chain) does not; the retry checks are skipped then.
     */
    protected boolean repeats() {
        return true;
    }

    /** The address at which the HTTP path reaches a local tool; default: unchanged. */
    protected URI reachable(URI local) {
        return local;
    }

    @AfterEach
    public void closeSubjectsAndTools() throws Exception {
        for (AutoCloseable closeable : opened) {
            closeable.close();
        }
        opened.clear();
    }

    // ------------------------------------------------------------------
    // Responses
    // ------------------------------------------------------------------

    @Test
    public void everyStatusComesBackAsAResponse() throws Exception {
        FakeHttpTool tool = tool()
                .route("/missing", exchange -> FakeHttpTool.respond(exchange, 404, "text/plain", bytes("gone")))
                .route("/busy", exchange -> FakeHttpTool.respond(exchange, 503, "text/plain", bytes("later")));
        Subject subject = subject(DEFAULT_LIMITS, true);

        AdapterResponse missing = subject.http().send(AdapterRequest.get(address(tool, "/missing")));
        AdapterResponse busy = subject.http().send(AdapterRequest.get(address(tool, "/busy")));

        assertEquals(404, missing.status());
        assertEquals("gone", missing.bodyAsString());
        assertEquals(503, busy.status());
    }

    // ------------------------------------------------------------------
    // Deadlines
    // ------------------------------------------------------------------

    @Test
    public void theTimeoutOfSendCoversTheWholeExchange() throws Exception {
        FakeHttpTool tool = tool().route("/trickle", exchange -> trickle(exchange, 30, Duration.ofMillis(100)));
        Subject subject = subject(DEFAULT_LIMITS, true);
        AdapterRequest request = getWithin(Duration.ofMillis(500), address(tool, "/trickle"));

        long started = System.nanoTime();
        AdapterException failed = assertThrows(AdapterException.class, () -> subject.http().send(request));

        assertTrue(failed.isRetryable(), "an own timeout is transient: " + failed.getMessage());
        assertTrue(elapsed(started).compareTo(Duration.ofMillis(2500)) < 0,
                "the body trickled for 3 s, the request had 500 ms: " + elapsed(started));
    }

    @Test
    public void openWaitsForTheHeadersThenAppliesTheTimeoutBetweenReads() throws Exception {
        FakeHttpTool tool = tool()
                .route("/steady", exchange -> trickle(exchange, 6, Duration.ofMillis(150)))
                .route("/stalling", exchange -> stall(exchange, Duration.ofMillis(1500)));
        Subject subject = subject(DEFAULT_LIMITS, true);
        Duration timeout = Duration.ofMillis(400);

        try (StreamResponse steady = subject.http().open(getWithin(timeout, address(tool, "/steady")))) {
            assertEquals(6, steady.body().readAllBytes().length,
                    "900 ms in all, never more than 150 ms between two pieces: the timeout is no total cap");
        }
        try (StreamResponse stalling = subject.http().open(getWithin(timeout, address(tool, "/stalling")))) {
            InputStream body = stalling.body();
            assertEquals('a', body.read());
            long started = System.nanoTime();
            assertThrows(IOException.class, body::read, "1.5 s without data is longer than the idle timeout");
            assertTrue(elapsed(started).compareTo(Duration.ofMillis(1300)) < 0, "ended at the idle timeout: "
                    + elapsed(started));
        }
    }

    @Test
    public void headersThatComeTooLateAreATransientFailure() throws Exception {
        FakeHttpTool tool = tool().route("/late", exchange -> {
            pause(Duration.ofMillis(1500));
            FakeHttpTool.respond(exchange, 200, "text/plain", bytes("late"));
        });
        Subject subject = subject(DEFAULT_LIMITS, true);

        long started = System.nanoTime();
        AdapterException failed = assertThrows(AdapterException.class,
                () -> subject.http().open(getWithin(Duration.ofMillis(300), address(tool, "/late"))));

        assertTrue(failed.isRetryable(), failed.getMessage());
        assertTrue(elapsed(started).compareTo(Duration.ofMillis(1200)) < 0, "at the timeout: " + elapsed(started));
    }

    @Test
    public void theDeadlineOfTheRunShortensTheRequestAndEndsItForGood() throws Exception {
        FakeHttpTool tool = tool().route("/slow", exchange -> {
            pause(Duration.ofSeconds(3));
            FakeHttpTool.respond(exchange, 200, "text/plain", bytes("slow"));
        });
        Subject subject = subject(DEFAULT_LIMITS, true);
        AdapterRequest request = getWithin(Duration.ofSeconds(10), address(tool, "/slow"));

        long started = System.nanoTime();
        AdapterException failed = subject.inRun(Duration.ofMillis(400),
                run -> assertThrows(AdapterException.class, () -> subject.http().send(request)));

        assertFalse(failed.isRetryable(), "at the deadline of the run nothing is retried: " + failed.getMessage());
        assertTrue(elapsed(started).compareTo(Duration.ofSeconds(2)) < 0, "the run had 400 ms: " + elapsed(started));
    }

    @Test
    public void theWaitForAPieceOfAStreamEndsAtTheDeadlineOfTheRun() throws Exception {
        FakeHttpTool tool = tool().route("/stalling", exchange -> stall(exchange, Duration.ofSeconds(3)));
        Subject subject = subject(DEFAULT_LIMITS, true);

        long started = System.nanoTime();
        subject.inRun(Duration.ofMillis(400), run -> {
            try (StreamResponse stream = subject.http().open(
                    getWithin(Duration.ofSeconds(10), address(tool, "/stalling")))) {
                InputStream body = stream.body();
                assertEquals('a', body.read());
                return assertThrows(IOException.class, body::read, "the run ends before the next piece");
            }
        });

        assertTrue(elapsed(started).compareTo(Duration.ofSeconds(2)) < 0, "the run had 400 ms, the idle timeout"
                + " 10 s: " + elapsed(started));
    }

    @Test
    public void aRunWithoutTimeLeftSendsNothing() throws Exception {
        FakeHttpTool tool = tool().text("/rows", "text/plain", "row");
        Subject subject = subject(DEFAULT_LIMITS, true);
        long before = subject.attempts();

        AdapterException failed = subject.inRun(Duration.ZERO, run -> assertThrows(AdapterException.class,
                () -> subject.http().send(AdapterRequest.get(address(tool, "/rows")))));

        assertFalse(failed.isRetryable(), failed.getMessage());
        assertEquals(List.of(), tool.requests(), "nothing reached the tool");
        assertEquals(before, subject.attempts(), "no attempt");
    }

    // ------------------------------------------------------------------
    // Maximum size and compression
    // ------------------------------------------------------------------

    @Test
    public void theBufferedLimitHoldsWhileReading() throws Exception {
        FakeHttpTool tool = tool()
                .route("/exact", exchange -> FakeHttpTool.respond(exchange, 200, "text/plain", new byte[1000]))
                .route("/over", exchange -> FakeHttpTool.respond(exchange, 200, "text/plain", new byte[1001]))
                .route("/endless", exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream out = exchange.getResponseBody()) {
                        for (int chunk = 0; chunk < 100_000; chunk++) {
                            out.write(new byte[64]);
                            out.flush();
                        }
                    }
                });
        Subject subject = subject(SMALL_LIMITS, true);

        assertEquals(1000, subject.http().send(AdapterRequest.get(address(tool, "/exact"))).body().length);
        AdapterException over = assertThrows(AdapterException.class,
                () -> subject.http().send(AdapterRequest.get(address(tool, "/over"))));
        assertFalse(over.isRetryable(), over.getMessage());
        assertTrue(over.getMessage().contains("larger than the limit"), over.getMessage());
        assertInstanceOf(ResponseTooLargeException.class, over, "the reason as a type");
        long started = System.nanoTime();
        AdapterException endless = assertThrows(AdapterException.class,
                () -> subject.http().send(AdapterRequest.get(address(tool, "/endless"))));
        assertTrue(endless.getMessage().contains("larger than the limit"), "the limit, not the timeout, ends an"
                + " endless body: " + endless.getMessage());
        assertInstanceOf(ResponseTooLargeException.class, endless);
        assertTrue(elapsed(started).compareTo(Duration.ofSeconds(5)) < 0, elapsed(started).toString());
    }

    @Test
    public void anAnnouncedOversizedResponseFailsBeforeItsFirstByte() throws Exception {
        FakeHttpTool tool = tool().route("/announced",
                exchange -> announceWithoutSending(exchange, 1_000_000, Duration.ofSeconds(3)));
        Subject subject = subject(SMALL_LIMITS, true);

        long started = System.nanoTime();
        AdapterException failed = assertThrows(AdapterException.class,
                () -> subject.http().send(AdapterRequest.get(address(tool, "/announced"))));

        assertTrue(failed.getMessage().contains("larger than the limit"), failed.getMessage());
        assertInstanceOf(ResponseTooLargeException.class, failed);
        assertTrue(elapsed(started).compareTo(Duration.ofMillis(2000)) < 0, "before the body: " + elapsed(started));
    }

    /**
     * HEAD has no body: its {@code Content-Length} states the size of the content, for example of a large
     * attachment, and the maximum size of a buffered response does not apply to it; for GET it applies.
     */
    @Test
    public void aHeadWhoseLengthExceedsTheLimitIsAnsweredWithoutABody() throws Exception {
        FakeHttpTool tool = tool().route("/large", exchange -> {
            exchange.getResponseHeaders().set("Content-Length", "1000000");
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            announceWithoutSending(exchange, 1_000_000, Duration.ofSeconds(3));
        });
        Subject subject = subject(SMALL_LIMITS, true);

        AdapterResponse head = subject.http().send(AdapterRequest.head(address(tool, "/large")));
        AdapterException get = assertThrows(AdapterException.class,
                () -> subject.http().send(AdapterRequest.get(address(tool, "/large"))));

        assertEquals(200, head.status());
        assertEquals("1000000", head.header("Content-Length").orElseThrow(), "the size of the content");
        assertEquals(0, head.body().length);
        assertTrue(get.getMessage().contains("larger than the limit"), "for GET the limit holds: " + get.getMessage());
        assertInstanceOf(ResponseTooLargeException.class, get);
    }

    @Test
    public void gzipIsRequestedUnpackedAndCountedUnpacked() throws Exception {
        FakeHttpTool tool = tool()
                .route("/small", exchange -> gzipped(exchange, 900))
                .route("/big", exchange -> gzipped(exchange, 5000));
        Subject subject = subject(SMALL_LIMITS, true);

        AdapterResponse small = subject.http().send(AdapterRequest.get(address(tool, "/small")));
        assertEquals(200, small.status(), "the tool answers 400 without Accept-Encoding: gzip");
        assertArrayEquals("a".repeat(900).getBytes(StandardCharsets.US_ASCII), small.body());
        assertTrue(small.header("Content-Encoding").isEmpty(), "the adapter gets the body unpacked");
        AdapterException big = assertThrows(AdapterException.class,
                () -> subject.http().send(AdapterRequest.get(address(tool, "/big"))));
        assertTrue(big.getMessage().contains("larger than the limit"), "5000 bytes unpacked, a few packed: "
                + big.getMessage());
        assertInstanceOf(ResponseTooLargeException.class, big);
    }

    // ------------------------------------------------------------------
    // Redirects and bodies
    // ------------------------------------------------------------------

    @Test
    public void aRequestWithABodyFollowsNoRedirectAndWithoutPermissionNoneIsFollowed() throws Exception {
        FakeHttpTool tool = tool().text("/target", "text/plain", "target");
        tool.route("/moved", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            FakeHttpTool.respond(exchange, 307, null, new byte[0]);
        });
        Subject subject = subject(DEFAULT_LIMITS, true);

        AdapterResponse upload = subject.http().send(AdapterRequest.builder("POST", address(tool, "/moved"))
                .body(Body.ofJson("{\"a\":1}")).redirects(RedirectPolicy.follow(3)).build());
        AdapterResponse plain = subject.http().send(AdapterRequest.get(address(tool, "/moved")));

        assertEquals(307, upload.status(), "a request with a body never follows a redirect");
        assertEquals(307, plain.status(), "redirects are followed only when the request allows it");
        assertTrue(tool.requests().stream().noneMatch(request -> request.path().equals("/target")),
                tool.requests().toString());
    }

    @Test
    public void aStreamedBodyIsSentOnceWithItsLength() throws Exception {
        FakeHttpTool tool = tool().route("/upload", exchange -> FakeHttpTool.respond(exchange, 201, null,
                new byte[0]));
        Subject subject = subject(DEFAULT_LIMITS, true);
        AtomicInteger opened = new AtomicInteger();
        Body content = Body.ofStream(5, "application/octet-stream", () -> {
            opened.incrementAndGet();
            return new ByteArrayInputStream(bytes("12345"));
        });

        AdapterResponse response = subject.http().send(AdapterRequest.put(address(tool, "/upload"), content));

        assertEquals(201, response.status());
        assertEquals(1, opened.get(), "a streamed body is opened once");
        FakeHttpTool.Request received = tool.requests().get(0);
        assertArrayEquals(bytes("12345"), received.body());
        assertEquals("5", received.header("Content-Length"));
    }

    // ------------------------------------------------------------------
    // Early and aborted responses
    // ------------------------------------------------------------------

    /** A tool on a raw socket for exactly one connection: the JDK's HTTP server hides aborted connections. */
    @FunctionalInterface
    protected interface RawTool {
        void serve(Socket socket, String head) throws Exception;
    }

    /** Starts a {@link RawTool}; its address is {@code http://127.0.0.1:<port>}. */
    protected static ServerSocket rawTool(RawTool tool) throws IOException {
        return RawTools.start(tool);
    }

    /** The address of a {@link RawTool} as the HTTP path reaches a local tool ({@link #reachable}). */
    private URI rawAddress(ServerSocket server, String path) {
        return reachable(URI.create("http://127.0.0.1:" + server.getLocalPort() + path));
    }

    /** A non-repeatable request (POST): what the check sees is its first failure, not a timeout after retries. */
    private AdapterRequest post(ServerSocket server, String path) {
        return AdapterRequest.builder("POST", rawAddress(server, path)).body(Body.ofString("x", "text/plain"))
                .build();
    }

    /**
     * If the connection ends before the announced length, the response is broken off: it fails instead of
     * counting as a shorter body.
     */
    @Test
    public void aResponseThatEndsBeforeItsLengthFails() throws Exception {
        try (ServerSocket server = rawTool(RawTools.endingBeforeItsLength())) {
            Subject subject = subject(RAW_LIMITS, true);
            long before = subject.attempts();
            long started = System.nanoTime();
            assertThrows(AdapterException.class, () -> subject.http().send(post(server, "/short")),
                    "a broken-off response is a failure, not \"abc\"");
            assertEquals(before + 1, subject.attempts(), "one attempt, the broken-off one");
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(4).toNanos(),
                    "the response broke off; it did not run into the timeout");
        }
    }

    /** The same for a stream: reading fails at the end instead of delivering a shorter body. */
    @Test
    public void aStreamThatEndsBeforeItsLengthFailsOnReading() throws Exception {
        try (ServerSocket server = rawTool(RawTools.endingBeforeItsLength())) {
            Subject subject = subject(RAW_LIMITS, true);
            try (StreamResponse response = subject.http().open(post(server, "/short"))) {
                assertThrows(IOException.class, () -> response.body().readAllBytes(),
                        "reading a broken-off stream fails instead of ending after \"abc\"");
            } catch (AdapterException brokenAtOpen) {
                //Just as good: the abort already arrived with the headers
            }
        }
    }

    /**
     * The response headers have arrived, perhaps part of the body too, and then the source of the upload fails:
     * the request fails instead of counting as an (empty or short) response, however the response frames its
     * length. An HTTP client that ends such a response normally, with whatever has arrived so far, must not pass
     * it on to the adapter.
     */
    @Test
    public void anUploadWhoseSourceFailsAfterTheAnswerBeganFailsWithALength() throws Exception {
        uploadFailsAfterTheAnswerBegan("Content-Length: 10\r\n", "abc", false);
    }

    @Test
    public void anUploadWhoseSourceFailsAfterTheAnswerBeganFailsWhenChunked() throws Exception {
        uploadFailsAfterTheAnswerBegan("Transfer-Encoding: chunked\r\n", "3\r\nabc\r\n", false);
    }

    @Test
    public void anUploadWhoseSourceFailsAfterTheAnswerBeganFailsUntilClose() throws Exception {
        uploadFailsAfterTheAnswerBegan("Connection: close\r\n", "abc", false);
    }

    @Test
    public void anUploadWhoseSourceFailsAfterTheAnswerBeganFailsAsAStream() throws Exception {
        uploadFailsAfterTheAnswerBegan("Content-Length: 10\r\n", "abc", true);
    }

    @Test
    public void anUploadWhoseSourceFailsAfterTheAnswerBeganFailsAsAChunkedStream() throws Exception {
        uploadFailsAfterTheAnswerBegan("Transfer-Encoding: chunked\r\n", "3\r\nabc\r\n", true);
    }

    /**
     * The tool reads part of the upload, sends the headers and {@code partial} and waits; the source delivers
     * 256 KiB and then fails after the response began: as a stream reliably, once {@code open()} has returned; for
     * {@code send} (which waits until the end) after a moment for the headers. If the HTTP path does not get to
     * the headers within that moment, the check covers the failure before the headers; it never fails spuriously.
     */
    private void uploadFailsAfterTheAnswerBegan(String framing, String partial, boolean stream) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch answered = new CountDownLatch(1);
        //As a stream the source waits until open() has returned with the headers: then it reliably fails afterwards
        CountDownLatch headsSeen = new CountDownLatch(stream ? 1 : 0);
        try (ServerSocket server = rawTool(RawTools.answeringAnUploadPartly(framing, partial, answered, done))) {
            Subject subject = subject(RAW_LIMITS, true);
            Body content = Body.ofStream(8L << 20, "application/octet-stream",
                    () -> sourceFailingAfterTheAnswer(answered, headsSeen, stream));
            AdapterRequest upload = AdapterRequest.put(rawAddress(server, "/upload"), content);

            try {
                if (stream) {
                    try (StreamResponse response = subject.http().open(upload)) {
                        headsSeen.countDown();
                        byte[] read = response.body().readAllBytes();
                        fail("an upload whose source failed is no answer: " + response.status() + " with "
                                + read.length + " bytes");
                    }
                } else {
                    AdapterResponse response = subject.http().send(upload);
                    fail("an upload whose source failed is no answer: " + response.status() + " with "
                            + response.body().length + " bytes");
                }
            } catch (AdapterException | IOException failed) {
                //As intended: while sending, opening or reading
            } finally {
                done.countDown();
            }
        }
    }

    /** If even opening the source fails, that is final, like a failure while reading. */
    @Test
    public void aSourceThatCannotBeOpenedFailsPermanently() throws Exception {
        FakeHttpTool tool = tool().route("/upload", exchange -> FakeHttpTool.respond(exchange, 201, null,
                new byte[0]));
        Subject subject = subject(DEFAULT_LIMITS, true);
        Body content = Body.ofStream(5, "application/octet-stream", () -> {
            throw new IOException("the file is gone");
        });

        AdapterException failed = assertThrows(AdapterException.class,
                () -> subject.http().send(AdapterRequest.put(address(tool, "/upload"), content)));

        assertFalse(failed.isRetryable(), "a source that cannot be opened stays unreadable: " + failed.getMessage());
    }

    /**
     * If the tool answers before it has read an upload (for example with 413) and then stops reading, the HTTP
     * path closes the source of the streamed body, whether the response arrives or the request fails: otherwise
     * the adapter's source (a file, the stream of the Spring facade) would stay open.
     */
    @Test
    public void anUploadTheToolAnswersBeforeReadingClosesItsSource() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        try (ServerSocket server = rawTool(RawTools.refusingAnUploadEarly(done))) {
            Subject subject = subject(RAW_LIMITS, true);
            CountDownLatch closed = new CountDownLatch(1);
            long length = 64L << 20;
            Body content = Body.ofStream(length, "application/octet-stream",
                    () -> sourceReportingItsClose(length, closed));

            try {
                subject.http().send(AdapterRequest.put(rawAddress(server, "/early"), content));
            } catch (AdapterException brokenOff) {
                //Just as good: the request failed while the body was still flowing (in the TCK's HTTP path only
                //after its deadline: the JDK client delivers the response only after the whole body)
            }

            try {
                assertTrue(closed.await(10, TimeUnit.SECONDS), "the source of the upload is closed");
            } finally {
                done.countDown();
            }
        }
    }

    @Test
    public void closingAResponseStreamEarlyAbortsTheTransfer() throws Exception {
        CountDownLatch aborted = new CountDownLatch(1);
        FakeHttpTool tool = tool().route("/large", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int chunk = 0; chunk < 4096; chunk++) {
                    out.write(new byte[64 * 1024]);
                    out.flush();
                }
            } catch (IOException closedByTheClient) {
                aborted.countDown();
                throw closedByTheClient;
            }
        });
        Subject subject = subject(DEFAULT_LIMITS, true);

        try (StreamResponse response = subject.http().open(AdapterRequest.get(address(tool, "/large")))) {
            assertTrue(response.body().readNBytes(1024).length == 1024);
        }

        assertTrue(aborted.await(10, TimeUnit.SECONDS), "the tool sees the transfer of 256 MiB aborted");
    }

    // ------------------------------------------------------------------
    // Run, counting, sign-in, release
    // ------------------------------------------------------------------

    @Test
    public void everyAttemptIsCounted() throws Exception {
        FakeHttpTool tool = tool().text("/rows", "text/plain", "row");
        Subject subject = subject(DEFAULT_LIMITS, true);
        long before = subject.attempts();

        for (int request = 0; request < 3; request++) {
            subject.http().send(AdapterRequest.get(address(tool, "/rows")));
        }

        assertEquals(before + 3, subject.attempts());
        assertEquals(3, tool.requests().size());
    }

    @Test
    public void currentRunNamesTheRunOfTheThreadAndANamedRunMustBeActive() throws Exception {
        FakeHttpTool tool = tool().text("/rows", "text/plain", "row");
        Subject subject = subject(DEFAULT_LIMITS, true);

        assertTrue(subject.http().currentRun().isEmpty(), "outside a run");
        UUID seen = subject.inRun(Duration.ofMinutes(5),
                run -> subject.http().currentRun().orElseThrow().runId().equals(run.runId()) ? run.runId() : null);
        assertTrue(seen != null, "inside the run it names that run");
        SyncRunContext invented = new SyncRunContext(UUID.randomUUID(), "PRJ");
        assertThrows(IllegalStateException.class, () -> subject.http().send(AdapterRequest.builder("GET",
                address(tool, "/rows")).run(invented).build()), "a run that is not active is a programming error");
        assertEquals(List.of(), tool.requests());
    }

    @Test
    public void aSignInGoesOnlyToItsOriginAndTheNetworkPathShowsItNowhere() throws Exception {
        FakeHttpTool first = tool().text("/me", "text/plain", "me");
        FakeHttpTool second = tool().text("/me", "text/plain", "me");
        Subject subject = subject(DEFAULT_LIMITS, true);
        String secret = "s3cr3t-" + UUID.randomUUID();
        URI firstAddress = address(first, "/me");
        subject.http().auth(originOf(firstAddress), Auth.basic("tck", SecretValue.of(secret)));

        subject.http().send(AdapterRequest.get(firstAddress));
        subject.http().send(AdapterRequest.get(address(second, "/me")));

        assertTrue(first.requests().get(0).header("Authorization").startsWith("Basic "));
        assertNull(second.requests().get(0).header("Authorization"), "another origin gets no sign-in");
        assertFalse(subject.http().toString().contains(secret), subject.http().toString());
        assertFalse(subject.http().toString().contains("Basic"), subject.http().toString());
    }

    /**
     * Basic in the charset of the sign-in: with {@code Auth.basic(..., ISO_8859_1)} the bytes of
     * {@code account:password} are encoded in ISO-8859-1 before Base64 (a tool such as Jama expects that),
     * without a charset in UTF-8 (RFC 7617). For a password with umlauts the two differ.
     */
    @Test
    public void basicGoesInTheCharsetOfItsSignIn() throws Exception {
        FakeHttpTool latin = tool().text("/me", "text/plain", "me");
        FakeHttpTool unicode = tool().text("/me", "text/plain", "me");
        Subject subject = subject(DEFAULT_LIMITS, true);
        String password = "Grüße-" + UUID.randomUUID();
        subject.http().auth(origin(latin), Auth.basic("jürgen", SecretValue.of(password),
                StandardCharsets.ISO_8859_1));
        subject.http().auth(origin(unicode), Auth.basic("jürgen", SecretValue.of(password)));

        subject.http().send(AdapterRequest.get(address(latin, "/me")));
        subject.http().send(AdapterRequest.get(address(unicode, "/me")));

        String pair = "jürgen:" + password;
        String iso = "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.ISO_8859_1));
        String utf8 = "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
        assertNotEquals(iso, utf8);
        assertEquals(iso, latin.requests().get(0).header("Authorization"), "ISO-8859-1, as signed in");
        assertEquals(utf8, unicode.requests().get(0).header("Authorization"), "without a charset UTF-8");
    }

    /** As in the host: the sign-in is added only if the request itself carries no {@code Authorization}. */
    @Test
    public void anAuthorizationTheRequestCarriesWinsOverTheRegisteredSignIn() throws Exception {
        FakeHttpTool tool = tool().text("/me", "text/plain", "me");
        Subject subject = subject(DEFAULT_LIMITS, true);
        URI address = address(tool, "/me");
        subject.http().auth(originOf(address), Auth.bearer(SecretValue.of("registered-" + UUID.randomUUID())));

        subject.http().send(AdapterRequest.builder("GET", address)
                .secretHeader("Authorization", SecretValue.of("Bearer own-of-the-request")).build());
        subject.http().send(AdapterRequest.get(address));

        assertEquals(List.of("Bearer own-of-the-request"), tool.requests().get(0).headers().get("Authorization"),
                "the request's own sign-in, not both and not the registered one");
        assertTrue(tool.requests().get(1).header("Authorization").startsWith("Bearer registered-"));
    }

    @Test
    public void aReleasedNetworkPathSendsNothing() throws Exception {
        FakeHttpTool tool = tool().text("/rows", "text/plain", "row");
        Subject subject = subject(DEFAULT_LIMITS, false);
        AdapterHttp http = subject.http();

        subject.close();

        assertThrows(IllegalStateException.class, () -> http.send(AdapterRequest.get(address(tool, "/rows"))));
        assertEquals(List.of(), tool.requests());
    }

    // ------------------------------------------------------------------
    // Redirects
    // ------------------------------------------------------------------

    @Test
    public void aRedirectIsFollowedOnlyAsFarAsTheRequestAllows() throws Exception {
        FakeHttpTool tool = tool().text("/target", "text/plain", "target");
        tool.route("/moved", exchange -> redirect(exchange, 302, "/target"));
        tool.route("/twice", exchange -> redirect(exchange, 301, "/moved"));
        Subject subject = subject(DEFAULT_LIMITS, true);

        AdapterResponse followed = subject.http().send(following(3, address(tool, "/twice")));
        AdapterResponse tooFar = subject.http().send(following(1, address(tool, "/twice")));

        assertEquals(200, followed.status());
        assertEquals("target", followed.bodyAsString());
        assertEquals(address(tool, "/target"), followed.uri(), "the response names where it came from");
        assertEquals(302, tooFar.status(), "beyond the allowed hops the redirect goes to the adapter");
    }

    /** Not per RFC 2396 ({@code ?q=1} against {@code /a/b} would give {@code /a/?q=1}). */
    @Test
    public void aLocationIsResolvedAfterRfc3986AndLosesItsFragment() throws Exception {
        FakeHttpTool tool = tool().text("/target", "text/plain", "target");
        tool.route("/a/b", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            if (query == null) {
                redirect(exchange, 302, "?q=1");
            } else {
                FakeHttpTool.respond(exchange, 200, "text/plain", bytes("query " + query));
            }
        });
        tool.route("/fragment", exchange -> redirect(exchange, 302, "/target#part"));
        tool.route("/x/y/z", exchange -> redirect(exchange, 302, "../../../target"));
        Subject subject = subject(DEFAULT_LIMITS, true);

        AdapterResponse query = subject.http().send(following(2, address(tool, "/a/b")));
        AdapterResponse fragment = subject.http().send(following(2, address(tool, "/fragment")));
        AdapterResponse dots = subject.http().send(following(2, address(tool, "/x/y/z")));

        assertEquals("query q=1", query.bodyAsString());
        assertEquals(address(tool, "/a/b?q=1"), query.uri(), "the path stays, only the query changes");
        assertEquals(200, fragment.status());
        assertEquals(address(tool, "/target"), fragment.uri(), "without the fragment");
        assertEquals(address(tool, "/target"), dots.uri(), "'..' above the root ends at the root");
    }

    /**
     * When the origin changes, {@link AdapterRequest#sensitiveHeaders()}, every sign-in (including that of the
     * new origin) and the cookies of the store are dropped; on the same origin everything stays.
     */
    @Test
    public void credentialsAreDroppedWhenARedirectChangesTheOrigin() throws Exception {
        FakeHttpTool target = tool().text("/target", "text/plain", "target");
        FakeHttpTool tool = tool().text("/here", "text/plain", "here");
        tool.route("/away", exchange -> redirect(exchange, 307, address(target, "/target").toString()));
        tool.route("/near", exchange -> redirect(exchange, 307, "/here"));
        Subject subject = subject(DEFAULT_LIMITS, true);
        subject.http().auth(origin(tool), Auth.bearer(SecretValue.of("registered-token")));
        subject.http().auth(origin(target), Auth.bearer(SecretValue.of("token-of-the-target")));
        subject.http().cookies().add(address(target, "/"), new HttpCookie("jar", "of-the-target"));

        subject.http().send(AdapterRequest.builder("GET", address(tool, "/away"))
                .redirects(RedirectPolicy.follow(3))
                .header("X-API-Key", "key-of-the-tool")
                .secretHeader("X-Session-Mark", SecretValue.of("marked-secret"))
                .header("X-Plain", "plain").build());
        subject.http().send(AdapterRequest.builder("GET", address(tool, "/near")).redirects(RedirectPolicy.follow(3))
                .header("X-API-Key", "key-of-the-tool").build());

        FakeHttpTool.Request arrived = target.requests().get(0);
        assertNull(arrived.header("X-API-Key"), "a header with a key in its name stays at its origin");
        assertNull(arrived.header("X-Session-Mark"), "a secret header stays at its origin");
        assertNull(arrived.header("Authorization"), "no sign-in after leaving the origin, not even the target's own");
        assertNull(arrived.header("Cookie"), "no cookie after leaving the origin, not even the target's own");
        assertEquals("plain", arrived.header("X-Plain"), "an ordinary header follows");
        FakeHttpTool.Request near = tool.requests().stream().filter(seen -> seen.path().equals("/here"))
                .findFirst().orElseThrow();
        assertEquals("key-of-the-tool", near.header("X-API-Key"), "on the same origin everything stays");
        assertEquals("Bearer registered-token", near.header("Authorization"));
    }

    @Test
    public void afterLeavingTheOriginNothingIsStoredAndEvenAHopBackGoesWithoutCredentials() throws Exception {
        FakeHttpTool other = tool();
        FakeHttpTool tool = tool();
        tool.route("/login", exchange -> respondSettingCookies(exchange, "in", "sid=s1; Path=/"));
        tool.route("/start", exchange -> redirect(exchange, 302, address(other, "/bounce").toString()));
        tool.route("/back", exchange -> respondSettingCookies(exchange, "back", "late=l1; Path=/"));
        other.route("/bounce", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "foreign=f1; Path=/");
            redirect(exchange, 302, address(tool, "/back").toString());
        });
        Subject subject = subject(DEFAULT_LIMITS, true);
        subject.http().auth(origin(tool), Auth.basic("svc", SecretValue.of("pw-of-the-tool")));
        subject.http().send(AdapterRequest.get(address(tool, "/login")));

        AdapterResponse back = subject.http().send(following(5, address(tool, "/start")));

        assertEquals("back", back.bodyAsString());
        FakeHttpTool.Request start = tool.requests().get(1);
        assertTrue(start.header("Authorization").startsWith("Basic "), "at the origin the sign-in goes");
        assertEquals("sid=s1", start.header("Cookie"));
        FakeHttpTool.Request hopBack = tool.requests().get(2);
        assertEquals("/back", hopBack.path());
        assertNull(hopBack.header("Authorization"), "the chain left the origin: even the hop back goes without");
        assertNull(hopBack.header("Cookie"));
        assertNull(other.requests().get(0).header("Authorization"));
        assertTrue(subject.http().cookies().get(address(other, "/")).isEmpty(), "a hop elsewhere stores nothing");
        assertEquals(List.of("sid"), subject.http().cookies().get(address(tool, "/")).stream()
                .map(HttpCookie::getName).toList(), "nor does the hop back");
    }

    // ------------------------------------------------------------------
    // OAuth
    // ------------------------------------------------------------------

    /** A provider without network access: {@code token-1}, {@code token-2}, ...; expiry after {@link #lifetime}. */
    private static final class CountingProvider implements TokenProvider {
        final AtomicInteger fetched = new AtomicInteger();
        volatile Duration lifetime;

        @Override
        public Token fetch(AdapterHttp unauthenticated) {
            return new Token(SecretValue.of("token-" + fetched.incrementAndGet()),
                    lifetime == null ? null : Instant.now().plus(lifetime));
        }
    }

    /** Signs the subject in at the origin of the tool with OAuth and a new {@link CountingProvider}. */
    private CountingProvider countingProviderAt(Subject subject, FakeHttpTool tool) {
        CountingProvider provider = new CountingProvider();
        subject.http().auth(origin(tool), Auth.oauth(provider));
        return provider;
    }

    /** A creating POST is never sent a second time after a 401. */
    @Test
    public void afterA401OnlyARepeatableRequestIsSentAgain() throws Exception {
        FakeHttpTool tool = tool().route("/item", acceptingOnly("token-4"));
        Subject subject = subject(DEFAULT_LIMITS, true);
        CountingProvider provider = countingProviderAt(subject, tool);
        URI item = address(tool, "/item");

        AdapterResponse create = subject.http().send(AdapterRequest.post(item, Body.ofJson("{\"a\":1}")));
        AdapterResponse empty = subject.http().send(AdapterRequest.builder("POST", item).build());
        AdapterResponse search = subject.http().send(AdapterRequest.builder("POST", item)
                .body(Body.ofJson("{\"q\":1}")).idempotent(true).build());
        AdapterResponse put = subject.http().send(AdapterRequest.put(item, Body.ofJson("{\"a\":2}")));

        assertEquals(401, create.status(), "a create is not sent again, even with bytes");
        assertEquals(401, empty.status(), "nor a POST without a body");
        assertEquals(200, search.status(), "a reading POST marked idempotent is sent again with a new token");
        assertEquals(200, put.status());
        assertEquals(List.of("POST Bearer token-1", "POST Bearer token-2", "POST Bearer token-3",
                        "POST Bearer token-4", "PUT Bearer token-4"),
                tool.requests().stream().map(seen -> seen.method() + " " + seen.header("Authorization")).toList(),
                "a 401 that is not sent again drops its token; the next request fetches a new one");
        assertEquals(4, provider.fetched.get());
    }

    @Test
    public void aTokenIsRenewedAtMostOncePerRequestAcrossAllHops() throws Exception {
        FakeHttpTool tool = tool().text("/c", "text/plain", "c");
        tool.route("/a", exchange -> {
            if ("Bearer token-2".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                redirect(exchange, 302, "/b");
            } else {
                FakeHttpTool.respond(exchange, 401, "text/plain", bytes("a"));
            }
        });
        tool.route("/b", exchange -> FakeHttpTool.respond(exchange, 401, "text/plain", bytes("b")));
        Subject subject = subject(DEFAULT_LIMITS, true);
        CountingProvider provider = countingProviderAt(subject, tool);

        AdapterResponse refused = subject.http().send(following(3, address(tool, "/a")));
        int afterChain = provider.fetched.get();
        subject.http().send(AdapterRequest.get(address(tool, "/c")));

        assertEquals(401, refused.status(), "the 401 of the second hop goes to the adapter");
        assertEquals(2, afterChain, "one fetch, one renewal for the whole request");
        assertEquals(1, tool.requests().stream().filter(seen -> seen.path().equals("/b")).count());
        assertEquals(3, provider.fetched.get(), "the rejected token was dropped: the next request fetches");
    }

    @Test
    public void anAuthorizationOfTheRequestIsNeverRenewedNorSentAgain() throws Exception {
        FakeHttpTool tool = tool().route("/item", acceptingOnly("never"));
        Subject subject = subject(DEFAULT_LIMITS, true);
        CountingProvider provider = countingProviderAt(subject, tool);

        AdapterResponse own = subject.http().send(AdapterRequest.builder("GET", address(tool, "/item"))
                .secretHeader("Authorization", SecretValue.of("Bearer own-token")).build());

        assertEquals(401, own.status());
        assertEquals(0, provider.fetched.get());
        assertEquals(1, tool.requests().size());
    }

    @Test
    public void theProviderFetchesOverAViewWithoutSignInAndWithoutCookies() throws Exception {
        FakeHttpTool tool = tool().route("/item", exchange -> FakeHttpTool.respond(exchange, 200, "text/plain",
                bytes("item")));
        tool.route("/token", exchange -> respondSettingCookies(exchange, "token-of-the-view", "view=v1; Path=/"));
        Subject subject = subject(DEFAULT_LIMITS, true);
        subject.http().cookies().add(address(tool, "/"), new HttpCookie("sid", "jar"));
        AtomicReference<AdapterHttp> view = new AtomicReference<>();
        subject.http().auth(origin(tool), Auth.oauth(unauthenticated -> {
            view.set(unauthenticated);
            AdapterResponse posted = unauthenticated.send(AdapterRequest.builder("POST", address(tool, "/token"))
                    .secretHeader("X-Client-Secret", SecretValue.of("client-secret"))
                    .body(Body.ofString("grant_type=client_credentials", "application/x-www-form-urlencoded"))
                    .build());
            try (StreamResponse opened = unauthenticated.open(AdapterRequest.get(address(tool, "/token")))) {
                opened.body().readAllBytes();
            } catch (IOException unreadable) {
                throw new AdapterException(unreadable.getMessage(), unreadable);
            }
            return new TokenProvider.Token(SecretValue.of(posted.bodyAsString()), null);
        }));

        assertEquals(200, subject.http().send(AdapterRequest.get(address(tool, "/item"))).status());

        List<FakeHttpTool.Request> fetches = tool.requests().stream().filter(seen -> seen.path().equals("/token"))
                .toList();
        assertEquals(2, fetches.size(), "send and open of the view");
        for (FakeHttpTool.Request fetch : fetches) {
            assertNull(fetch.header("Authorization"), "fetching never needs a token");
            assertNull(fetch.header("Cookie"), "no cookies of the store");
        }
        assertEquals("client-secret", fetches.get(0).header("X-Client-Secret"));
        FakeHttpTool.Request item = tool.requests().get(tool.requests().size() - 1);
        assertEquals("Bearer token-of-the-view", item.header("Authorization"));
        assertEquals("sid=jar", item.header("Cookie"));
        assertEquals(List.of("sid"), subject.http().cookies().getCookies().stream().map(HttpCookie::getName).toList(),
                "the view stores no cookies");
        assertThrows(UnsupportedOperationException.class, () -> view.get().cookies());
        assertThrows(UnsupportedOperationException.class,
                () -> view.get().auth(origin(tool), Auth.bearer(SecretValue.of("x-token"))));
    }

    @Test
    public void aProviderThatTakesTheNetworkPathOfTheContextFailsInsteadOfWaitingForItself() throws Exception {
        FakeHttpTool tool = tool().text("/token", "text/plain", "token");
        Subject subject = subject(DEFAULT_LIMITS, true);
        AdapterHttp http = subject.http();
        http.auth(origin(tool), Auth.oauth(unauthenticated -> new TokenProvider.Token(SecretValue.of(
                http.send(AdapterRequest.get(address(tool, "/token"))).bodyAsString()), null)));

        long started = System.nanoTime();
        AdapterException failed = assertThrows(AdapterException.class,
                () -> http.send(AdapterRequest.get(address(tool, "/item"))));

        assertTrue(failed.getMessage().contains("must fetch over the network path it is given"), failed.getMessage());
        assertFalse(failed.isRetryable());
        assertTrue(elapsed(started).compareTo(Duration.ofSeconds(5)) < 0, elapsed(started).toString());
        assertEquals(List.of(), tool.requests(), "nothing was sent");
    }

    /** A token of 2 s is not fetched anew before every request, but before it expires. */
    @Test
    public void aTokenIsFetchedAgainBeforeItExpiresButAShortOneNotForEveryRequest() throws Exception {
        FakeHttpTool tool = tool().route("/item", exchange -> FakeHttpTool.respond(exchange, 200, "text/plain",
                bytes("item")));
        Subject subject = subject(DEFAULT_LIMITS, true);
        CountingProvider provider = new CountingProvider();
        provider.lifetime = Duration.ofSeconds(2);
        subject.http().auth(origin(tool), Auth.oauth(provider));
        URI item = address(tool, "/item");

        subject.http().send(AdapterRequest.get(item));
        subject.http().send(AdapterRequest.get(item));
        assertEquals(1, provider.fetched.get(), "renewed at most half the lifetime ahead, not now");
        provider.lifetime = Duration.ofHours(1);
        pause(Duration.ofMillis(1300));
        subject.http().send(AdapterRequest.get(item));
        assertEquals(2, provider.fetched.get(), "within half the lifetime before its expiry");
        subject.http().send(AdapterRequest.get(item));
        assertEquals(2, provider.fetched.get());
    }

    @Test
    public void aFailingProviderFailsTheRequestAndKeepsItsKind() throws Exception {
        FakeHttpTool tool = tool().text("/item", "text/plain", "item");
        Subject subject = subject(DEFAULT_LIMITS, true);
        subject.http().auth(origin(tool), Auth.oauth(unauthenticated -> {
            throw new AdapterException("token endpoint busy", null, true, 503);
        }));
        AdapterException busy = assertThrows(AdapterException.class,
                () -> subject.http().send(AdapterRequest.get(address(tool, "/item"))));
        subject.http().auth(origin(tool), Auth.oauth(unauthenticated -> {
            throw new IllegalArgumentException("broken provider");
        }));
        AdapterException broken = assertThrows(AdapterException.class,
                () -> subject.http().send(AdapterRequest.get(address(tool, "/item"))));

        assertTrue(busy.isRetryable(), "as the provider reports it");
        assertEquals(503, busy.httpStatus().orElse(-1), "with its HTTP status");
        assertTrue(busy.getMessage().contains("could not be fetched"), busy.getMessage());
        assertFalse(broken.isRetryable());
        assertTrue(broken.getMessage().contains("broken provider"), broken.getMessage());
        assertEquals(List.of(), tool.requests(), "without a token no request goes");
    }

    // ------------------------------------------------------------------
    // Cookies
    // ------------------------------------------------------------------

    @Test
    public void cookiesBelongToTheirOriginAndACookieTheRequestCarriesWins() throws Exception {
        FakeHttpTool first = tool().text("/data", "text/plain", "data");
        first.route("/login", exchange -> respondSettingCookies(exchange, "ok", "session=abc123; Path=/"));
        FakeHttpTool second = tool().text("/data", "text/plain", "data");
        Subject subject = subject(DEFAULT_LIMITS, true);

        subject.http().send(AdapterRequest.get(address(first, "/login")));
        subject.http().send(AdapterRequest.get(address(first, "/data")));
        subject.http().send(AdapterRequest.get(address(second, "/data")));
        subject.http().send(AdapterRequest.builder("GET", address(first, "/data")).header("Cookie", "session=own")
                .build());

        List<FakeHttpTool.Request> atFirst = first.requests();
        assertEquals("session=abc123", atFirst.get(1).header("Cookie"), "the cookie goes back to its origin");
        assertNull(second.requests().get(0).header("Cookie"), "another origin (port) gets no cookie");
        assertEquals(List.of("session=own"), atFirst.get(2).headers().get("Cookie"),
                "a cookie the request carries wins over the store, as an Authorization does");
    }

    /** RFC 6265: path and default path, {@code Secure}, deletion by a negative {@code Max-Age}, a bounded line. */
    @Test
    public void cookiesFollowRfc6265AndGoOutAsNameAndValue() throws Exception {
        FakeHttpTool tool = tool().text("/api/item", "text/plain", "item");
        tool.text("/apiary", "text/plain", "apiary");
        tool.text("/big", "text/plain", "big");
        tool.route("/api/login", exchange -> respondSettingCookies(exchange, "in", "pos=5; Max-Age=600",
                "root=1; Path=/", "safe=1; Path=/; Secure", "gone=1; Path=/"));
        tool.route("/api/logout", exchange -> respondSettingCookies(exchange, "out", "gone=x; Path=/; Max-Age=-5"));
        Subject subject = subject(DEFAULT_LIMITS, true);

        subject.http().send(AdapterRequest.get(address(tool, "/api/login")));
        subject.http().send(AdapterRequest.get(address(tool, "/api/logout")));
        subject.http().send(AdapterRequest.get(address(tool, "/api/item")));
        subject.http().send(AdapterRequest.get(address(tool, "/apiary")));

        List<FakeHttpTool.Request> seen = tool.requests();
        assertEquals("pos=5; root=1", seen.get(2).header("Cookie"), "name=value only, the longer path first;"
                + " Secure not from http, a negative Max-Age deletes");
        assertEquals("root=1", seen.get(3).header("Cookie"), "the default path /api does not match /apiary");
        assertThrows(IllegalArgumentException.class,
                () -> subject.http().cookies().add(null, new HttpCookie("a", "1")), "a cookie without origin");

        for (int i = 0; i < 4; i++) {
            subject.http().cookies().add(address(tool, "/"), new HttpCookie("big" + i, "x".repeat(3000)));
        }
        subject.http().send(AdapterRequest.get(address(tool, "/big")));
        String line = tool.requests().get(tool.requests().size() - 1).header("Cookie");
        assertTrue(line.length() <= 8192, "the Cookie line is bounded: " + line.length());
        assertTrue(line.contains("big0="), "what fits goes");
    }

    // ------------------------------------------------------------------
    // Retries
    // ------------------------------------------------------------------

    @Test
    public void aRepeatableGetIsRepeatedAfterA503WithinItsTimeout() throws Exception {
        assumeTrue(repeats(), "the transport alone does not repeat");
        FakeHttpTool tool = tool().route("/busy", failingFirst(2, 503, null));
        Subject subject = subject(DEFAULT_LIMITS, true);
        long before = subject.attempts();

        AdapterResponse response = subject.http().send(AdapterRequest.get(address(tool, "/busy")));

        assertEquals(200, response.status());
        assertEquals("done", response.bodyAsString());
        assertEquals(before + 3, subject.attempts(), "every attempt counts");
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4)), subject.retryWaits());
    }

    @Test
    public void afterTheLastRetryTheAnswerGoesToTheAdapterAndNoWaitOutlastsTheTimeout() throws Exception {
        assumeTrue(repeats(), "the transport alone does not repeat");
        FakeHttpTool tool = tool().route("/down", failingFirst(Integer.MAX_VALUE, 503, null));
        Subject subject = subject(new Limits(Duration.ofSeconds(60), 64 * 1024), true);
        long before = subject.attempts();

        assertEquals(503, subject.http().send(AdapterRequest.get(address(tool, "/down"))).status());
        assertEquals(before + 4, subject.attempts(), "one attempt and at most three retries");
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8)),
                subject.retryWaits());

        Subject quick = subject(DEFAULT_LIMITS, true);
        long beforeQuick = quick.attempts();
        assertEquals(503, quick.http().send(getWithin(Duration.ofSeconds(5), address(tool, "/down"))).status());
        assertEquals(beforeQuick + 2, quick.attempts(), "2 s fit into a timeout of 5 s, 4 s more do not");
        assertEquals(List.of(Duration.ofSeconds(2)), quick.retryWaits());
    }

    @Test
    public void aCreateAndAnUploadAreNeverRepeatedNotEvenAfterA429() throws Exception {
        assumeTrue(repeats(), "the transport alone does not repeat");
        FakeHttpTool tool = tool()
                .route("/items", failingFirst(Integer.MAX_VALUE, 429, "1"))
                .route("/files", failingFirst(Integer.MAX_VALUE, 503, null))
                .route("/search", failingFirst(1, 429, "1"));
        Subject subject = subject(DEFAULT_LIMITS, true);
        long before = subject.attempts();
        AdapterRequest create = AdapterRequest.builder("POST", address(tool, "/items"))
                .body(Body.ofBytes(bytes("{}"), "application/json")).build();
        AdapterRequest upload = AdapterRequest.builder("PUT", address(tool, "/files"))
                .body(Body.ofStream(4, "application/octet-stream", () -> new ByteArrayInputStream(bytes("data"))))
                .build();

        assertEquals(429, subject.http().send(create).status(), "the 429 goes to the adapter at once");
        assertEquals(503, subject.http().send(upload).status(), "a streamed body is never sent again");
        assertEquals(before + 2, subject.attempts(), "one attempt each");
        assertEquals(List.of(), subject.retryWaits());

        AdapterRequest search = AdapterRequest.builder("POST", address(tool, "/search"))
                .body(Body.ofBytes(bytes("{}"), "application/json")).idempotent(true).build();
        assertEquals(200, subject.http().send(search).status(), "a reading POST marked as repeatable");
        assertEquals(before + 4, subject.attempts());
        assertEquals(List.of(Duration.ofSeconds(1)), subject.retryWaits());
    }

    @Test
    public void retryAfterIsFollowedUpToSixtySeconds() throws Exception {
        assumeTrue(repeats(), "the transport alone does not repeat");
        FakeHttpTool tool = tool()
                .route("/soon", failingFirst(1, 429, "3"))
                .route("/later", failingFirst(1, 503, "3600"))
                .route("/unreadable", failingFirst(1, 429, "soon"));
        Subject subject = subject(new Limits(Duration.ofSeconds(120), 64 * 1024), true);

        for (String path : List.of("/soon", "/later", "/unreadable")) {
            assertEquals(200, subject.http().send(AdapterRequest.get(address(tool, path))).status(), path);
        }

        assertEquals(List.of(Duration.ofSeconds(3), Duration.ofSeconds(60), Duration.ofSeconds(2)),
                subject.retryWaits(), "Retry-After, at most 60 s; unreadable: the wait without one");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Subject subject(Limits limits, boolean closeAfter) throws Exception {
        Subject subject = subject(limits);
        if (closeAfter) {
            opened.add(0, subject);
        }
        return subject;
    }

    private FakeHttpTool tool() throws IOException {
        FakeHttpTool tool = FakeHttpTool.startWithoutBindingHeader();
        opened.add(tool);
        return tool;
    }

    private URI address(FakeHttpTool tool, String path) {
        return reachable(tool.uri(path));
    }

    /** The origin of the tool as the HTTP path reaches it. */
    private URI origin(FakeHttpTool tool) {
        return originOf(address(tool, "/"));
    }

    /** Scheme and authority of an address. */
    private static URI originOf(URI address) {
        return URI.create(address.getScheme() + "://" + address.getRawAuthority());
    }

    /**
     * The source of an upload that fails after the tool began to answer: 256 KiB of zero bytes, then every read
     * waits for {@code answered} and {@code headsSeen}, a moment longer where the headers are not reported
     * ({@code stream} false, or {@code headsSeen} does not come), and fails.
     */
    private static InputStream sourceFailingAfterTheAnswer(CountDownLatch answered, CountDownLatch headsSeen,
                                                           boolean stream) {
        return new InputStream() {
            private long sent;

            @Override
            public int read() throws IOException {
                byte[] single = new byte[1];
                return read(single, 0, 1) < 0 ? -1 : 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int count) throws IOException {
                if (sent >= 256 * 1024) {
                    try {
                        answered.await(5, TimeUnit.SECONDS);
                        if (!headsSeen.await(5, TimeUnit.SECONDS)) {
                            Thread.sleep(300);
                        }
                        if (!stream) {
                            Thread.sleep(300);
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    throw new IOException("the source of the upload failed");
                }
                int n = (int) Math.min(count, 64 * 1024);
                Arrays.fill(buffer, offset, offset + n, (byte) 0);
                sent += n;
                return n;
            }
        };
    }

    /** The source of an upload of {@code length} zero bytes; {@code closed} is counted down when it is closed. */
    private static InputStream sourceReportingItsClose(long length, CountDownLatch closed) {
        return new InputStream() {
            private long left = length;

            @Override
            public int read() {
                byte[] single = new byte[1];
                return read(single, 0, 1) < 0 ? -1 : 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int count) {
                if (left <= 0) {
                    return -1;
                }
                int n = (int) Math.min(count, left);
                Arrays.fill(buffer, offset, offset + n, (byte) 0);
                left -= n;
                return n;
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };
    }

    /** A GET with its own timeout. */
    private static AdapterRequest getWithin(Duration timeout, URI address) {
        return AdapterRequest.builder("GET", address).timeout(timeout).build();
    }

    /** A GET that follows up to {@code hops} redirects. */
    private static AdapterRequest following(int hops, URI address) {
        return AdapterRequest.builder("GET", address).redirects(RedirectPolicy.follow(hops)).build();
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
