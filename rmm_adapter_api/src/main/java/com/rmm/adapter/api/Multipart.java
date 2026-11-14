package com.rmm.adapter.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Helper for {@code multipart/form-data} (RFC 7578): fields and files become a
 * {@link Body} with a known length, without buffering the files.
 *
 * <pre>{@code
 * Body upload = Multipart.builder()
 *         .field("comment", "Attachment from Mirador")
 *         .file("file", "spec.pdf", Body.ofStream(length, "application/pdf", content::open))
 *         .build();
 * }</pre>
 *
 * <p>The body is replayable only if all parts are; with a stream part it is,
 * like that part, readable only once. It always counts as an upload
 * ({@link Body}): the host never retries it, even if it consists of bytes only.
 * The boundary between the parts is random; the body carries the media type
 * that includes it.
 */
public final class Multipart {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private Multipart() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Collects the parts in their order. */
    public static final class Builder {

        private final List<Object> segments = new ArrayList<>();
        private final String boundary;
        private boolean replayable = true;
        private int parts;

        private Builder() {
            byte[] random = new byte[16];
            RANDOM.nextBytes(random);
            this.boundary = "rmm-" + HexFormat.of().formatHex(random);
        }

        /** A text field, sent in UTF-8. */
        public Builder field(String name, String value) {
            Objects.requireNonNull(value, "value");
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + quoted(name, "field name") + "\"\r\n\r\n";
            segments.add(utf8(head));
            segments.add(utf8(value));
            segments.add(CRLF);
            parts++;
            return this;
        }

        /**
         * A file. Its media type is that of the body, otherwise
         * {@code application/octet-stream}.
         */
        public Builder file(String name, String filename, Body content) {
            Objects.requireNonNull(content, "content");
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + quoted(name, "field name")
                    + "\"; filename=\"" + quoted(filename, "file name") + "\"\r\n"
                    + "Content-Type: " + content.contentType().orElse("application/octet-stream") + "\r\n\r\n";
            segments.add(utf8(head));
            segments.add(content);
            segments.add(CRLF);
            replayable &= content.replayable();
            parts++;
            return this;
        }

        /** The finished body; needs at least one part. */
        public Body build() {
            if (parts == 0) {
                throw new IllegalStateException("multipart body needs at least one part");
            }
            List<Object> all = new ArrayList<>(segments);
            all.add(utf8("--" + boundary + "--\r\n"));
            long length = 0;
            for (Object segment : all) {
                length = Math.addExact(length, segment instanceof Body body ? body.length() : ((byte[]) segment).length);
            }
            List<Object> frozen = List.copyOf(all);
            // Always an upload: never retried, even if built from bytes only
            return new Body(length, "multipart/form-data; boundary=" + boundary, replayable, true,
                    () -> new SegmentStream(frozen));
        }

        /** Encodes quotes and line breaks as browsers do (HTML, RFC 7578 4.2). */
        private static String quoted(String value, String what) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(what + " must not be empty");
            }
            return value.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
        }

        private static byte[] utf8(String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Reads the parts one after another; a file part is opened only when its turn comes. */
    private static final class SegmentStream extends InputStream {

        private final List<Object> segments;
        private int next;
        private InputStream current;

        SegmentStream(List<Object> segments) {
            this.segments = segments;
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
            while (true) {
                if (current == null) {
                    if (next == segments.size()) {
                        return -1;
                    }
                    Object segment = segments.get(next++);
                    current = segment instanceof Body body ? body.open() : new ByteArrayInputStream((byte[]) segment);
                }
                int n = current.read(buffer, offset, count);
                if (n >= 0) {
                    return n;
                }
                current.close();
                current = null;
            }
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                current.close();
                current = null;
            }
            next = segments.size();
        }
    }
}
