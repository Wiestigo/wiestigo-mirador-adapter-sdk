package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AttachmentContentSource;
import com.rmm.adapter.api.BulkOperation;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetWriter;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The hooks behind what a binding declares: a declared phase, bulk operation
 * or {@code supportsArtifactTypeChange} promises that its hook is
 * overridden. The default of the API behind a declaration is an empty
 * promise.
 */
final class DeclaredHooks {

    private DeclaredHooks() {
    }

    /**
     * Declaring a phase ({@code reads}, {@code writes}) promises that its
     * hooks are overridden: the sync core calls exactly the declared phases,
     * and the throwing defaults of the API stand for "not declared". An
     * overridden but undeclared hook is allowed (an adapter that passes
     * everything on declares only what its service serves).
     */
    static void checkPhases(AdapterCapabilities capabilities, AdapterInstance instance)
            throws NoSuchMethodException {
        if (instance.reader().isPresent()) {
            SourceReader reader = instance.reader().orElseThrow();
            if (capabilities.reads(SyncPhase.RELATIONSHIPS)) {
                requireOverridden("reads", SyncPhase.RELATIONSHIPS, reader, SourceReader.class, "readRelationships",
                        SyncRunContext.class, ReadScope.class, PageCursor.class);
            }
            if (capabilities.reads(SyncPhase.COMMENTS)) {
                requireOverridden("reads", SyncPhase.COMMENTS, reader, SourceReader.class, "readComments",
                        SyncRunContext.class, ReadScope.class, PageCursor.class);
            }
            if (capabilities.reads(SyncPhase.ATTACHMENTS)) {
                //Metadata and byte channel: a page without bytes would be an empty promise
                requireOverridden("reads", SyncPhase.ATTACHMENTS, reader, SourceReader.class, "readAttachments",
                        SyncRunContext.class, ReadScope.class, PageCursor.class);
                requireOverridden("reads", SyncPhase.ATTACHMENTS, reader, SourceReader.class, "openAttachment",
                        SyncRunContext.class, String.class);
            }
            if (capabilities.reads(SyncPhase.VARIABILITY)) {
                //From the tool to Mirador, variability is only the selection per element
                requireOverridden("reads", SyncPhase.VARIABILITY, reader, SourceReader.class, "readElementFeatures",
                        SyncRunContext.class, ReadScope.class, PageCursor.class);
            }
        }
        if (instance.writer().isPresent()) {
            TargetWriter writer = instance.writer().orElseThrow();
            if (capabilities.writes(SyncPhase.RELATIONSHIPS)) {
                requireOverridden("writes", SyncPhase.RELATIONSHIPS, writer, TargetWriter.class, "createRelationship",
                        SyncRunContext.class, ElementRelationship.class);
                requireOverridden("writes", SyncPhase.RELATIONSHIPS, writer, TargetWriter.class, "deleteRelationship",
                        SyncRunContext.class, String.class);
                if (capabilities.supportsRelationshipUpdate()) {
                    requireOverridden("writes", SyncPhase.RELATIONSHIPS, writer, TargetWriter.class,
                            "updateRelationship", SyncRunContext.class, ElementRelationship.class);
                }
            }
            if (capabilities.writes(SyncPhase.COMMENTS)) {
                //One of the two forms is enough: the sync core calls createCommentWithOutcome, whose
                //default delegates to createComment
                requireOneOverridden("writes " + SyncPhase.COMMENTS, writer, TargetWriter.class,
                        "declare only the phases the adapter actually serves",
                        new Class<?>[]{SyncRunContext.class, ElementComment.class},
                        "createCommentWithOutcome", "createComment");
            }
            if (capabilities.writes(SyncPhase.ATTACHMENTS)) {
                //Create and delete: in the contract, replacing is delete plus create
                requireOverridden("writes", SyncPhase.ATTACHMENTS, writer, TargetWriter.class, "createAttachment",
                        SyncRunContext.class, ElementAttachment.class, AttachmentContentSource.class);
                requireOverridden("writes", SyncPhase.ATTACHMENTS, writer, TargetWriter.class, "deleteAttachment",
                        SyncRunContext.class, ElementAttachment.class);
            }
            if (capabilities.writes(SyncPhase.VARIABILITY)) {
                //Trees, variants and the selection per element: the tool mirrors
                //the product definition completely or not at all
                requireOverridden("writes", SyncPhase.VARIABILITY, writer, TargetWriter.class, "writeFeatureTrees",
                        SyncRunContext.class, List.class);
                requireOverridden("writes", SyncPhase.VARIABILITY, writer, TargetWriter.class, "writeVariants",
                        SyncRunContext.class, List.class);
                requireOverridden("writes", SyncPhase.VARIABILITY, writer, TargetWriter.class,
                        "writeElementFeatures", SyncRunContext.class, List.class);
            }
        }
    }

    private static void requireOverridden(String declaration, SyncPhase phase, Object implementation,
                                          Class<?> contract, String hook, Class<?>... parameters)
            throws NoSuchMethodException {
        requireOverridden(declaration + " " + phase, implementation, contract, hook,
                "declare only the phases the adapter actually serves", parameters);
    }

    /**
     * The single definition of "declared but not overridden": if the default
     * of the contract still stands behind the hook, the declaration is an
     * empty promise. Every declaration check goes through here.
     */
    private static void requireOverridden(String declaration, Object implementation, Class<?> contract,
                                          String hook, String advice, Class<?>... parameters)
            throws NoSuchMethodException {
        if (implementation.getClass().getMethod(hook, parameters).getDeclaringClass() == contract) {
            throw new TckViolation("Capabilities declare " + declaration + ", but the "
                    + contract.getSimpleName() + " <" + implementation.getClass().getName()
                    + "> does not override " + hook + "(...) — " + advice);
        }
    }

    /**
     * Like {@link #requireOverridden(String, Object, Class, String, String, Class[])},
     * for a hook with two forms: at least one of {@code hooks} must be
     * overridden.
     */
    private static void requireOneOverridden(String declaration, Object implementation, Class<?> contract,
                                             String advice, Class<?>[] parameters, String... hooks)
            throws NoSuchMethodException {
        for (String hook : hooks) {
            if (implementation.getClass().getMethod(hook, parameters).getDeclaringClass() != contract) {
                return;
            }
        }
        throw new TckViolation("Capabilities declare " + declaration + ", but the "
                + contract.getSimpleName() + " <" + implementation.getClass().getName()
                + "> overrides none of " + String.join("(...), ", hooks) + "(...) — " + advice);
    }

    /**
     * {@code supportsArtifactTypeChange} promises that {@code convertArtifact}
     * is overridden; only then can the sync core carry a type conversion made
     * in Mirador into the tool.
     */
    static void checkTypeChange(AdapterCapabilities capabilities, AdapterInstance instance)
            throws NoSuchMethodException {
        if (!capabilities.supportsArtifactTypeChange()) {
            return;
        }
        TargetWriter writer = instance.writer().orElseThrow(() -> new TckViolation(
                "Capabilities declare supportsArtifactTypeChange, but the instance provides no TargetWriter"));
        requireOverridden("supportsArtifactTypeChange", writer, TargetWriter.class, "convertArtifact",
                "declare only what is actually implemented", SyncRunContext.class, ElementArtifact.class);
    }

    /** The batch hook per {@link BulkOperation}; declaring the operation promises this override. */
    private static final Map<BulkOperation, String> BULK_HOOKS = Map.of(
            BulkOperation.ARTIFACT_CREATE, "createArtifacts",
            BulkOperation.ARTIFACT_UPDATE, "updateArtifacts",
            BulkOperation.ARTIFACT_HIERARCHY_UPDATE, "updateArtifactsHierarchy",
            BulkOperation.ARTIFACT_DELETE, "deleteArtifacts",
            BulkOperation.RELATIONSHIP_CREATE, "createRelationships",
            BulkOperation.RELATIONSHIP_UPDATE, "updateRelationships",
            BulkOperation.RELATIONSHIP_DELETE, "deleteRelationships",
            BulkOperation.COMMENT_CREATE, "createComments");

    /**
     * A declared {@link BulkOperation} promises that its batch hook is
     * overridden; only then does the host pass a batch on as one call. The
     * default (a loop over the single hooks) behind a declaration is an empty
     * promise.
     */
    static void checkBulkOperations(AdapterCapabilities capabilities, AdapterInstance instance)
            throws NoSuchMethodException {
        Set<BulkOperation> declared = capabilities.bulkOperations();
        if (declared.isEmpty()) {
            return;
        }
        TargetWriter writer = instance.writer().orElseThrow(() -> new TckViolation(
                "Capabilities declare bulkOperations " + declared
                        + ", but the instance provides no TargetWriter"));
        for (BulkOperation operation : declared) {
            requireOverridden(operation.name(), writer, TargetWriter.class, BULK_HOOKS.get(operation),
                    "declare only what is actually implemented as a batch hook", SyncRunContext.class, List.class);
        }
    }
}
