package com.rmm.adapter.tck;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * The answers of {@link FakeHttpTool} routes that several checks of {@link AdapterHttpSemantics} use: redirects,
 * bodies that trickle, stall or never come, compressed bodies, cookies, and routes that answer differently per
 * attempt or per token.
 */
final class FakeToolHandlers {

    private FakeToolHandlers() {
    }

    /** Answers the first {@code failures} requests with {@code status} (and {@code Retry-After}), then 200. */
    static HttpHandler failingFirst(int failures, int status, String retryAfter) {
        AtomicInteger seen = new AtomicInteger();
        return exchange -> {
            if (seen.getAndIncrement() < failures) {
                if (retryAfter != null) {
                    exchange.getResponseHeaders().set("Retry-After", retryAfter);
                }
                FakeHttpTool.respond(exchange, status, "text/plain", utf8("busy"));
            } else {
                FakeHttpTool.respond(exchange, 200, "text/plain", utf8("done"));
            }
        };
    }

    /** Answers 200 if the request carries {@code Bearer <accepted>}, otherwise 401. */
    static HttpHandler acceptingOnly(String accepted) {
        return exchange -> FakeHttpTool.respond(exchange,
                ("Bearer " + accepted).equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401,
                "text/plain", utf8("item"));
    }

    /** Answers {@code status} with the header {@code Location} and without a body. */
    static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        FakeHttpTool.respond(exchange, status, null, new byte[0]);
    }

    /** Answers 200 with the text {@code body} and one {@code Set-Cookie} header per cookie. */
    static void respondSettingCookies(HttpExchange exchange, String body, String... cookies) throws IOException {
        for (String cookie : cookies) {
            exchange.getResponseHeaders().add("Set-Cookie", cookie);
        }
        FakeHttpTool.respond(exchange, 200, "text/plain", utf8(body));
    }

    /** Headers at once, then {@code pieces} bytes at intervals of {@code gap}. */
    static void trickle(HttpExchange exchange, int pieces, Duration gap) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (int piece = 0; piece < pieces; piece++) {
                out.write('x');
                out.flush();
                pause(gap);
            }
        }
    }

    /** Headers and the byte {@code a} at once, the byte {@code b} and the end only after {@code gap}. */
    static void stall(HttpExchange exchange, Duration gap) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        out.write('a');
        out.flush();
        pause(gap);
        out.write('b');
        out.close();
    }

    /** Announces a body of {@code length} bytes, sends none of it and closes the exchange after {@code held}. */
    static void announceWithoutSending(HttpExchange exchange, long length, Duration held) throws IOException {
        exchange.sendResponseHeaders(200, length);
        exchange.getResponseBody().flush();
        pause(held);
        exchange.close();
    }

    /** {@code size} bytes {@code a}, gzip-compressed; only if the request accepts gzip. */
    static void gzipped(HttpExchange exchange, int size) throws IOException {
        String accepted = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        if (accepted == null || !accepted.contains("gzip")) {
            FakeHttpTool.respond(exchange, 400, "text/plain", utf8("gzip expected"));
            return;
        }
        ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(packed)) {
            out.write("a".repeat(size).getBytes(StandardCharsets.US_ASCII));
        }
        exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        FakeHttpTool.respond(exchange, 200, "text/plain", packed.toByteArray());
    }

    /** Waits for {@code duration}; an interrupt ends the wait and stays set. */
    static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
