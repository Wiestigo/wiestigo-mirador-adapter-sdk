package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AttachmentContent;
import com.rmm.adapter.api.ElementFailure;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncRunContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * The result of one read of the attachment phase, with what its pages state,
 * and the checks on it: usable metadata, a completeness statement without
 * contradiction, and byte streams that deliver what they announce.
 */
final class AttachmentRead {

    /** The sample of the byte channel: this many attachments, each only up to this length. */
    private static final int STREAM_SAMPLE = 3;
    private static final long STREAM_SAMPLE_MAX_BYTES = 5L * 1024 * 1024;

    private final List<ElementAttachment> attachments;
    /** The parent elements the pages report as completely read. */
    private final Set<String> completeParents;
    /** The elements the pages report a read failure for. */
    private final Set<String> failed;

    private AttachmentRead(List<ElementAttachment> attachments, Set<String> completeParents, Set<String> failed) {
        this.attachments = attachments;
        this.completeParents = completeParents;
        this.failed = failed;
    }

    /** The attachments read, in order. */
    List<ElementAttachment> attachments() {
        return attachments;
    }

    /**
     * Reads the attachment phase to its end exactly once and collects what
     * the pages state. Once, because the contract promises only one read per
     * phase and run: an adapter may use up its run state while it reads (for
     * example, empty a cache page by page). Checks the cursor rules along the
     * way, as {@link ReadContract} does.
     */
    static AttachmentRead once(SourceReader reader, SyncRunContext runContext, IntSupplier maxPages)
            throws AdapterException {
        List<ElementAttachment> attachments = new ArrayList<>();
        Set<String> completeParents = new LinkedHashSet<>();
        Set<String> failed = new HashSet<>();
        Set<String> seenCursors = new HashSet<>();
        PageCursor cursor = null;
        int pages = 0;
        while (true) {
            SourcePage<ElementAttachment> page = reader.readAttachments(runContext, ReadScope.full(), cursor);
            if (page == null) {
                throw new TckViolation("readAttachments: the reader returned null instead of a SourcePage"
                        + " — return SourcePage.empty() for a run without attachments");
            }
            pages++;
            attachments.addAll(page.elements());
            page.failures().stream().map(ElementFailure::externalId).filter(Objects::nonNull).forEach(failed::add);
            for (String parent : page.completeParentIds()) {
                if (parent == null || parent.isBlank()) {
                    throw new TckViolation("readAttachments: completeParentIds carries a blank entry — Mirador"
                            + " would reconcile the attachments of a nameless parent and delete what it holds");
                }
                completeParents.add(parent);
            }
            if (page.lastPage()) {
                return new AttachmentRead(List.copyOf(attachments), completeParents, failed);
            }
            if (!seenCursors.add(page.nextCursor().value())) {
                throw new TckViolation("readAttachments: cursor value <" + page.nextCursor().value()
                        + "> was returned twice — the reader would loop forever");
            }
            if (pages >= maxPages.getAsInt()) {
                throw new TckViolation("readAttachments: no lastPage after " + maxPages.getAsInt()
                        + " pages — the reader does not terminate");
            }
            cursor = page.nextCursor();
        }
    }

    /**
     * Every attachment carries the metadata the sync core works with (its
     * own id, unique in the read, its parent element and a file name), and
     * no parent element with a read failure counts as completely read.
     */
    void requireUsableMetadata() {
        Set<String> ids = new HashSet<>();
        for (ElementAttachment attachment : attachments) {
            String id = attachment.getIdValue();
            if (id == null || id.isBlank()) {
                throw new TckViolation("readAttachments delivered an attachment without an id ("
                        + attachment.getFileName() + ") — Mirador stores it as the external reference"
                        + " and would fetch the file again on every run");
            }
            if (!ids.add(id)) {
                throw new TckViolation("readAttachments delivered the id <" + id + "> twice — two"
                        + " attachments would collapse into one in Mirador");
            }
            if (attachment.getParentIdValue() == null || attachment.getParentIdValue().isBlank()) {
                throw new TckViolation("The attachment <" + id + "> names no parent element — Mirador"
                        + " could not tell which artifact the file belongs to");
            }
            if (attachment.getFileName() == null || attachment.getFileName().isBlank()) {
                throw new TckViolation("The attachment <" + id + "> carries no file name");
            }
        }
        for (String parent : completeParents) {
            if (failed.contains(parent)) {
                throw new TckViolation("The element <" + parent + "> is reported both as read failure and"
                        + " as completely read (completeParentIds) — Mirador would delete the attachments"
                        + " the adapter merely failed to read");
            }
        }
    }

    /**
     * The byte channel keeps its announcement: {@code openAttachment}
     * delivers exactly the announced number of bytes, checked on a sample of
     * up to {@value #STREAM_SAMPLE} attachments of limited length.
     */
    void requireExactStreams(SourceReader reader, SyncRunContext runContext) throws AdapterException, IOException {
        int checked = 0;
        for (ElementAttachment attachment : attachments) {
            if (checked >= STREAM_SAMPLE) {
                break;
            }
            //The length in the metadata is only the cheap pre-filter: it may be
            //missing (-1) or wrong; what counts is what the channel announces
            if (attachment.getByteCount() > STREAM_SAMPLE_MAX_BYTES) {
                continue;
            }
            try (AttachmentContent content = reader.openAttachment(runContext, attachment.getIdValue())) {
                if (content == null) {
                    throw new TckViolation("openAttachment(<" + attachment.getIdValue() + ">) returned null"
                            + " — report a missing file as an AdapterException, never as no content");
                }
                long announced = content.byteCount();
                if (announced > STREAM_SAMPLE_MAX_BYTES) {
                    //Too large for a sample: close without reading
                    //(this is what AttachmentContent is closeable for)
                    continue;
                }
                checked++;
                //One byte more than announced: enough to detect "too long",
                //and it keeps a stream that never ends out of memory
                long delivered = content.stream().readNBytes((int) announced + 1).length;
                if (delivered != announced) {
                    throw new TckViolation("openAttachment(<" + attachment.getIdValue() + ">) announced "
                            + announced + " bytes but delivered "
                            + (delivered > announced ? "more" : String.valueOf(delivered))
                            + " — Mirador announces the length to the object store before the first byte");
                }
                if (attachment.getByteCount() >= 0 && attachment.getByteCount() != delivered) {
                    throw new TckViolation("The attachment <" + attachment.getIdValue() + "> is read as "
                            + attachment.getByteCount() + " bytes but its stream delivers " + delivered
                            + " — Mirador skips re-fetching by length and timestamp and would keep the"
                            + " wrong file");
                }
            }
        }
    }
}
