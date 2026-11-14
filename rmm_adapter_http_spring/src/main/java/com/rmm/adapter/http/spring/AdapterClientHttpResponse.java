package com.rmm.adapter.http.spring;

import com.rmm.adapter.api.StreamResponse;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseCookie;
import org.springframework.http.client.reactive.AbstractClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A {@link StreamResponse} of the host as a response for Spring: the exact
 * status, all values of every header, the body as a stream. Reading happens
 * on a connector thread, chunk by chunk on demand; at the end, on an error
 * and on cancellation the stream is closed (before the end = abort), also on
 * cancellation before the first read. Spring allows only one subscription to
 * the body.
 */
final class AdapterClientHttpResponse extends AbstractClientHttpResponse {

    /** Size of one chunk that is read. */
    static final int CHUNK = 8192;

    private static final Log LOG = LogFactory.getLog(AdapterClientHttpResponse.class);

    AdapterClientHttpResponse(StreamResponse response, DataBufferFactory bufferFactory, Scheduler scheduler) {
        super(HttpStatusCode.valueOf(response.status()), headersOf(response), cookiesOf(response),
                bodyOf(response, bufferFactory, scheduler));
    }

    /**
     * All values per name. The body always arrives decompressed: a
     * {@code Content-Encoding} is removed, and {@code Content-Length} is
     * present only with a known length.
     */
    private static HttpHeaders headersOf(StreamResponse response) {
        HttpHeaders headers = new HttpHeaders();
        response.headers().forEach((name, values) -> {
            if (!HttpHeaders.CONTENT_LENGTH.equalsIgnoreCase(name)
                    && !HttpHeaders.CONTENT_ENCODING.equalsIgnoreCase(name)) {
                headers.addAll(name, values);
            }
        });
        response.length().ifPresent(headers::setContentLength);
        return headers;
    }

    /**
     * Every {@code Set-Cookie} header is exactly one cookie (RFC 6265, no
     * splitting at commas), with its own attributes including SameSite. A
     * cookie that Spring does not accept (a space or comma in the value, an
     * underscore in {@code Domain} …) is missing only here; the header stays
     * in {@code getHeaders()}, and the request succeeds.
     */
    private static MultiValueMap<String, ResponseCookie> cookiesOf(StreamResponse response) {
        MultiValueMap<String, ResponseCookie> cookies = new LinkedMultiValueMap<>();
        for (String header : response.headerValues(HttpHeaders.SET_COOKIE)) {
            try {
                ResponseCookie cookie = cookieOf(header);
                cookies.add(cookie.getName(), cookie);
            } catch (IllegalArgumentException invalid) {
                //Only the name: value and message can carry secrets
                LOG.debug("invalid Set-Cookie skipped: " + nameOf(header));
            }
        }
        return cookies;
    }

    private static ResponseCookie cookieOf(String header) {
        String[] parts = header.split(";");
        int eq = parts[0].indexOf('=');
        if (eq < 1) {
            throw new IllegalArgumentException("Set-Cookie without name");
        }
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(
                parts[0].substring(0, eq).trim(), parts[0].substring(eq + 1).trim());
        Long maxAge = null;
        Long expires = null;
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i].trim();
            int split = attribute.indexOf('=');
            String name = (split < 0 ? attribute : attribute.substring(0, split).trim()).toLowerCase(Locale.ROOT);
            String value = split < 0 ? "" : attribute.substring(split + 1).trim();
            switch (name) {
                case "domain" -> builder.domain(value.isEmpty() ? null : value);
                case "path" -> builder.path(value.isEmpty() ? null : value);
                case "secure" -> builder.secure(true);
                case "httponly" -> builder.httpOnly(true);
                case "samesite" -> builder.sameSite(value.isEmpty() ? null : value);
                case "max-age" -> maxAge = parseLong(value);
                case "expires" -> expires = secondsUntil(value);
                default -> {
                    //Unknown attributes (Partitioned, Priority …) stay in the header
                }
            }
        }
        builder.maxAge(maxAge != null ? maxAge : expires != null ? expires : -1);
        return builder.build();
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static Long secondsUntil(String date) {
        try {
            Instant expires = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Math.max(0, Duration.between(Instant.now(), expires).getSeconds());
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    private static String nameOf(String header) {
        int end = header.indexOf('=');
        String name = (end < 0 ? header : header.substring(0, end)).trim();
        return name.length() > 64 ? name.substring(0, 64) + "…" : name;
    }

    private static Flux<DataBuffer> bodyOf(StreamResponse response, DataBufferFactory bufferFactory,
                                           Scheduler scheduler) {
        InputStream body = response.body();
        return Flux.<DataBuffer>generate(sink -> {
                    byte[] chunk = new byte[CHUNK];
                    int n;
                    try {
                        n = body.read(chunk);
                    } catch (IOException failure) {
                        sink.error(failure);
                        return;
                    }
                    if (n < 0) {
                        sink.complete();
                    } else {
                        sink.next(bufferFactory.wrap(ByteBuffer.wrap(chunk, 0, n)));
                    }
                })
                .doFinally(signal -> AdapterHttpConnector.closeQuietly(response))
                //Every demand (also one from the event loop) reads on a connector thread
                .subscribeOn(scheduler)
                //Cancelled before the subscription ran there: the doFinally above would never fire
                .doOnCancel(() -> AdapterHttpConnector.closeQuietly(response));
    }
}
