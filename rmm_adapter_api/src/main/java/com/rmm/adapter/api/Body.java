package com.rmm.adapter.api;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The body of an {@link AdapterRequest}, with a known length.
 *
 * <ul>
 *   <li><b>Bytes</b> ({@link #ofBytes}, {@link #ofString}, {@link #ofJson}):
 *       held in memory and {@link #replayable()}; only these can be sent again
 *       for a retry.</li>
 *   <li><b>Stream</b> ({@link #ofStream}): for uploads of gigabyte size
 *       without buffering, <b>readable only once</b>. The host opens the
 *       source only when it actually sends; a second {@link #open()}
 *       fails.</li>
 * </ul>
 *
 * <p><b>Upload:</b> a stream body and every body from {@link Multipart} is an
 * upload. A request with an upload is never retried and cannot be marked
 * idempotent, even if its method would be (PUT of a file). No request with a
 * body follows a redirect ({@link RedirectPolicy}).
 *
 * <p>The length is binding: the host announces it as {@code Content-Length},
 * and reading fails with an {@link IOException} if the source delivers fewer
 * or more bytes, instead of sending a truncated or overlong request.
 */
public final class Body {

    /** Opens the bytes of a stream body, starting at the first byte. */
    @FunctionalInterface
    public interface Source {
        InputStream open() throws IOException;
    }

    private final long length;
    private final String contentType;
    private final boolean replayable;
    private final boolean upload;
    private final Source source;
    private final AtomicBoolean opened = new AtomicBoolean();

    Body(long length, String contentType, boolean replayable, boolean upload, Source source) {
        if (length < 0) {
            throw new IllegalArgumentException("body length must not be negative: " + length);
        }
        if (contentType != null && (contentType.isBlank() || !HttpRules.isHeaderValue(contentType))) {
            throw new IllegalArgumentException("content type must not be blank or contain control characters");
        }
        this.length = length;
        this.contentType = contentType;
        this.replayable = replayable;
        this.upload = upload;
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Bytes, readable any number of times. The array is copied; later changes
     * by the caller have no effect.
     *
     * @param contentType media type; {@code null} = none
     */
    public static Body ofBytes(byte[] bytes, String contentType) {
        Objects.requireNonNull(bytes, "bytes");
        byte[] copy = bytes.clone();
        return new Body(copy.length, contentType, true, false, () -> new ByteArrayInputStream(copy));
    }

    /** Text in UTF-8, readable any number of times. */
    public static Body ofString(String text, String contentType) {
        Objects.requireNonNull(text, "text");
        return ofBytes(text.getBytes(StandardCharsets.UTF_8), contentType);
    }

    /** JSON in UTF-8 with {@code application/json}, readable any number of times. */
    public static Body ofJson(String json) {
        return ofString(json, "application/json");
    }

    /**
     * A stream with a known length, readable only once.
     *
     * <p>The source must return from {@code read()} within the host's stream
     * limit or react to interruption or closing. If the host aborts an upload,
     * it closes the source from another thread, possibly in the middle of a
     * {@code read()}; {@code close()} must therefore not wait for a running
     * {@code read()} (for example on the same monitor). A source that does not
     * comply occupies a thread of the binding until its read returns. If the
     * binding is released while the body is being sent, the upload fails
     * immediately, as transient ({@link AdapterException#isRetryable()}).
     *
     * @param length      number of bytes the source delivers (not negative).
     *                    The stream must end there: after the last byte the
     *                    host reads once more to see that nothing follows, so
     *                    a stream that stays open without ending blocks the
     *                    upload until its limit
     * @param contentType media type; {@code null} = none
     * @param source      opens the stream; the host calls it at most once
     */
    public static Body ofStream(long length, String contentType, Source source) {
        return new Body(length, contentType, false, true, Objects.requireNonNull(source, "source"));
    }

    /** Length in bytes, announced as {@code Content-Length}. */
    public long length() {
        return length;
    }

    /** Media type, sent as {@code Content-Type}. */
    public Optional<String> contentType() {
        return Optional.ofNullable(contentType);
    }

    /** Whether the body can be read more than once. */
    public boolean replayable() {
        return replayable;
    }

    /** Whether the body is an upload (stream or {@link Multipart}); see the class description. */
    boolean upload() {
        return upload;
    }

    /**
     * Opens the bytes, starting at the first. A body that is not replayable
     * can be opened only once; a second call throws
     * {@link IllegalStateException}. The stream checks the announced length.
     */
    public InputStream open() throws IOException {
        if (!replayable && !opened.compareAndSet(false, true)) {
            throw new IllegalStateException("body has already been read — a streamed body can be read only once");
        }
        InputStream stream = source.open();
        if (stream == null) {
            throw new IOException("body source returned no stream");
        }
        return new LengthCheckedStream(stream, length);
    }

    @Override
    public String toString() {
        return "Body[length=" + length + ", contentType=" + contentType + ", replayable=" + replayable
                + ", upload=" + upload + "]";
    }

    /** Delivers exactly {@code expected} bytes or fails. */
    private static final class LengthCheckedStream extends FilterInputStream {

        private final long expected;
        private long read;

        LengthCheckedStream(InputStream in, long expected) {
            super(in);
            this.expected = expected;
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
            if (read == expected) {
                // At the announced end: if the source delivers more, it is too long
                if (in.read() >= 0) {
                    throw new IOException("body is longer than its declared length " + expected);
                }
                return -1;
            }
            int n = in.read(buffer, offset, (int) Math.min(count, expected - read));
            if (n < 0) {
                throw new IOException("body ended after " + read + " of " + expected + " declared bytes");
            }
            read += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = 0;
            byte[] buffer = new byte[8192];
            while (skipped < n) {
                int chunk = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                if (chunk < 0) {
                    break;
                }
                skipped += chunk;
            }
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), expected - read);
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
