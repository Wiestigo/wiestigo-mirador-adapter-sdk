package com.rmm.adapter.api;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Buffered response from {@link AdapterHttp#send}, for REST calls with JSON.
 * The host reads the body only up to its maximum size; a larger response fails
 * as {@link AdapterException} before it is completely in memory. Large content
 * is fetched as a stream with {@link AdapterHttp#open}.
 *
 * <p>Every status comes back as a response, including 4xx and 5xx; the adapter
 * decides what it means.
 */
public final class AdapterResponse {

    private final int status;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    /**
     * @param status  status (100 to 599)
     * @param uri     address the response came from (after redirects)
     * @param headers headers; they are copied
     * @param body    body; not copied, the host hands it over
     */
    public AdapterResponse(int status, URI uri, Map<String, List<String>> headers, byte[] body) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be between 100 and 599: " + status);
        }
        this.status = status;
        this.uri = Objects.requireNonNull(uri, "uri");
        this.headers = HttpRules.copyHeaders(headers);
        this.body = Objects.requireNonNull(body, "body");
    }

    public int status() {
        return status;
    }

    /** Status 2xx. */
    public boolean successful() {
        return status >= 200 && status < 300;
    }

    public URI uri() {
        return uri;
    }

    /** All headers; names are case-insensitive. */
    public Map<String, List<String>> headers() {
        return headers;
    }

    /** The first value of the header. */
    public Optional<String> header(String name) {
        return HttpRules.firstHeader(headers, name);
    }

    /** All values of the header, in their order. */
    public List<String> headerValues(String name) {
        return headers.getOrDefault(name, List.of());
    }

    /** The body as a copy. */
    public byte[] body() {
        return body.clone();
    }

    /** The body as a stream, without a copy. */
    public InputStream bodyStream() {
        return new ByteArrayInputStream(body);
    }

    /** The body as text in the charset from {@code Content-Type}, otherwise UTF-8. */
    public String bodyAsString() {
        return new String(body, HttpRules.charsetOf(header("Content-Type")));
    }

    /** Without headers, body and the query of the address: they may carry secrets. */
    @Override
    public String toString() {
        return "AdapterResponse[" + status + " " + HttpRules.forLog(uri) + ", " + body.length + " bytes]";
    }
}
