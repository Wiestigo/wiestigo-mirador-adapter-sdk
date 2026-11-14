package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.Body;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import static com.rmm.adapter.tck.TckHttpMessages.describe;
import static com.rmm.adapter.tck.TckHttpMessages.message;

/**
 * The body of a request as the JDK client sends it: a replayable body from memory, a streamed one (an upload) from
 * its source, which is opened once and closed even when the client does not send it to the end.
 */
final class TckHttpRequestBody {

    private TckHttpRequestBody() {
    }

    /** The publisher of the request's body; a replayable body is read here, a stream only when the client sends. */
    static HttpRequest.BodyPublisher publisher(AdapterRequest request) throws AdapterException {
        Optional<Body> body = request.body();
        if (body.isEmpty() || body.get().length() == 0) {
            return HttpRequest.BodyPublishers.noBody();
        }
        Body content = body.get();
        if (content.replayable()) {
            try (InputStream in = content.open()) {
                return HttpRequest.BodyPublishers.ofByteArray(in.readAllBytes());
            } catch (IOException | RuntimeException unreadable) {
                throw new AdapterException(message(request, "the request body cannot be read ("
                        + describe(unreadable) + ")"), null, false);
            }
        }
        //A stream is opened only when the client sends, and only once
        AtomicReference<InputStream> opened = new AtomicReference<>();
        HttpRequest.BodyPublisher streamed = HttpRequest.BodyPublishers.fromPublisher(
                HttpRequest.BodyPublishers.ofInputStream(() -> {
                    try {
                        InputStream in = content.open();
                        //Already closed (cancelled, failed) before the source was open: close it again right away
                        if (!opened.compareAndSet(null, in)) {
                            in.close();
                            throw new IOException("the upload was aborted");
                        }
                        return in;
                    } catch (IOException unreadable) {
                        throw new UncheckedIOException(unreadable);
                    }
                }), content.length());
        return new ClosingBodyPublisher(streamed, opened);
    }

    /** Closes the source of a streamed request body if it is still open. */
    static void closeSourceOf(HttpRequest prepared) {
        prepared.bodyPublisher().ifPresent(publisher -> {
            if (publisher instanceof ClosingBodyPublisher closing) {
                closing.closeSource();
            }
        });
    }

    /**
     * Closes the source of a streamed body even when the client does not send the body to the end, as the host
     * does. The JDK client closes the stream of {@code ofInputStream} only at its end; it cancels the subscription
     * when the tool answers and closes the connection before reading the body. If the tool keeps the connection
     * open, the client delivers the response only after the whole body, so never, and the request ends at its
     * deadline. Without this wrapper the adapter's source (for example the stream of the Spring facade) would
     * stay open. For the same reason the HTTP path closes it as soon as the response is read completely or the
     * request fails ({@link #closeSourceOf}).
     */
    private static final class ClosingBodyPublisher implements HttpRequest.BodyPublisher {

        /** Marker for "closed"; a source that opens only afterwards is closed again by its opener right away. */
        private static final InputStream CLOSED = InputStream.nullInputStream();

        private final HttpRequest.BodyPublisher delegate;
        private final AtomicReference<InputStream> opened;

        ClosingBodyPublisher(HttpRequest.BodyPublisher delegate, AtomicReference<InputStream> opened) {
            this.delegate = delegate;
            this.opened = opened;
        }

        @Override
        public long contentLength() {
            return delegate.contentLength();
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            delegate.subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscriber.onSubscribe(new Flow.Subscription() {
                        @Override
                        public void request(long n) {
                            subscription.request(n);
                        }

                        @Override
                        public void cancel() {
                            try {
                                subscription.cancel();
                            } finally {
                                closeSource();
                            }
                        }
                    });
                }

                @Override
                public void onNext(ByteBuffer item) {
                    subscriber.onNext(item);
                }

                @Override
                public void onError(Throwable failure) {
                    closeSource();
                    subscriber.onError(failure);
                }

                @Override
                public void onComplete() {
                    closeSource();
                    subscriber.onComplete();
                }
            });
        }

        void closeSource() {
            InputStream source = opened.getAndSet(CLOSED);
            if (source != null && source != CLOSED) {
                try {
                    source.close();
                } catch (IOException | RuntimeException ignored) {
                    //The body is sent or aborted; a failure while closing does not change that
                }
            }
        }
    }
}
