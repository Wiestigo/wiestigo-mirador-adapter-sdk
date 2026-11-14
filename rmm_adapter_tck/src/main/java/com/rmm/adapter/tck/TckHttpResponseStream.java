package com.rmm.adapter.tck;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.rmm.adapter.tck.TckHttpMessages.describe;

/**
 * The body of a response as a stream with backpressure: at most {@value #READ_AHEAD} chunks ahead and an idle
 * timeout between two chunks (shortened to the run's deadline); closing before the end aborts the transfer.
 */
final class TckHttpResponseStream extends InputStream implements HttpResponse.BodySubscriber<InputStream> {

    /** A stream reads at most this many chunks ahead; same as the host. */
    private static final int READ_AHEAD = 4;
    private static final Object END = new Object();

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    /** When the stream was opened ({@link System#nanoTime()}). */
    private final long openedAt = System.nanoTime();
    private final Duration idle;
    private final Instant deadline;
    /** Method and address without query, in front of every message, as for {@code send}. */
    private final String what;
    private volatile Flow.Subscription subscription;
    private volatile boolean cancelled;
    private Iterator<ByteBuffer> pending = List.<ByteBuffer>of().iterator();
    private ByteBuffer current;
    private boolean ended;
    private boolean closed;

    /**
     * @param idle     the longest time between two chunks
     * @param deadline the run's deadline; {@code null} = none
     * @param what     method and address without query, in front of every message
     */
    TckHttpResponseStream(Duration idle, Instant deadline, String what) {
        this.idle = idle;
        this.deadline = deadline;
        this.what = what;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        if (cancelled) {
            subscription.cancel();
            return;
        }
        subscription.request(READ_AHEAD);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        queue.add(buffers);
    }

    @Override
    public void onError(Throwable throwable) {
        queue.add(throwable);
    }

    @Override
    public void onComplete() {
        queue.add(END);
    }

    @Override
    public CompletionStage<InputStream> getBody() {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int n = read(single, 0, 1);
        return n < 0 ? -1 : single[0] & 0xff;
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int count) throws IOException {
        Objects.checkFromIndexSize(offset, count, buffer.length);
        if (closed) {
            throw new IOException(what + "the response stream is closed");
        }
        if (count == 0) {
            return 0;
        }
        while (current == null || !current.hasRemaining()) {
            if (pending.hasNext()) {
                current = pending.next();
                continue;
            }
            if (ended) {
                return -1;
            }
            Object next = poll();
            if (next == END) {
                ended = true;
                return -1;
            }
            if (next instanceof Throwable failure) {
                ended = true;
                throw new IOException(what + describe(failure), failure);
            }
            @SuppressWarnings("unchecked")
            List<ByteBuffer> buffers = (List<ByteBuffer>) next;
            pending = buffers.iterator();
            subscription.request(1);
        }
        int n = Math.min(count, current.remaining());
        current.get(buffer, offset, n);
        return n;
    }

    /**
     * The next chunk, at the latest after the idle timeout or the rest of the run's deadline; after
     * {@link TckAdapterHttp#MAX_STREAM_OPEN} the stream is aborted (as in the host).
     */
    private Object poll() throws IOException {
        long openLeft = TckAdapterHttp.MAX_STREAM_OPEN.toNanos() - (System.nanoTime() - openedAt);
        if (openLeft <= 0) {
            abort();
            throw new IOException(what + "the response stream was open longer than "
                    + describe(TckAdapterHttp.MAX_STREAM_OPEN) + " and was aborted by the host");
        }
        Duration wait = idle.toNanos() > openLeft ? Duration.ofNanos(openLeft) : idle;
        boolean atDeadline = false;
        if (deadline != null) {
            Duration left = Duration.between(Instant.now(), deadline);
            if (left.isZero() || left.isNegative()) {
                abort();
                throw new IOException(what + "the run has reached its deadline");
            }
            if (left.compareTo(wait) < 0) {
                wait = left;
                atDeadline = true;
            }
        }
        Object next;
        try {
            next = queue.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            abort();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException(what + "interrupted while reading the response");
        }
        if (next == null) {
            abort();
            throw new IOException(what + (atDeadline ? "stopped at the deadline of the run after "
                    + describe(wait) : "no data within " + describe(wait)));
        }
        return next;
    }

    private void abort() {
        ended = true;
        cancelled = true;
        Flow.Subscription known = subscription;
        if (known != null) {
            known.cancel();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!ended) {
            abort();
        }
        queue.clear();
    }
}
