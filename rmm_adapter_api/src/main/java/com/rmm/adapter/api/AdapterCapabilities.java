package com.rmm.adapter.api;

import java.util.Set;

/**
 * Declared capabilities of a tool. Capabilities are declared, not guessed: the
 * sync core picks its strategy from them, so no adapter rebuilds orchestration
 * logic. They are constant, that is answerable without configuration; what
 * only configuration decides, such as which field carries the hierarchy path,
 * belongs to the bound instance ({@link AdapterInstance#pathHierarchy()}).
 *
 * <p>Updating a comment cannot be declared in major 1: comments are only
 * created.
 *
 * @param directions supported sync directions (at least one).
 * @param hierarchyModel how the tool represents hierarchy. With
 *     {@link HierarchyModel#PATH_ATTRIBUTE} the instance must name its path
 *     attribute ({@link AdapterInstance#pathHierarchy()}).
 * @param supportsRelationshipUpdate false: the tool cannot change an existing
 *     link (a tool such as Jama); the core calls delete and create instead of
 *     {@code updateRelationship}. true requires RELATIONSHIPS in
 *     {@code writes}.
 * @param supportsArtifactTypeChange true: the tool can change the type of an
 *     existing element (a tool such as Jira, with {@code issuetype} in the
 *     PUT); the core then calls {@link TargetWriter#convertArtifact} when a
 *     type is converted in Mirador. false: converted elements fail visibly and
 *     get no receipt; deleting and recreating in the tool is an operator
 *     decision the core never takes itself.
 * @param supportsPartialUpdate true: an update that carries only some fields
 *     leaves the others untouched in the tool (PATCH semantics). Only then
 *     does the core send just the attributes changed since the last
 *     reconciliation. false (the safe default): the core always sends the full
 *     state, as required wherever an update replaces (PUT semantics) and a
 *     partial update would clear the fields not sent.
 * @param supportsDeltaRead true: the tool can deliver "changed since X". Only
 *     then does the {@link SourceReader} receive a
 *     {@link ReadScope#changedSince()}; otherwise a requested delta run falls
 *     back to a full run.
 * @param reads the phases ({@link SyncPhase}) the adapter serves from the tool
 *     to Mirador. The core calls only declared phases of the
 *     {@link SourceReader} and reconciles only those in a full run: an empty
 *     delivery of a declared phase means "everything deleted", while an
 *     undeclared phase does not exist for the core. Required with
 *     {@link SyncDirection#TO_RMM}, and then at least
 *     {@link SyncPhase#ARTIFACTS}; empty without that direction. Declaring
 *     {@link SyncPhase#ATTACHMENTS} obliges the adapter to serve
 *     {@code readAttachments} and {@code openAttachment}; declaring
 *     {@link SyncPhase#VARIABILITY}, {@code readElementFeatures} (reads) or
 *     {@code writeFeatureTrees}, {@code writeVariants} and
 *     {@code writeElementFeatures} (writes).
 * @param writes the same from Mirador to the tool: the phases the
 *     {@link TargetWriter} serves. The core never calls an undeclared phase
 *     and discards its outbound events without transfer. Required with
 *     {@link SyncDirection#FROM_RMM}, and then at least
 *     {@link SyncPhase#ARTIFACTS}; empty without that direction.
 * @param bulkOperations operations for which the tool has real bulk endpoints
 *     (such as {@code POST /issue/bulk} in Jira) and the adapter overrides the
 *     batch hook; each requires its phase in {@code writes}. Empty if the
 *     adapter implements only the single hooks.
 * @param rateLimit rate limit of the tool; null if none is known. The host
 *     enforces it: it spaces the hook calls and repeats retryable errors
 *     ({@link AdapterException#isRetryable()}) up to
 *     {@link RateLimit#maxRetries()} times, so an adapter needs no retry logic
 *     of its own.
 * @param imageReferenceForms the forms in which the tool refers to images in
 *     its rich text and which the core keeps when saving
 *     ({@link ImageReferenceForm}; since contract 1.1). Empty (the default of
 *     the ten-argument constructor): no tool reference survives saving.
 */
public record AdapterCapabilities(Set<SyncDirection> directions,
                                  HierarchyModel hierarchyModel,
                                  boolean supportsRelationshipUpdate,
                                  boolean supportsArtifactTypeChange,
                                  boolean supportsPartialUpdate,
                                  boolean supportsDeltaRead,
                                  Set<SyncPhase> reads,
                                  Set<SyncPhase> writes,
                                  Set<BulkOperation> bulkOperations,
                                  RateLimit rateLimit,
                                  Set<ImageReferenceForm> imageReferenceForms) {

    /** Capabilities without image reference forms. */
    public AdapterCapabilities(Set<SyncDirection> directions, HierarchyModel hierarchyModel,
                               boolean supportsRelationshipUpdate, boolean supportsArtifactTypeChange,
                               boolean supportsPartialUpdate, boolean supportsDeltaRead, Set<SyncPhase> reads,
                               Set<SyncPhase> writes, Set<BulkOperation> bulkOperations, RateLimit rateLimit) {
        this(directions, hierarchyModel, supportsRelationshipUpdate, supportsArtifactTypeChange,
                supportsPartialUpdate, supportsDeltaRead, reads, writes, bulkOperations, rateLimit, Set.of());
    }

    public AdapterCapabilities {
        if (directions == null || directions.isEmpty()) {
            throw new IllegalArgumentException("An adapter must declare at least one sync direction");
        }
        directions = Set.copyOf(directions);
        if (hierarchyModel == null) {
            throw new IllegalArgumentException("hierarchyModel must not be null");
        }
        reads = reads == null ? Set.of() : Set.copyOf(reads);
        writes = writes == null ? Set.of() : Set.copyOf(writes);
        requirePhases("reads", reads, directions.contains(SyncDirection.TO_RMM), SyncDirection.TO_RMM);
        requirePhases("writes", writes, directions.contains(SyncDirection.FROM_RMM), SyncDirection.FROM_RMM);
        bulkOperations = bulkOperations == null ? Set.of() : Set.copyOf(bulkOperations);
        for (BulkOperation operation : bulkOperations) {
            if (!writes.contains(operation.phase())) {
                throw new IllegalArgumentException("bulkOperations declares " + operation + ", but writes does not"
                        + " declare " + operation.phase());
            }
        }
        if (supportsRelationshipUpdate && !writes.contains(SyncPhase.RELATIONSHIPS)) {
            throw new IllegalArgumentException("supportsRelationshipUpdate needs writes to declare RELATIONSHIPS");
        }
        imageReferenceForms = imageReferenceForms == null ? Set.of() : Set.copyOf(imageReferenceForms);
    }

    /** The same capabilities with these image reference forms. */
    public AdapterCapabilities withImageReferenceForms(Set<ImageReferenceForm> forms) {
        return new AdapterCapabilities(directions, hierarchyModel, supportsRelationshipUpdate,
                supportsArtifactTypeChange, supportsPartialUpdate, supportsDeltaRead, reads, writes, bulkOperations,
                rateLimit, forms);
    }

    /** A direction declares its phases exactly if it is supported, and then at least the artifacts. */
    private static void requirePhases(String name, Set<SyncPhase> phases, boolean directionDeclared,
                                      SyncDirection direction) {
        if (directionDeclared && !phases.contains(SyncPhase.ARTIFACTS)) {
            throw new IllegalArgumentException(name + " must declare at least ARTIFACTS with direction " + direction);
        }
        if (!directionDeclared && !phases.isEmpty()) {
            throw new IllegalArgumentException(name + " declares " + phases + " without direction " + direction);
        }
    }

    /** Whether the adapter serves this phase from the tool to Mirador. */
    public boolean reads(SyncPhase phase) {
        return reads.contains(phase);
    }

    /** Whether the adapter serves this phase from Mirador to the tool. */
    public boolean writes(SyncPhase phase) {
        return writes.contains(phase);
    }
}
