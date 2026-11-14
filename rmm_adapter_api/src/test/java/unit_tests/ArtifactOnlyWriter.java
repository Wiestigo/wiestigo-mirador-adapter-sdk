package unit_tests;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import com.rmm.adapter.api.TargetWriter;

/**
 * A writer that implements only the mandatory artifact hooks, each with a fixed answer; every other hook
 * keeps its default. Tests extend it with the hooks they are about.
 */
class ArtifactOnlyWriter implements TargetWriter {

    @Override
    public String createArtifact(SyncRunContext context, ElementArtifact artifact) {
        return "ext-1";
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
}
