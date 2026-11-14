package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.StreamResponse;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The response of one attempt of the HTTP path, buffered or as a stream.
 *
 * @param status  the status code
 * @param headers the response headers, without those of an encoding that the HTTP path has removed
 * @param body    the buffered body; {@code null} for a stream
 * @param stream  the streamed response; {@code null} for a buffered one
 */
record TckHttpAnswer(int status, Map<String, List<String>> headers, byte[] body, StreamResponse stream) {

    /** The response as the adapter gets it: the stream, or the buffered response from {@code target}. */
    Object result(URI target) throws AdapterException {
        if (stream != null) {
            return stream;
        }
        try {
            return new AdapterResponse(status, target, headers, body);
        } catch (IllegalArgumentException invalid) {
            throw new AdapterException("the tool sent an invalid response (" + invalid.getMessage() + ")", null,
                    false);
        }
    }

    /** Discards a response that the HTTP path does not hand out (redirect, renewed token). */
    void discard() {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                //aborted
            }
        }
    }

    /** The first value of a header, ignoring the case of its name. */
    Optional<String> firstHeader(String name) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                return Optional.of(entry.getValue().get(0));
            }
        }
        return Optional.empty();
    }

    /** All values of a header, ignoring the case of its name. */
    List<String> allHeaders(String name) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream()).toList();
    }
}
