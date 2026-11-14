package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.ResponseTooLargeException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.zip.GZIPInputStream;

import static com.rmm.adapter.tck.TckHttpMessages.describe;
import static com.rmm.adapter.tck.TckHttpMessages.message;

/**
 * The body of a response on the HTTP path: buffered up to the maximum size, which counts the decompressed bytes,
 * and the handling of a gzip encoding that the HTTP path asks for itself.
 */
final class TckHttpResponseBody {

    private TckHttpResponseBody() {
    }

    /**
     * Buffers a response of at most {@code limit} bytes. A response that announces too large a size fails before
     * the first byte; a {@code HEAD} response announces the size of a body it does not carry.
     */
    static HttpResponse.BodyHandler<byte[]> buffered(boolean head, int limit) {
        return info -> {
            boolean gzip = gzipped(info.headers());
            OptionalLong announced = info.headers().firstValueAsLong("Content-Length");
            if (!head && !gzip && announced.isPresent() && announced.getAsLong() > limit) {
                //Announced as too large: fails before the first byte
                return new Limited(-1);
            }
            //When compressed, only the decompressed bytes count; the compressed form may be slightly larger
            return new Limited(gzip ? limit + limit / 16 + 1024 : limit);
        };
    }

    /** Whether the response is compressed with gzip. */
    static boolean gzipped(HttpHeaders headers) {
        return headers.allValues("Content-Encoding").stream()
                .anyMatch(value -> value.trim().equalsIgnoreCase("gzip"));
    }

    /** The headers of a decompressed body: without encoding and length, which no longer apply. */
    static Map<String, List<String>> withoutEncoding(Map<String, List<String>> headers) {
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        copy.remove("Content-Encoding");
        copy.remove("Content-Length");
        return copy;
    }

    /** Decompresses at most {@code limit} bytes; more is too large. */
    static byte[] gunzip(AdapterRequest request, byte[] packed, int limit) throws AdapterException {
        if (packed.length == 0) {
            return packed;
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            for (int n; (n = in.read(buffer)) >= 0; ) {
                total += n;
                if (total > limit) {
                    throw tooLarge(request, limit);
                }
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException invalid) {
            throw new AdapterException(message(request, "the tool sent an invalid response (" + describe(invalid)
                    + ")"), null, false);
        }
    }

    /** Whether a failure of the exchange says that the response is larger than the maximum size. */
    static boolean exceededLimit(Throwable failure) {
        for (Throwable link = failure; link != null; link = link.getCause() == link ? null : link.getCause()) {
            if (link instanceof TooLarge) {
                return true;
            }
        }
        return false;
    }

    /** As in the host: final, with its own exception type. */
    static AdapterException tooLarge(AdapterRequest request, int limit) {
        return new ResponseTooLargeException(message(request, "the response is larger than the limit of "
                + limit + " bytes (RMM_MODEL_ADAPTER_HTTP_MAX_RESPONSE_BYTES) - large contents"
                + " are read with open()"));
    }

    /** Buffers at most {@code limit} bytes; {@code limit < 0} fails immediately. */
    private static final class Limited implements HttpResponse.BodySubscriber<byte[]> {

        private final int limit;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        private Limited(int limit) {
            this.limit = limit;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (limit < 0) {
                subscription.cancel();
                result.completeExceptionally(new TooLarge());
                return;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new TooLarge());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }
    }

    /** The response is larger than the maximum size. */
    private static final class TooLarge extends IOException {
        private TooLarge() {
            super("the response is larger than the limit", null);
        }
    }
}
