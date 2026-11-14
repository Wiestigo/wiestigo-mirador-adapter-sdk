package com.rmm.adapter.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * The bytes of an attachment as a stream with its length: the byte channel of
 * the attachment contract.
 *
 * <p>Not a byte array: an attachment may be as large as the host's attachment
 * limit (1 GiB by default, see {@link HostLimits#maxAttachmentBytes()}), and it
 * should stream through adapter and core instead of sitting in memory. The
 * length travels with it because the core needs it before the first byte: it is
 * checked against the limit and announced to the object store.
 *
 * <p>Whoever opens the content closes it: {@code try (AttachmentContent
 * content = reader.openAttachment(...))}. Closing closes the stream, even if
 * the core has not read it to the end (for example because the length exceeds
 * the limit).
 *
 * @param stream      the bytes, starting at the first
 * @param byteCount   the total length in bytes (not negative)
 * @param contentType the media type as the tool states it; null if the tool
 *                    has none. The core determines the type from the bytes
 *                    anyway.
 */
public record AttachmentContent(InputStream stream, long byteCount, String contentType) implements AutoCloseable {

    public AttachmentContent {
        Objects.requireNonNull(stream, "stream");
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must not be negative: " + byteCount);
        }
    }

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
