package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.database_schema.element.Element;
import com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementField;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.ElementFailure;
import com.rmm.adapter.api.ElementFeatures;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * A complete read of a binding in comparable form (TCK cases 1, 4 and 5):
 * every declared read phase until {@code lastPage}, in the sync core's
 * order, as lines of identifier, fields, references, element failures and
 * page count. Two readings are equal if the binding delivered the same.
 * This is how the TCK compares a binding with itself under other conditions
 * (in parallel, bound again, with traps).
 *
 * <p>Every reading is a run of its own (new {@code runId}): readers may keep
 * state per run and consume it while reading.</p>
 *
 * @param lines the lines of the reading, with one header line per phase
 */
public record ToolReading(List<String> lines) {

    /** How the line of an artifact begins; its identifier follows ({@link #signature}). */
    private static final String ARTIFACT_PREFIX = "ElementArtifact ";
    /** What follows the identifier in the line of an element. */
    private static final String NAME_PART = " name=";

    public ToolReading {
        lines = List.copyOf(lines);
    }

    /**
     * Reads all declared phases of the binding in the project
     * {@code externalProjectId}. {@link ReadContract} checks the cursor
     * semantics along the way.
     */
    public static ToolReading of(SourceReader reader, AdapterCapabilities capabilities, String externalProjectId,
                                 int maxPages) throws AdapterException {
        return read(reader, capabilities, new SyncRunContext(UUID.randomUUID(), externalProjectId), maxPages);
    }

    /**
     * Like {@link #of(SourceReader, AdapterCapabilities, String, int)}, but
     * as a run of the connection if {@code context} is a
     * {@link TckAdapterContext} ({@link TckAdapterContext#inRun}): the hooks
     * then have {@code runStore()}, {@code executor().submit} and the run in
     * {@code http()} as in the host. At the end the run has ended and its
     * run store is empty. The context fetches the pages the way its host
     * does ({@link ContractContext#paging()}).
     */
    public static ToolReading of(AdapterContext context, SourceReader reader, AdapterCapabilities capabilities,
                                 String externalProjectId, int maxPages) throws AdapterException {
        SyncRunContext run = new SyncRunContext(UUID.randomUUID(), externalProjectId);
        ReadContract.Paging paging = context instanceof ContractContext contract ? contract.paging()
                : ReadContract.Paging.sequential();
        try {
            return TckAdapterContext.runIn(context, run, () -> read(paging, reader, capabilities, run, maxPages));
        } catch (AdapterException | RuntimeException | Error failed) {
            throw failed;
        } catch (Exception other) {
            throw new IllegalStateException(other);
        }
    }

    /** Reads all declared phases in the run {@code run}. */
    static ToolReading read(SourceReader reader, AdapterCapabilities capabilities, SyncRunContext run, int maxPages)
            throws AdapterException {
        return read(ReadContract.Paging.sequential(), reader, capabilities, run, maxPages);
    }

    /** As above, with the pages from {@code paging}. */
    static ToolReading read(ReadContract.Paging paging, SourceReader reader, AdapterCapabilities capabilities,
                            SyncRunContext run, int maxPages) throws AdapterException {
        List<String> lines = new ArrayList<>();
        add(lines, "artifacts", ReadContract.readAll(paging, "readArtifacts", reader::readArtifacts, run,
                ReadScope.full(), maxPages));
        if (capabilities.reads(SyncPhase.RELATIONSHIPS)) {
            add(lines, "relationships", ReadContract.readAll(paging, "readRelationships", reader::readRelationships,
                    run, ReadScope.full(), maxPages));
        }
        if (capabilities.reads(SyncPhase.COMMENTS)) {
            add(lines, "comments", ReadContract.readAll(paging, "readComments", reader::readComments, run,
                    ReadScope.full(), maxPages));
        }
        if (capabilities.reads(SyncPhase.ATTACHMENTS)) {
            add(lines, "attachments", ReadContract.readAll(paging, "readAttachments", reader::readAttachments, run,
                    ReadScope.full(), maxPages));
        }
        if (capabilities.reads(SyncPhase.VARIABILITY)) {
            add(lines, "element features", ReadContract.readAll(paging, "readElementFeatures",
                    reader::readElementFeatures, run, ReadScope.full(), maxPages));
        }
        return new ToolReading(lines);
    }

    /**
     * The external identifiers of the artifacts read. An identifier may
     * contain spaces; it ends where the name of the element begins in its
     * line ({@link #signature}).
     */
    public List<String> artifactIds() {
        List<String> ids = new ArrayList<>();
        boolean inArtifacts = false;
        for (String line : lines) {
            if (line.startsWith("# ")) {
                inArtifacts = line.startsWith("# artifacts");
            } else if (inArtifacts && line.startsWith(ARTIFACT_PREFIX)) {
                int end = line.indexOf(NAME_PART, ARTIFACT_PREFIX.length());
                ids.add(line.substring(ARTIFACT_PREFIX.length(), end < 0 ? line.length() : end));
            }
        }
        return ids;
    }

    /**
     * Only the elements and element failures, without header lines and page
     * counts: the data set the binding reaches. Two bindings to different
     * tools differ here, whatever their page size.
     */
    public List<String> elements() {
        return lines.stream().filter(line -> !line.startsWith("# ")).toList();
    }

    /** The reading as one text, for searching values that do not belong here. */
    public String text() {
        return String.join("\n", lines);
    }

    @Override
    public String toString() {
        return text();
    }

    /**
     * Adds one phase: a header line with the page count, then elements and
     * element failures, sorted. Sorting avoids a false alarm for a tool
     * without a fixed order (a search without sorting).
     */
    private static <T> void add(List<String> lines, String phase, ReadContract.PhaseResult<T> result) {
        lines.add("# " + phase + " (" + result.pages() + " pages)");
        List<String> read = new ArrayList<>();
        result.elements().forEach(element -> read.add(signature(element)));
        for (ElementFailure failure : result.failures()) {
            read.add("failure " + failure.externalId() + ": " + failure.message());
        }
        read.sort(null);
        lines.addAll(read);
    }

    /** One line per element: what the sync core takes from it, without object identity. */
    static String signature(Object element) {
        if (element instanceof ElementFeatures features) {
            return "ElementFeatures " + features.externalId() + " " + features.featureNames();
        }
        if (!(element instanceof Element read)) {
            return String.valueOf(element);
        }
        StringBuilder line = new StringBuilder(read.getClass().getSimpleName())
                .append(' ').append(read.getIdValue())
                .append(NAME_PART).append(read.getNameValue())
                .append(" label=").append(read.getLabelValue());
        if (read instanceof ElementRelationship relationship) {
            line.append(" from=").append(relationship.getFromItemIdValue())
                    .append(" to=").append(relationship.getToItemIdValue());
        }
        if (read instanceof ElementComment comment) {
            line.append(" to=").append(comment.getCommentToItemIdValue());
        }
        if (read instanceof ElementAttachment attachment) {
            line.append(" parent=").append(attachment.getParentIdValue())
                    .append(" file=").append(attachment.getFileName())
                    .append(" bytes=").append(attachment.getByteCount())
                    .append(" type=").append(attachment.getContentType());
        }
        if (read.getFields() != null) {
            read.getFields().values().stream()
                    .sorted(Comparator.comparing(field -> String.valueOf(field.getLabelValue())))
                    .forEach(field -> line.append(" | ").append(field.getLabelValue()).append('=')
                            .append(valueOf(field)));
        }
        return line.toString();
    }

    private static String valueOf(ElementField<?> field) {
        try {
            return field.getValueAsString();
        } catch (RuntimeException unreadable) {
            return "<" + unreadable.getClass().getSimpleName() + ">";
        }
    }
}
