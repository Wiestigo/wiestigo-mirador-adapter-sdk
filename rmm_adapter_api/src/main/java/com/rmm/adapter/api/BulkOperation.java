package com.rmm.adapter.api;

/**
 * Write operations for which a tool can offer real bulk endpoints. An adapter
 * declares only what it actually implements as an overridden batch hook; for
 * everything else the default applies (a loop over the single hooks).
 */
public enum BulkOperation {

    /**
     * Batch contract: a create batch is a group of siblings, and the external
     * ID of a predecessor created in the same batch does not exist yet. The
     * {@code precedingElementID} of the location is therefore not reliable in a
     * batch ({@code null} for batch siblings); the order is carried by the
     * {@code orderIndex}. A tool whose create API consumes the predecessor does
     * not declare this operation and keeps the single hook, where every
     * assigned ID is applied before the next sibling is converted.
     */
    ARTIFACT_CREATE,

    ARTIFACT_UPDATE,
    ARTIFACT_HIERARCHY_UPDATE,
    ARTIFACT_DELETE,
    RELATIONSHIP_CREATE,
    RELATIONSHIP_UPDATE,
    RELATIONSHIP_DELETE,

    /**
     * Comments of an artifact can reply to each other, and a reply carries the
     * tool ID of its thread predecessor. The core therefore passes them in
     * several rounds: a batch contains only comments whose predecessor is no
     * longer pending (it has a receipt, is not in the batch set, or has
     * failed); the replies follow in the next batch once their predecessor has
     * a receipt. A thread without replies travels in one round.
     */
    COMMENT_CREATE;

    /** The write phase the operation belongs to; declaring a bulk operation requires the declared phase. */
    public SyncPhase phase() {
        return switch (this) {
            case ARTIFACT_CREATE, ARTIFACT_UPDATE, ARTIFACT_HIERARCHY_UPDATE, ARTIFACT_DELETE -> SyncPhase.ARTIFACTS;
            case RELATIONSHIP_CREATE, RELATIONSHIP_UPDATE, RELATIONSHIP_DELETE -> SyncPhase.RELATIONSHIPS;
            case COMMENT_CREATE -> SyncPhase.COMMENTS;
        };
    }
}
