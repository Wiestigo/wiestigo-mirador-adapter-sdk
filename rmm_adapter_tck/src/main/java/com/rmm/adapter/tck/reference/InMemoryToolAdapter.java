package com.rmm.adapter.tck.reference;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.database_schema.element.Element;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AttachmentContent;
import com.rmm.adapter.api.AttachmentContentSource;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The TCK's in-memory reference adapter: a complete, runnable implementation
 * of the adapter contract against a data set in memory. It covers both sync
 * directions, an offset cursor and delta reads based on a modification time
 * per element.
 *
 * <p>It serves two purposes: the TCK tests itself against it (a correct
 * adapter must pass every TCK case), and adapter authors can read here what
 * a minimal implementation that honors the contract looks like, without the
 * overhead of a real tool API.</p>
 */
public final class InMemoryToolAdapter implements ToolAdapter {

    /** The tool used without {@code tool.name}; the {@code seed…} methods fill it. */
    public static final String DEFAULT_TOOL = "memtool";

    /** A stored artifact with its modification time (the basis for delta reads). */
    private record StoredArtifact(ElementArtifact artifact, Instant modifiedAt) {
    }

    /**
     * An attachment with metadata and bytes kept apart, as in the contract:
     * the pages carry only the metadata, and the host fetches the bytes one
     * attachment at a time through {@code openAttachment}.
     */
    private record StoredAttachment(ElementAttachment attachment, byte[] bytes) {
    }

    /**
     * The fake tools by name. They are the outside world of this adapter,
     * like servers on the network, not state of a binding. A binding reaches
     * only the tool of its configuration ({@code tool.name}), just as a real
     * adapter reaches only the address of its connection (TCK case 1: two
     * bindings with different configuration share no tool).
     */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    @Override
    public AdapterDescriptor descriptor() {
        return new AdapterDescriptor("MEMTOOL", "In-Memory Reference Tool", "1.0.0",
                AdapterApiVersion.MAJOR,
                new AdapterCapabilities(
                        Set.of(SyncDirection.TO_RMM, SyncDirection.FROM_RMM),
                        HierarchyModel.FLAT,
                        false, false,
                        //Partial update: an update merges into the stored fields;
                        //fields that are not sent remain (PATCH semantics)
                        true,
                        //Delta read: the data set has a modification time per element
                        true,
                        //All phases in both directions, including attachments
                        //and variability, so that the TCK's self-test reaches
                        //every check
                        SyncPhase.allWithVariability(),
                        SyncPhase.allWithVariability(),
                        //createArtifacts is overridden, so the capability is
                        //declared (the TCK checks that both agree)
                        Set.of(com.rmm.adapter.api.BulkOperation.ARTIFACT_CREATE), null),
                List.of(ConfigKey.optional("page.size", ConfigType.INTEGER, "2",
                                "Page size for reading — small, so that tests see several pages."),
                        ConfigKey.optional("tool.name", ConfigType.STRING, DEFAULT_TOOL,
                                "The fake tool the binding reaches — like the address of a real tool."),
                        ConfigKey.optional("tool.space", ConfigType.STRING, "",
                                "Project space or account in the tool — like two accounts at the same address;"
                                        + " empty: the data set of the tool itself.")),
                List.of());
    }

    @Override
    public AdapterInstance bind(AdapterContext context) {
        int pageSize = Math.max(1, context.config().integer("page.size"));
        Tool tool = tool(toolKey(context.config().string("tool.name"), context.config().string("tool.space")));
        Tool.InMemoryReader reader = tool.new InMemoryReader(pageSize);
        Tool.InMemoryWriter writer = tool.new InMemoryWriter();
        return new AdapterInstance() {
            @Override
            public Optional<SourceReader> reader() {
                return Optional.of(reader);
            }

            @Override
            public Optional<TargetWriter> writer() {
                return Optional.of(writer);
            }
        };
    }

    /**
     * The name under which a project space of a tool is seeded:
     * {@code tool/space}, or the tool's name if there is no space.
     */
    public static String toolKey(String toolName, String space) {
        return space == null || space.isBlank() ? toolName : toolName + "/" + space.trim();
    }

    private Tool tool(String name) {
        return tools.computeIfAbsent(name, unknown -> new Tool());
    }

    /** Seeds an artifact in the tool {@link #DEFAULT_TOOL}; the element's id is its key. */
    public void seedArtifact(ElementArtifact artifact, Instant modifiedAt) {
        seedArtifact(DEFAULT_TOOL, artifact, modifiedAt);
    }

    /** Seeds an artifact in a named tool (for a second binding, {@code tool.name}). */
    public void seedArtifact(String toolName, ElementArtifact artifact, Instant modifiedAt) {
        tool(toolName).artifacts.put(artifact.getIdValue(), new StoredArtifact(artifact, modifiedAt));
    }

    /** Seeds a deletion that a delta read since an earlier time must report. */
    public void seedDeletion(String externalId, Instant deletedAt) {
        Tool tool = tool(DEFAULT_TOOL);
        tool.artifacts.remove(externalId);
        tool.deletedArtifacts.put(externalId, deletedAt);
    }

    public void seedRelationship(ElementRelationship relationship) {
        seedRelationship(DEFAULT_TOOL, relationship);
    }

    /** Seeds a relationship in a named tool. */
    public void seedRelationship(String toolName, ElementRelationship relationship) {
        tool(toolName).relationships.put(relationship.getIdValue(), relationship);
    }

    public void seedComment(ElementComment comment) {
        seedComment(DEFAULT_TOOL, comment);
    }

    /** Seeds a comment in a named tool. */
    public void seedComment(String toolName, ElementComment comment) {
        tool(toolName).comments.put(comment.getIdValue(), comment);
    }

    /**
     * Seeds an attachment with its bytes. The byte count is set from the
     * bytes: a tool that announces another length than it delivers is
     * exactly the error the contract rules out.
     */
    public void seedAttachment(ElementAttachment attachment, byte[] bytes) {
        attachment.setByteCount(bytes.length);
        tool(DEFAULT_TOOL).attachments.put(attachment.getIdValue(), new StoredAttachment(attachment, bytes.clone()));
    }

    /** Seeds the feature selection of an element as a tool would deliver it (qualified names). */
    public void seedElementFeatures(String externalId, List<String> featureNames) {
        tool(DEFAULT_TOOL).elementFeatures.put(externalId, List.copyOf(featureNames));
    }

    /** The mirrored feature trees, for tests of the write path. */
    public Map<String, com.rmm.adapter.api.VariabilityTree> featureTrees() {
        return java.util.Collections.unmodifiableMap(tool(DEFAULT_TOOL).featureTrees);
    }

    public Map<String, com.rmm.adapter.api.VariabilityVariant> variants() {
        return java.util.Collections.unmodifiableMap(tool(DEFAULT_TOOL).variants);
    }

    public Map<String, List<String>> elementFeatures() {
        return java.util.Collections.unmodifiableMap(tool(DEFAULT_TOOL).elementFeatures);
    }

    /**
     * One fake tool: artifacts, deletion feed, relationships, comments,
     * attachments and variability in memory. The reader and writer of a
     * binding work on exactly one tool. Limit: the maps are not
     * synchronized, so two bindings writing to the same tool at once are
     * not supported. An unknown name silently creates an empty tool.
     */
    private static final class Tool {

        private final Map<String, StoredArtifact> artifacts = new LinkedHashMap<>();
        /** Deletion feed: when each artifact was removed; a delta read reports these. */
        private final Map<String, Instant> deletedArtifacts = new LinkedHashMap<>();
        private final Map<String, ElementRelationship> relationships = new LinkedHashMap<>();
        private final Map<String, ElementComment> comments = new LinkedHashMap<>();
        private final Map<String, StoredAttachment> attachments = new LinkedHashMap<>();
        /**
         * Variability: trees and variants as the mirrored product
         * definition, and the selection per element under its identifier.
         * An empty list means "no selection", a missing entry "never
         * written".
         */
        private final Map<String, com.rmm.adapter.api.VariabilityTree> featureTrees = new LinkedHashMap<>();
        private final Map<String, com.rmm.adapter.api.VariabilityVariant> variants = new LinkedHashMap<>();
        private final Map<String, List<String>> elementFeatures = new LinkedHashMap<>();
        private final AtomicLong idSequence = new AtomicLong();

        // ------------------------------------------------------------------
        // Read contract: offset cursor, delta by modifiedAt
        // ------------------------------------------------------------------

        private final class InMemoryReader implements SourceReader {

            private final int pageSize;

            private InMemoryReader(int pageSize) {
                this.pageSize = pageSize;
            }

            @Override
            public SourcePage<ElementArtifact> readArtifacts(SyncRunContext context, ReadScope scope,
                                                             PageCursor cursor) throws AdapterException {
                List<ElementArtifact> inScope = artifacts.values().stream()
                        .filter(stored -> !scope.isDelta() || !stored.modifiedAt().isBefore(scope.changedSince()))
                        //Explicit scope: exactly the requested identifiers
                        .filter(stored -> !scope.isExplicit() || scope.elementIds().contains(stored.artifact().getIdValue()))
                        .map(StoredArtifact::artifact)
                        .toList();
                //Deletion feed: in a delta run, the identifiers removed since then, on the last page
                List<String> deletedSince = !scope.isDelta() ? List.of() : deletedArtifacts.entrySet().stream()
                        .filter(entry -> !entry.getValue().isBefore(scope.changedSince()))
                        .map(Map.Entry::getKey)
                        .toList();
                return pageOf(inScope, cursor, deletedSince);
            }

            @Override
            public SourcePage<ElementRelationship> readRelationships(SyncRunContext context, ReadScope scope,
                                                                     PageCursor cursor) throws AdapterException {
                return pageOf(List.copyOf(relationships.values()), cursor);
            }

            @Override
            public SourcePage<ElementComment> readComments(SyncRunContext context, ReadScope scope,
                                                           PageCursor cursor) throws AdapterException {
                SourcePage<ElementComment> page = pageOf(List.copyOf(comments.values()), cursor);
                if (!page.lastPage()) {
                    return page;
                }
                //The data set delivers all comments: with the last page, the
                //comments of every artifact have been read completely
                return new SourcePage<>(page.elements(), page.failures(), page.readCount(), null, true,
                        page.deletedIds(), List.copyOf(artifacts.keySet()));
            }

            /**
             * Reads attachment metadata page by page. The last page declares
             * the attachments of every artifact as completely read, which
             * this adapter may promise because it delivers everything. The
             * host uses that to detect attachments deleted in the tool.
             */
            @Override
            public SourcePage<ElementAttachment> readAttachments(SyncRunContext context, ReadScope scope,
                                                                 PageCursor cursor) throws AdapterException {
                List<ElementAttachment> all = attachments.values().stream()
                        .map(StoredAttachment::attachment).toList();
                SourcePage<ElementAttachment> page = pageOf(all, cursor);
                if (!page.lastPage()) {
                    return page;
                }
                return new SourcePage<>(page.elements(), page.failures(), page.readCount(), null, true,
                        page.deletedIds(), List.copyOf(artifacts.keySet()));
            }

            /**
             * The bytes of one attachment as a stream with its length. An
             * unknown attachment is an error, not an empty stream; otherwise
             * the host would store an empty file.
             */
            @Override
            public AttachmentContent openAttachment(SyncRunContext context, String externalId) throws AdapterException {
                StoredAttachment stored = attachments.get(externalId);
                if (stored == null) {
                    throw new AdapterException("No attachment <" + externalId + "> in the tool");
                }
                return new AttachmentContent(new ByteArrayInputStream(stored.bytes()), stored.bytes().length,
                        stored.attachment().getContentType());
            }

            /**
             * Reads the feature selection per element. An explicit scope
             * delivers exactly the requested identifiers. An element that
             * never got a selection is absent: not read differs from empty.
             */
            @Override
            public SourcePage<com.rmm.adapter.api.ElementFeatures> readElementFeatures(SyncRunContext context,
                                                                                       ReadScope scope,
                                                                                       PageCursor cursor)
                    throws AdapterException {
                List<com.rmm.adapter.api.ElementFeatures> all = elementFeatures.entrySet().stream()
                        .filter(entry -> !scope.isExplicit() || scope.elementIds().contains(entry.getKey()))
                        .map(entry -> new com.rmm.adapter.api.ElementFeatures(entry.getKey(), entry.getValue()))
                        .toList();
                return pageOf(all, cursor);
            }

            /** Offset pagination: the cursor is the next start index. */
            private <T> SourcePage<T> pageOf(List<T> all, PageCursor cursor) throws AdapterException {
                return pageOf(all, cursor, List.of());
            }

            private <T> SourcePage<T> pageOf(List<T> all, PageCursor cursor, List<String> deletedIds)
                    throws AdapterException {
                int startIndex = cursor == null ? 0 : startIndexOf(cursor);
                int endIndex = Math.min(startIndex + pageSize, all.size());
                List<T> page = new ArrayList<>(all.subList(Math.min(startIndex, endIndex), endIndex));
                boolean lastPage = endIndex >= all.size();
                return new SourcePage<>(page, List.of(), page.size(),
                        lastPage ? null : new PageCursor(String.valueOf(endIndex)), lastPage,
                        lastPage ? deletedIds : List.of());
            }
        }

        /**
         * The start index a cursor of this tool carries. A cursor that is
         * damaged or follows another scheme fails the read with an
         * {@link AdapterException}, like every other failure of a hook.
         */
        private static int startIndexOf(PageCursor cursor) throws AdapterException {
            try {
                int startIndex = Integer.parseInt(cursor.value());
                if (startIndex >= 0) {
                    return startIndex;
                }
            } catch (NumberFormatException notANumber) {
                //Reported below, like a negative index
            }
            throw new AdapterException("The cursor <" + cursor.value() + "> is not a start index of this tool");
        }

        // ------------------------------------------------------------------
        // Write contract: ids assigned by the tool, state in memory
        // ------------------------------------------------------------------

        /**
         * Fills the tool's own copy of an element the host handed over: the
         * id the tool assigned, and name, label and fields of the element.
         * The tool keeps this copy, never the host's object, which stays as
         * it was: a real tool is sent a payload and cannot change the
         * element in the host either.
         */
        private static <E extends Element> E copyOf(Element handedOver, E copy, String externalId) {
            copy.setId(new ElementId(externalId));
            copy.setName(handedOver.getName());
            copy.setLabel(handedOver.getLabel());
            copy.setFields(handedOver.getFields() == null ? null : new HashMap<>(handedOver.getFields()));
            return copy;
        }

        private final class InMemoryWriter implements TargetWriter {

            @Override
            public String createArtifact(SyncRunContext context, ElementArtifact artifact) {
                String externalId = "MEM-" + idSequence.incrementAndGet();
                ElementArtifact stored = copyOf(artifact, new ElementArtifact(), externalId);
                stored.setLocation(artifact.getLocation());
                artifacts.put(externalId, new StoredArtifact(stored, Instant.now()));
                return externalId;
            }

            /** Batch hook for {@code ARTIFACT_CREATE}: one outcome per element, in input order. */
            @Override
            public List<com.rmm.adapter.api.TargetOutcome> createArtifacts(SyncRunContext context,
                                                                           List<ElementArtifact> batch) {
                List<com.rmm.adapter.api.TargetOutcome> outcomes = new java.util.ArrayList<>(batch.size());
                for (ElementArtifact artifact : batch) {
                    outcomes.add(com.rmm.adapter.api.TargetOutcome.success(createArtifact(context, artifact)));
                }
                return outcomes;
            }

            @Override
            public com.rmm.adapter.api.TargetOutcome updateArtifact(SyncRunContext context, ElementArtifact artifact)
                    throws AdapterException {
                StoredArtifact existing = artifacts.get(artifact.getIdValue());
                if (existing == null) {
                    throw new AdapterException("No artifact <" + artifact.getIdValue() + "> in the tool");
                }
                //PATCH semantics (supportsPartialUpdate): the fields sent
                //replace the stored ones, the others remain, and the receipt
                //names exactly the fields sent
                ElementArtifact merged = existing.artifact();
                java.util.List<String> applied = new java.util.ArrayList<>();
                if (artifact.getFields() != null) {
                    artifact.getFields().values().forEach(field -> {
                        merged.addField(field);
                        applied.add(field.getLabelValue());
                    });
                }
                artifacts.put(artifact.getIdValue(), new StoredArtifact(merged, Instant.now()));
                return com.rmm.adapter.api.TargetOutcome.applied(applied);
            }

            @Override
            public void updateArtifactHierarchy(SyncRunContext context, ElementArtifact artifact) {
                //FLAT: the tool has no hierarchy
            }

            @Override
            public void deleteArtifact(SyncRunContext context, String externalId) {
                artifacts.remove(externalId);
                deletedArtifacts.put(externalId, Instant.now());
                //A tool removes the element's attachments with it: a file without
                //its artifact does not exist there, and the host would keep reading it
                attachments.values().removeIf(stored -> externalId.equals(stored.attachment().getParentIdValue()));
                //And its feature selection: no element, no selection
                elementFeatures.remove(externalId);
            }

            @Override
            public String createRelationship(SyncRunContext context, ElementRelationship relationship) {
                String externalId = "REL-" + idSequence.incrementAndGet();
                ElementRelationship stored = copyOf(relationship, new ElementRelationship(), externalId);
                stored.setFromItemId(relationship.getFromItemId());
                stored.setToItemId(relationship.getToItemId());
                stored.setProjectId(relationship.getProjectId());
                relationships.put(externalId, stored);
                return externalId;
            }

            @Override
            public String updateRelationship(SyncRunContext context, ElementRelationship relationship)
                    throws AdapterException {
                //Never reached: supportsRelationshipUpdate is false, so the
                //host deletes and re-creates the relationship instead
                throw new AdapterException("MEMTOOL cannot update relationships — the capability declares that");
            }

            @Override
            public void deleteRelationship(SyncRunContext context, String externalId) {
                relationships.remove(externalId);
            }

            @Override
            public String createComment(SyncRunContext context, ElementComment comment) {
                String externalId = "C-" + idSequence.incrementAndGet();
                ElementComment stored = copyOf(comment, new ElementComment(), externalId);
                stored.setCommentToItemId(comment.getCommentToItemId());
                stored.setProjectId(comment.getProjectId());
                comments.put(externalId, stored);
                return externalId;
            }

            /**
             * Creates an attachment: opens the source, takes the bytes and
             * returns the tool's identifier, which is the host's receipt.
             * The announced length must match the bytes; a tool that
             * tolerates a mismatch would silently store half a file.
             */
            @Override
            public String createAttachment(SyncRunContext context, ElementAttachment attachment,
                                           AttachmentContentSource content) throws AdapterException {
                if (attachment.getParentIdValue() == null || attachment.getParentIdValue().isBlank()) {
                    throw new AdapterException("Attachment <" + attachment.getFileName()
                            + "> carries no parent element");
                }
                byte[] bytes;
                try (AttachmentContent opened = content.open()) {
                    bytes = opened.stream().readAllBytes();
                    if (opened.byteCount() != bytes.length) {
                        throw new AdapterException("The source announced " + opened.byteCount()
                                + " bytes but delivered " + bytes.length);
                    }
                } catch (IOException e) {
                    throw new AdapterException("The bytes of <" + attachment.getFileName() + "> are not readable: "
                            + e.getMessage(), e);
                }
                String externalId = "ATT-" + idSequence.incrementAndGet();
                ElementAttachment stored = new ElementAttachment(new ElementId(externalId), attachment.getParentId(),
                        attachment.getFileName());
                stored.setContentType(attachment.getContentType());
                stored.setDescription(attachment.getDescription());
                stored.setContentHash(attachment.getContentHash());
                stored.setModifiedAt(Instant.now());
                stored.setByteCount(bytes.length);
                attachments.put(externalId, new StoredAttachment(stored, bytes));
                return externalId;
            }

            /**
             * Removes an attachment. Idempotent: what the tool does not have
             * (any more) is removed, which was the goal. The attachment also
             * carries its parent element. This tool does not need it (the
             * attachment is reachable by its own identifier); another one
             * does (a tool such as Jama removes the link to the parent).
             */
            @Override
            public void deleteAttachment(SyncRunContext context, ElementAttachment attachment) {
                attachments.remove(attachment.getIdValue());
            }

            // ---- Variability: the tool mirrors, Mirador is the master ----

            /** The complete state replaces the mirrored one: a tree the list does not name is dropped. */
            @Override
            public void writeFeatureTrees(SyncRunContext context, List<com.rmm.adapter.api.VariabilityTree> trees) {
                featureTrees.clear();
                trees.forEach(tree -> featureTrees.put(tree.name(), tree));
            }

            @Override
            public void writeVariants(SyncRunContext context, List<com.rmm.adapter.api.VariabilityVariant> variants) {
                Tool.this.variants.clear();
                variants.forEach(variant -> Tool.this.variants.put(variant.name(), variant));
            }

            /** One outcome per element; an unknown element is a failure, never stored silently. */
            @Override
            public List<com.rmm.adapter.api.TargetOutcome> writeElementFeatures(SyncRunContext context,
                                                                                List<com.rmm.adapter.api.ElementFeatures> elements) {
                List<com.rmm.adapter.api.TargetOutcome> outcomes = new java.util.ArrayList<>(elements.size());
                for (com.rmm.adapter.api.ElementFeatures element : elements) {
                    if (!artifacts.containsKey(element.externalId())) {
                        outcomes.add(com.rmm.adapter.api.TargetOutcome.failure(new AdapterException(
                                "No artifact <" + element.externalId() + "> in the tool")));
                        continue;
                    }
                    elementFeatures.put(element.externalId(), element.featureNames());
                    outcomes.add(com.rmm.adapter.api.TargetOutcome.success());
                }
                return outcomes;
            }
        }
    }
}
