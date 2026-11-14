package com.rmm.adapter.http.spring;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The body of a request as a blocking stream for
 * {@link com.rmm.adapter.api.Body#ofStream}. It subscribes to the body from
 * Spring only on the first read and always requests just one chunk ahead
 * (backpressure), so the whole body never sits in memory, and the source
 * produces only what the host really sends.
 *
 * <p>Closing unsubscribes; a waiting {@code read} then ends with an
 * {@link IOException}, an interrupted one with an
 * {@link InterruptedIOException}. The host thread that reads never hangs: if
 * no chunk arrives for longer than {@code wait}, the read ends with an
 * {@link IOException}; if the request fails or is cancelled, the facade
 * closes the stream ({@code AdapterClientHttpRequest#closeStreams}),
 * otherwise the host does once it has sent it. Closing from another thread
 * does not touch the chunk that is currently being read; the reading thread
 * releases it on its next {@code read} or {@code close}.</p>
 */
final class PublisherInputStream extends InputStream implements CoreSubscriber<DataBuffer> {

    private static final Object COMPLETE = new Object();
    private static final Object CLOSED = new Object();

    private final Flux<DataBuffer> source;
    private final Context context;
    private final Duration wait;
    private final BlockingQueue<Object> signals = new LinkedBlockingQueue<>();
    private volatile Subscription subscription;
    private volatile boolean closed;
    private volatile Thread reader;
    private boolean subscribed;
    private boolean finished;
    private DataBuffer current;

    PublisherInputStream(Publisher<? extends DataBuffer> source, ContextView context, Duration wait) {
        this.source = Flux.from(source);
        this.context = Context.of(context);
        this.wait = wait;
    }

    @Override
    public Context currentContext() {
        return context;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        if (closed) {
            subscription.cancel();
        } else {
            subscription.request(1);
        }
    }

    @Override
    public void onNext(DataBuffer buffer) {
        if (closed) {
            DataBufferUtils.release(buffer);
            return;
        }
        signals.add(buffer);
        //close() may have drained the queue between the check and add
        if (closed && signals.remove(buffer)) {
            DataBufferUtils.release(buffer);
        }
    }

    @Override
    public void onError(Throwable failure) {
        signals.add(new Failure(failure));
    }

    @Override
    public void onComplete() {
        signals.add(COMPLETE);
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int n = read(single, 0, 1);
        return n < 0 ? -1 : single[0] & 0xff;
    }

    @Override
    public int read(byte[] buffer, int offset, int count) throws IOException {
        if (count == 0) {
            return 0;
        }
        if (closed) {
            releaseCurrent();
            throw new IOException("request body stream is closed");
        }
        reader = Thread.currentThread();
        if (!subscribed) {
            subscribed = true;
            source.subscribe(this);
        }
        while (current == null || current.readableByteCount() == 0) {
            if (current != null) {
                DataBufferUtils.release(current);
                current = null;
            }
            if (finished) {
                return -1;
            }
            Object signal = next();
            if (signal == COMPLETE) {
                finished = true;
                return -1;
            }
            if (signal == CLOSED) {
                throw new IOException("request body stream is closed");
            }
            if (signal instanceof Failure failure) {
                finished = true;
                throw new IOException("request body failed: " + failure.cause().getMessage(), failure.cause());
            }
            current = (DataBuffer) signal;
            //Request the next chunk while this one is being read
            subscription.request(1);
        }
        int n = Math.min(count, current.readableByteCount());
        current.read(buffer, offset, n);
        return n;
    }

    private Object next() throws IOException {
        try {
            Object signal = signals.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
            if (signal == null) {
                close();
                throw new IOException("request body produced nothing for " + wait.toMillis() + " ms");
            }
            return signal;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            close();
            throw new InterruptedIOException("interrupted while waiting for the request body");
        }
    }

    @Override
    public void close() {
        if (Thread.currentThread() == reader) {
            releaseCurrent();
        }
        if (closed) {
            return;
        }
        closed = true;
        Subscription active = subscription;
        if (active != null) {
            active.cancel();
        }
        for (Object signal = signals.poll(); signal != null; signal = signals.poll()) {
            if (signal instanceof DataBuffer buffer) {
                DataBufferUtils.release(buffer);
            }
        }
        //Wakes a waiting read; onNext releases chunks that arrive afterwards
        signals.add(CLOSED);
    }

    private void releaseCurrent() {
        if (current != null) {
            DataBufferUtils.release(current);
            current = null;
        }
    }

    private record Failure(Throwable cause) {
    }
}
