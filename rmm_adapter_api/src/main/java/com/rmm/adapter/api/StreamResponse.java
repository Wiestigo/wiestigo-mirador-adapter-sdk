package com.rmm.adapter.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Response as a stream from {@link AdapterHttp#open}, for downloads of gigabyte
 * size without buffering: memory use stays the same however large the content
 * is.
 *
 * <p>Whoever opens it closes it:
 * {@code try (StreamResponse response = http.open(request))}. Closing before
 * the end <b>aborts</b>: the host discards the connection instead of reading
 * the rest. A stream that is not closed holds a connection of the binding
 * until the deadline of the run ends it.
 *
 * <p>After the headers, the deadline of the request applies as an idle timeout
 * between two chunks read; the deadline of the run always applies
 * ({@link AdapterRequest}). The body always arrives decompressed; if the host
 * decompressed it, the length is unknown.
 */
public final class StreamResponse implements AutoCloseable {

    private final int status;
    private final URI uri;
    private final Map<String, List<String>> headers;
    private final OptionalLong length;
    private final InputStream body;

    /**
     * @param status  status (100 to 599)
     * @param uri     address the response came from (after redirects)
     * @param headers headers; they are copied
     * @param length  length of the body ({@code Content-Length}); empty = unknown
     * @param body    the body; its {@code close()} aborts the transfer
     */
    public StreamResponse(int status, URI uri, Map<String, List<String>> headers, OptionalLong length,
                          InputStream body) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be between 100 and 599: " + status);
        }
        Objects.requireNonNull(length, "length");
        if (length.isPresent() && length.getAsLong() < 0) {
            throw new IllegalArgumentException("length must not be negative: " + length.getAsLong());
        }
        this.status = status;
        this.uri = Objects.requireNonNull(uri, "uri");
        this.headers = HttpRules.copyHeaders(headers);
        this.length = length;
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

    public Map<String, List<String>> headers() {
        return headers;
    }

    public Optional<String> header(String name) {
        return HttpRules.firstHeader(headers, name);
    }

    public List<String> headerValues(String name) {
        return headers.getOrDefault(name, List.of());
    }

    /**
     * Length according to {@code Content-Length}; empty if the tool states
     * none or the host decompresses a compressed body.
     */
    public OptionalLong length() {
        return length;
    }

    /** The body; always the same stream. */
    public InputStream body() {
        return body;
    }

    /** Closes the stream; before the end this aborts the transfer. */
    @Override
    public void close() throws IOException {
        body.close();
    }

    /** Without headers and the query of the address: they may carry secrets. */
    @Override
    public String toString() {
        return "StreamResponse[" + status + " " + HttpRules.forLog(uri) + ", length=" + length + "]";
    }
}
