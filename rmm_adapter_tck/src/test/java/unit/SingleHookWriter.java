package unit;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import com.rmm.adapter.api.TargetWriter;

/**
 * A writer with only the hooks for a single element, each with a fixed answer; the batch hooks and
 * {@code convertArtifact} keep their default implementation.
 */
class SingleHookWriter implements TargetWriter {
    @Override
    public String createArtifact(SyncRunContext context, ElementArtifact artifact) {
        return "a-1";
    }

    @Override
    public TargetOutcome updateArtifact(SyncRunContext context, ElementArtifact artifact) {
        return TargetOutcome.success();
    }

    @Override
    public void updateArtifactHierarchy(SyncRunContext context, ElementArtifact artifact) {
    }

    @Override
    public void deleteArtifact(SyncRunContext context, String externalId) {
    }

    @Override
    public String createRelationship(SyncRunContext context, ElementRelationship relationship) {
        return "r-1";
    }

    @Override
    public String updateRelationship(SyncRunContext context, ElementRelationship relationship) {
        return "r-1";
    }

    @Override
    public void deleteRelationship(SyncRunContext context, String externalId) {
    }

    @Override
    public String createComment(SyncRunContext context, ElementComment comment) {
        return "c-1";
    }
}
