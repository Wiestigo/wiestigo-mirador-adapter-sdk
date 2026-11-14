package com.rmm.adapter.api;

import java.io.IOException;

/**
 * The source of the bytes when writing an attachment to a tool
 * ({@link TargetWriter#createAttachment}).
 *
 * <p>A source instead of a stream, because an adapter may need the bytes more
 * than once: for a tool that requires a multi-step upload or when repeating an
 * attempt, it simply opens again. Every call returns a fresh
 * {@link AttachmentContent} starting at the first byte; the caller closes it.
 */
@FunctionalInterface
public interface AttachmentContentSource {

    /** A fresh stream starting at the first byte, with its length. */
    AttachmentContent open() throws IOException, AdapterException;
}
