package com.rmm.adapter.api;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;

import java.util.ArrayList;
import java.util.List;

/**
 * Write contract for synchronization from Mirador to the tool. Elements arrive
 * as a fully mapped view in the transfer classes the inbound direction uses:
 * <ul>
 *   <li>{@code id}: the external ID in the tool; empty for a create, where the
 *       hook returns the ID the tool assigned.</li>
 *   <li>{@code fields}: already mapped. Keys are tool identifiers, protected
 *       attributes are removed, mapped values carry the tool value.</li>
 *   <li>{@code location}: the target position in the hierarchy; the sync core
 *       fills in target IDs assigned during the run.</li>
 * </ul>
 *
 * <p>The core guarantees the following, so the adapter needs no orchestration
 * of its own:
 * <ul>
 *   <li>Creates arrive level by level: a parent exists in the tool before its
 *       children are created.</li>
 *   <li>Updates arrive in parallel; the hooks must be thread-safe.</li>
 *   <li>Relationships that share an endpoint arrive one after another;
 *       unconnected groups run in parallel.</li>
 *   <li>A failed element (an exception or {@link TargetOutcome#failure}) gets
 *       no receipt and is retried in the next run.</li>
 * </ul>
 *
 * <p>The artifact hooks are required. The core calls the hooks of another
 * phase only if the adapter declares that phase in
 * {@link AdapterCapabilities#writes()}; the adapter must then override them
 * (the TCK checks this). Their throwing defaults stand for "not declared".
 *
 * <p>Batch hooks default to a loop over the single hooks. A tool with real
 * bulk endpoints overrides the hook, declares the {@link BulkOperation} and
 * translates the error report of its API into exactly one
 * {@link TargetOutcome} per element, in input order.
 *
 * <p>Tool specifics (such as locking before writing) stay inside the adapter:
 * the contract only requires that a call has succeeded or failed when it
 * returns.
 */
public interface TargetWriter {

    // ------------------------------------------------------------------
    // Single hooks: the minimal contract of a target tool
    // ------------------------------------------------------------------

    /** Creates the artifact in the tool and returns the ID assigned there. */
    String createArtifact(SyncRunContext context, ElementArtifact artifact) throws AdapterException;

    /**
     * Transfers the attributes of the artifact to the tool and returns the
     * <b>receipt</b>: the fields of the mapped view the tool accepted
     * ({@link TargetOutcome#applied}). A field the tool does not know or does
     * not allow to be written may be skipped; it is then missing from the
     * receipt, optionally with a reason
     * ({@link TargetOutcome#applied(java.util.Collection, java.util.Map)}),
     * and the core treats it as not written and sends it again in the next
     * run. A result without a receipt ({@link TargetOutcome#success()})
     * confirms no field; the TCK requires the receipt.
     */
    TargetOutcome updateArtifact(SyncRunContext context, ElementArtifact artifact) throws AdapterException;

    /** Transfers the hierarchy position of the artifact to the tool. */
    void updateArtifactHierarchy(SyncRunContext context, ElementArtifact artifact) throws AdapterException;

    /**
     * Deletes the artifact with the external ID in the tool. Idempotent: an
     * element the tool no longer knows (404) counts as deleted, without an
     * exception. Any other rejection (403, 409 locked, 5xx) is an exception;
     * the next run tries again. The adapter checks the tool's response and
     * deletes only in the project of the run
     * ({@link SyncRunContext#externalProjectId()}).
     */
    void deleteArtifact(SyncRunContext context, String externalId) throws AdapterException;

    /** Creates the relationship in the tool and returns the ID assigned there. */
    default String createRelationship(SyncRunContext context, ElementRelationship relationship) throws AdapterException {
        throw new AdapterException("This adapter does not declare the RELATIONSHIPS write phase —"
                + " createRelationship must not be called (capability writes)");
    }

    /**
     * Updates the relationship in the tool and returns its ID, which may be
     * new. Called only if {@code supportsRelationshipUpdate} is declared;
     * otherwise the core calls delete and create.
     */
    default String updateRelationship(SyncRunContext context, ElementRelationship relationship) throws AdapterException {
        throw new AdapterException("This adapter does not declare the RELATIONSHIPS write phase —"
                + " updateRelationship must not be called (capability writes)");
    }

    /** Deletes the relationship with the external ID in the tool. */
    default void deleteRelationship(SyncRunContext context, String externalId) throws AdapterException {
        throw new AdapterException("This adapter does not declare the RELATIONSHIPS write phase —"
                + " deleteRelationship must not be called (capability writes)");
    }

    /** Creates the comment in the tool and returns the ID assigned there. */
    default String createComment(SyncRunContext context, ElementComment comment) throws AdapterException {
        throw new AdapterException("This adapter does not declare the COMMENTS write phase —"
                + " createComment must not be called (capability writes)");
    }

    /**
     * Creates the comment in the tool and reports the outcome: the assigned ID
     * ({@link TargetOutcome#success(String)}), optionally with notes for the
     * operator, or {@link TargetOutcome#skipped(String)} if the comment is
     * deliberately not written (see {@link TargetOutcome}). A failure may be
     * thrown or returned as {@link TargetOutcome#failure}.
     *
     * <p>The core calls this method, never {@link #createComment} directly;
     * the default delegates to it. An adapter that declares COMMENTS overrides
     * at least one of the two (the TCK checks this): this one to give notes or
     * to skip, otherwise {@link #createComment}. A wrapper around a
     * {@code TargetWriter} must delegate this method as well.
     */
    default TargetOutcome createCommentWithOutcome(SyncRunContext context, ElementComment comment)
            throws AdapterException {
        return TargetOutcome.success(createComment(context, comment));
    }

    /**
     * Creates an attachment in the tool from the metadata of the element and
     * the bytes of the source. The adapter may open the source as often as its
     * tool requires and closes every stream. There is no replace: the core
     * deletes and creates. Requires the declared phase
     * {@link SyncPhase#ATTACHMENTS}.
     *
     * @return the identifier of the attachment in the tool
     */
    default String createAttachment(SyncRunContext context, ElementAttachment attachment,
                                    AttachmentContentSource content) throws AdapterException {
        throw new AdapterException("This adapter does not declare the ATTACHMENTS write phase —"
                + " createAttachment must not be called (capability writes)");
    }

    /**
     * Removes an attachment in the tool. Idempotent: an attachment the tool no
     * longer has is not an error. Requires the declared phase
     * {@link SyncPhase#ATTACHMENTS}.
     *
     * <p>{@code id} is the attachment in the tool, {@code parentId} the element
     * it is attached to. Some tools need both, because what they remove is the
     * link between the two. The other fields are the last known state and
     * irrelevant here.
     */
    default void deleteAttachment(SyncRunContext context, ElementAttachment attachment) throws AdapterException {
        throw new AdapterException("This adapter does not declare the ATTACHMENTS write phase —"
                + " deleteAttachment must not be called (capability writes)");
    }

    /**
     * Writes all visible feature trees of the sync scope. Mirador owns the
     * trees and the tool mirrors them: the call is idempotent and carries the
     * complete state in every run; a tree the tool has and the list no longer
     * names was deleted in Mirador. Requires the declared phase
     * {@link SyncPhase#VARIABILITY}.
     */
    default void writeFeatureTrees(SyncRunContext context, List<VariabilityTree> trees) throws AdapterException {
        throw new AdapterException("This adapter does not declare the VARIABILITY write phase —"
                + " writeFeatureTrees must not be called (capability writes)");
    }

    /**
     * Writes all visible variants with their selection and validity, under the
     * same rule as {@link #writeFeatureTrees}. Requires the declared phase
     * {@link SyncPhase#VARIABILITY}.
     */
    default void writeVariants(SyncRunContext context, List<VariabilityVariant> variants) throws AdapterException {
        throw new AdapterException("This adapter does not declare the VARIABILITY write phase —"
                + " writeVariants must not be called (capability writes)");
    }

    /**
     * Writes the feature selection per element as qualified names
     * {@code TREE.FEATURE}; an empty list means "no selection" and is written.
     * Returns exactly one {@link TargetOutcome} per element, in input order.
     * Requires the declared phase {@link SyncPhase#VARIABILITY}.
     */
    default List<TargetOutcome> writeElementFeatures(SyncRunContext context, List<ElementFeatures> elements)
            throws AdapterException {
        throw new AdapterException("This adapter does not declare the VARIABILITY write phase —"
                + " writeElementFeatures must not be called (capability writes)");
    }

    /**
     * Changes the type of the existing element to the label of the mapped view
     * and transfers the current field values. Called only if
     * {@code supportsArtifactTypeChange} is declared; declaring it is the
     * promise to override this hook (the TCK checks this). If the tool rejects
     * the change (for example an incompatible target type), throw an
     * {@link AdapterException}: the element gets no receipt and stays visible
     * as failed.
     */
    default void convertArtifact(SyncRunContext context, ElementArtifact artifact) throws AdapterException {
        throw new AdapterException("This adapter declares no artifact type change support —"
                + " convertArtifact must not be called (capability supportsArtifactTypeChange)");
    }

    // ------------------------------------------------------------------
    // Batch hooks: one TargetOutcome per element, in input order
    // ------------------------------------------------------------------    //
    // The defaults call the single hook per element and turn its failure into
    // the outcome of that element. An AdapterStoreLimitException is not a
    // failure of one element: it passes through and ends the run.

    /**
     * Batch create. The {@code precedingElementID} of the location is not
     * reliable here ({@code null} for predecessors in the same batch); the
     * order is given by {@code orderIndex}. See
     * {@link BulkOperation#ARTIFACT_CREATE}.
     */
    default List<TargetOutcome> createArtifacts(SyncRunContext context, List<ElementArtifact> artifacts)
            throws AdapterException {
        List<TargetOutcome> outcomes = new ArrayList<>(artifacts.size());
        for (ElementArtifact artifact : artifacts) {
            try {
                outcomes.add(TargetOutcome.success(createArtifact(context, artifact)));
            } catch (AdapterStoreLimitException limit) {
                throw limit;
            } catch (Exception e) {
                outcomes.add(TargetOutcome.failure(e));
            }
        }
        return outcomes;
    }

    default List<TargetOutcome> updateArtifacts(SyncRunContext context, List<ElementArtifact> artifacts)
            throws AdapterException {
        List<TargetOutcome> outcomes = new ArrayList<>(artifacts.size());
        for (ElementArtifact artifact : artifacts) {
            try {
                outcomes.add(updateArtifact(context, artifact));
            } catch (AdapterStoreLimitException limit) {
                throw limit;
            } catch (Exception e) {
                outcomes.add(TargetOutcome.failure(e));
            }
        }
        return outcomes;
    }

    default List<TargetOutcome> updateArtifactsHierarchy(SyncRunContext context, List<ElementArtifact> artifacts)
            throws AdapterException {
        List<TargetOutcome> outcomes = new ArrayList<>(artifacts.size());
        for (ElementArtifact artifact : artifacts) {
            try {
                updateArtifactHierarchy(context, artifact);
                outcomes.add(TargetOutcome.success());
            } catch (AdapterStoreLimitException limit) {
                throw limit;
            } catch (Exception e) {
                outcomes.add(TargetOutcome.failure(e));
            }
        }
        return outcomes;
    }

    default List<TargetOutcome> deleteArtifacts(SyncRunContext context, List<String> externalIds)
            throws AdapterException {
        List<TargetOutcome> outcomes = new ArrayList<>(externalIds.size());
        for (String externalId : externalIds) {
            try {
                deleteArtifact(context, externalId);
                outcomes.add(TargetOutcome.success());
            } catch (AdapterStoreLimitException limit) {
                throw limit;
            } catch (Exception e) {
                outcomes.add(TargetOutcome.failure(e));
            }
        }
        return outcomes;
    }

    default List<TargetOutcome> createRelationships(SyncRunContext context, List<ElementRelationship> relationships)
            throws AdapterException {
        List<TargetOutcome> outcomes = new ArrayList<>(relationships.size());
        for (ElementRelationship relationship : relationships) {
            try {
                outcomes.add(TargetOutcome.success(createRelationship(context, relationship)));
            } catch (AdapterStoreLimitException limit) {
                throw limit;
            } catch (Exception e) {
                outcomes.add(TargetOutcome.failure(e));
            }
        }
        return outcomes;
    }

    default List<TargetOutcome> updateRelationships(SyncRunContext context, List<ElementRelationship> relationships)
            throws AdapterException {
        List<TargetOutcome> outcomes = new ArrayList<>(relationships.size());
        for (ElementRelationship relationship : relationships) {
            try {
                outcomes.add(TargetOutcome.success(updateRelationship(context, relationship)));
            } catch (AdapterStoreLimitException limit) {
                throw limit;
            } catch (Exception e) {
                outcomes.add(TargetOutcome.failure(e));
            }
        }
        return outcomes;
    }

    default List<TargetOutcome> deleteRelationships(SyncRunContext context, List<String> externalIds)
            throws AdapterException {
        List<TargetOutcome> outcomes = new ArrayList<>(externalIds.size());
        for (String externalId : externalIds) {
            try {
                deleteRelationship(context, externalId);
                outcomes.add(TargetOutcome.success());
            } catch (AdapterStoreLimitException limit) {
                throw limit;
            } catch (Exception e) {
                outcomes.add(TargetOutcome.failure(e));
            }
        }
        return outcomes;
    }

    /**
     * Batch create of comments ({@link BulkOperation#COMMENT_CREATE}). The
     * default goes through {@link #createCommentWithOutcome} and so carries
     * notes and skips along.
     */
    default List<TargetOutcome> createComments(SyncRunContext context, List<ElementComment> comments)
            throws AdapterException {
        List<TargetOutcome> outcomes = new ArrayList<>(comments.size());
        for (ElementComment comment : comments) {
            try {
                outcomes.add(createCommentWithOutcome(context, comment));
            } catch (AdapterStoreLimitException limit) {
                throw limit;
            } catch (Exception e) {
                outcomes.add(TargetOutcome.failure(e));
            }
        }
        return outcomes;
    }
}
