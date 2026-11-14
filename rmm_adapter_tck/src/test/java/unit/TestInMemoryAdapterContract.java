package unit;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.reference.InMemoryToolAdapter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Runs every contract case against the reference implementation (an in-memory fake tool), including the
 * live cases, which need no real tool here. It also serves as the model for how adapter authors set up
 * their own TCK test class.
 */
public class TestInMemoryAdapterContract extends AdapterContractTest {

    /**
     * The project space of the second binding (case 1): the same tool, a different access with a different
     * data set. This also exposes a client that the type caches per address.
     */
    private static final String SECOND_SPACE = "second-project";

    static InMemoryToolAdapter seeded() {
        InMemoryToolAdapter adapter = new InMemoryToolAdapter();
        Instant lastWeek = Instant.now().minusSeconds(7 * 24 * 3600);
        //5 artifacts at page size 2: the read contract sees several pages
        for (int index = 1; index <= 5; index++) {
            ElementArtifact artifact = ElementArtifact.ofNameAndLabel(
                    new ElementName("Requirement"), new ElementLabel("Requirement"));
            artifact.setId(new ElementId("SEED-" + index));
            adapter.seedArtifact(artifact, lastWeek);
        }
        ElementRelationship relationship = new ElementRelationship(new ElementId("SEED-REL-1"),
                new ElementName("relates"), new ElementId("SEED-1"), new ElementId("SEED-2"));
        adapter.seedRelationship(relationship);
        ElementComment comment = new ElementComment();
        comment.setId(new ElementId("SEED-C-1"));
        comment.setCommentToItemId(new ElementId("SEED-1"));
        adapter.seedComment(comment);
        //Two attachments at one artifact at page size 2: the attachment
        //contract sees metadata, bytes and the completeness statement
        ElementAttachment attachment = new ElementAttachment(new ElementId("SEED-A-1"),
                new ElementId("SEED-1"), "evidence.pdf");
        attachment.setContentType("application/pdf");
        adapter.seedAttachment(attachment, "SEED bytes of evidence.pdf".getBytes(StandardCharsets.UTF_8));
        ElementAttachment second = new ElementAttachment(new ElementId("SEED-A-2"),
                new ElementId("SEED-1"), "measurement-log.csv");
        second.setContentType("text/csv");
        adapter.seedAttachment(second, "a;b\n1;2\n".getBytes(StandardCharsets.UTF_8));
        //The tool of the second binding has a smaller data set of its own
        for (int index = 1; index <= 3; index++) {
            ElementArtifact artifact = ElementArtifact.ofNameAndLabel(
                    new ElementName("Requirement"), new ElementLabel("Requirement"));
            artifact.setId(new ElementId("SECOND-" + index));
            adapter.seedArtifact(InMemoryToolAdapter.toolKey(InMemoryToolAdapter.DEFAULT_TOOL, SECOND_SPACE),
                    artifact, lastWeek);
        }
        return adapter;
    }

    /**
     * Returns a fresh type with the same data set on every call. Only then does case 1 read each binding as
     * the first of its type as well; adapter authors should do the same.
     */
    @Override
    protected ToolAdapter adapter() {
        return seeded();
    }

    @Override
    protected Map<String, String> configuration() {
        return Map.of("page.size", "2");
    }

    /** Case 1: the same configuration and address, only a different project space with a different data set. */
    @Override
    protected Optional<Map<String, String>> secondBindingConfiguration() {
        return Optional.of(Map.of("page.size", "2", "tool.space", SECOND_SPACE));
    }

    @Override
    protected Optional<SyncRunContext> liveRunContext() {
        return Optional.of(new SyncRunContext(UUID.randomUUID(), "MEM-PRJ"));
    }

    /** Case 2: the tool lives in memory and has no network traffic to check, so the case is opted out explicitly. */
    @Override
    protected Optional<String> networkOptOut() {
        return Optional.of("the reference tool lives in memory and sends no request");
    }

    /** A delta read reports the deletions since the instant on its last page; a full read never does. */
    @Test
    public void aDeltaReadReportsDeletionsSinceTheInstant() throws Exception {
        InMemoryToolAdapter tool = seeded();
        Instant lastRun = Instant.now().minusSeconds(60);
        tool.seedDeletion("SEED-5", Instant.now());
        var instance = tool.bind(com.rmm.adapter.tck.TckAdapterContext.of(tool.descriptor(), configuration()));
        try {
            var delta = com.rmm.adapter.tck.ReadContract.readAll("readArtifacts(delta)",
                    instance.reader().orElseThrow()::readArtifacts, liveRunContext().orElseThrow(),
                    com.rmm.adapter.api.ReadScope.since(lastRun), 10);
            org.junit.jupiter.api.Assertions.assertTrue(delta.elements().isEmpty(), "nothing changed since the instant");
            var lastPage = instance.reader().orElseThrow().readArtifacts(liveRunContext().orElseThrow(),
                    com.rmm.adapter.api.ReadScope.since(lastRun), null);
            org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("SEED-5"), lastPage.deletedIds());
            var full = instance.reader().orElseThrow().readArtifacts(liveRunContext().orElseThrow(),
                    com.rmm.adapter.api.ReadScope.full(), null);
            org.junit.jupiter.api.Assertions.assertTrue(full.deletedIds().isEmpty(), "a full run needs no feed");
        } finally {
            instance.close();
        }
    }

    @Override
    protected Optional<String> roundTripRelationshipType() {
        return Optional.of("relates");
    }

    /** The template for the comment round trip. */
    @Override
    protected Optional<ElementComment> roundTripComment() {
        return Optional.of(commentWithText("The brake squeals."));
    }

    @Override
    protected Optional<ElementArtifact> roundTripArtifact() {
        ElementArtifact artifact = ElementArtifact.ofNameAndLabel(
                new ElementName("Requirement"), new ElementLabel("Requirement"));
        //Two fields: the partial update case sends one and checks the other
        artifact.addField(new com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString(
                new ElementName("name"), new ElementLabel("Name"), "Brake"));
        artifact.addField(new com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString(
                new ElementName("description"), new ElementLabel("Description"), "The brake brakes."));
        return Optional.of(artifact);
    }
}
