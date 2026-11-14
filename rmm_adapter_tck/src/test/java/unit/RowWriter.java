package unit;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import com.rmm.adapter.api.TargetWriter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The writer of {@link RowTool}: collects created artifacts in {@code created}, per binding or, as a defect,
 * in the type.
 */
final class RowWriter implements TargetWriter {
    private final List<String> created;
    private final AtomicInteger sequence = new AtomicInteger();

    RowWriter(List<String> created) {
        this.created = created;
    }

    @Override
    public String createArtifact(SyncRunContext context, ElementArtifact artifact) {
        String id = "new-" + sequence.incrementAndGet();
        created.add(id);
        return id;
    }

    @Override
    public TargetOutcome updateArtifact(SyncRunContext context, ElementArtifact artifact) {
        return TargetOutcome.applied(List.of());
    }

    @Override
    public void updateArtifactHierarchy(SyncRunContext context, ElementArtifact artifact) {
    }

    @Override
    public void deleteArtifact(SyncRunContext context, String externalId) {
        created.remove(externalId);
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
