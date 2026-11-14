package com.rmm.adapter.tck.reference;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.tck.ReadContract;
import com.rmm.adapter.tck.TckAdapterContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-memory reference adapter beyond the contract cases: a cursor it did not issue fails the read with
 * an {@link AdapterException}, and the write hooks leave the elements of the host as they were.
 */
public class TestInMemoryToolAdapter {

    private final SyncRunContext run = new SyncRunContext(UUID.randomUUID(), "MEM-PRJ");

    private final List<TckAdapterContext> contexts = new ArrayList<>();

    private AdapterInstance bound(InMemoryToolAdapter adapter) {
        TckAdapterContext context = TckAdapterContext.of(adapter.descriptor(), Map.of("page.size", "2"));
        contexts.add(context);
        return adapter.bind(context);
    }

    @AfterEach
    public void releaseTheBindings() {
        contexts.forEach(TckAdapterContext::release);
    }

    private static ElementArtifact requirement(String id) {
        ElementArtifact artifact = ElementArtifact.ofNameAndLabel(new ElementName("Requirement"),
                new ElementLabel("Requirement"));
        if (id != null) {
            artifact.setId(new ElementId(id));
        }
        artifact.addField(new ElementFieldString(new ElementName("name"), new ElementLabel("Name"), "Brake"));
        return artifact;
    }

    /** One reader call per read phase, so that a test can run the same check against each. */
    @FunctionalInterface
    private interface Phase {
        SourcePage<?> read(SourceReader reader, PageCursor cursor) throws AdapterException;
    }

    private List<Phase> phases() {
        return List.of(
                (reader, cursor) -> reader.readArtifacts(run, ReadScope.full(), cursor),
                (reader, cursor) -> reader.readRelationships(run, ReadScope.full(), cursor),
                (reader, cursor) -> reader.readComments(run, ReadScope.full(), cursor),
                (reader, cursor) -> reader.readAttachments(run, ReadScope.full(), cursor),
                (reader, cursor) -> reader.readElementFeatures(run, ReadScope.full(), cursor));
    }

    @Test
    public void aCursorThatIsNoStartIndexFailsEveryReadPhaseWithAnAdapterException() {
        InMemoryToolAdapter adapter = new InMemoryToolAdapter();
        adapter.seedArtifact(requirement("SEED-1"), Instant.now());
        SourceReader reader = bound(adapter).reader().orElseThrow();

        for (Phase phase : phases()) {
            for (String foreign : List.of("next-page", "1.0", "-1", "99999999999")) {
                AdapterException failed = assertThrows(AdapterException.class,
                        () -> phase.read(reader, new PageCursor(foreign)), foreign);

                assertTrue(failed.getMessage().contains("<" + foreign + ">"), failed.getMessage());
                assertFalse(failed.isRetryable(), "the same cursor fails again");
            }
        }
    }

    @Test
    public void aStartIndexBeyondTheEndIsAnEmptyLastPage() throws AdapterException {
        InMemoryToolAdapter adapter = new InMemoryToolAdapter();
        adapter.seedArtifact(requirement("SEED-1"), Instant.now());
        SourceReader reader = bound(adapter).reader().orElseThrow();

        for (Phase phase : phases()) {
            SourcePage<?> page = phase.read(reader, new PageCursor("7"));

            assertTrue(page.elements().isEmpty());
            assertTrue(page.lastPage(), "the data set shrank during a pause: nothing is left to read");
        }
    }

    @Test
    public void createArtifactLeavesTheElementOfTheHostAsItWas() throws Exception {
        AdapterInstance instance = bound(new InMemoryToolAdapter());
        TargetWriter writer = instance.writer().orElseThrow();
        ElementArtifact ofTheHost = requirement(null);

        String first = writer.createArtifact(run, ofTheHost);
        String second = writer.createArtifact(run, ofTheHost);

        assertNull(ofTheHost.getIdValue(), "the id of the tool is the answer of the hook, not a change to the element");
        List<ElementArtifact> read = ReadContract.readAll("readArtifacts",
                instance.reader().orElseThrow()::readArtifacts, run, ReadScope.full(), 10).elements();
        assertEquals(List.of(first, second), read.stream().map(ElementArtifact::getIdValue).toList(),
                "one record per call, each under its own id");
        assertEquals("Brake", read.get(0).getFieldByLabel("Name").getValueAsString());
    }

    @Test
    public void anUpdateChangesTheRecordOfTheToolNotTheElementItWasCreatedFrom() throws Exception {
        AdapterInstance instance = bound(new InMemoryToolAdapter());
        TargetWriter writer = instance.writer().orElseThrow();
        ElementArtifact ofTheHost = requirement(null);
        String externalId = writer.createArtifact(run, ofTheHost);

        ElementArtifact update = new ElementArtifact();
        update.setId(new ElementId(externalId));
        update.addField(new ElementFieldString(new ElementName("name"), new ElementLabel("Name"), "Steering"));
        update.addField(new ElementFieldString(new ElementName("status"), new ElementLabel("Status"), "Draft"));
        writer.updateArtifact(run, update);

        assertEquals(1, ofTheHost.getFields().size(), "the element of the host keeps its own fields");
        assertEquals("Brake", ofTheHost.getFieldByLabel("Name").getValueAsString());
        ElementArtifact read = instance.reader().orElseThrow().readArtifacts(run, ReadScope.full(), null)
                .elements().get(0);
        assertEquals("Steering", read.getFieldByLabel("Name").getValueAsString());
        assertEquals("Draft", read.getFieldByLabel("Status").getValueAsString());
    }

    @Test
    public void createRelationshipAndCreateCommentLeaveTheElementsOfTheHostAsTheyWere() throws Exception {
        AdapterInstance instance = bound(new InMemoryToolAdapter());
        TargetWriter writer = instance.writer().orElseThrow();
        SourceReader reader = instance.reader().orElseThrow();
        ElementRelationship link = new ElementRelationship(null, new ElementName("relates"), new ElementId("A-1"),
                new ElementId("A-2"));
        ElementComment comment = new ElementComment();
        comment.setCommentToItemId(new ElementId("A-1"));
        comment.addField(new ElementFieldString(new ElementName("body"), new ElementLabel("Body"), "Squeals."));

        String linkId = writer.createRelationship(run, link);
        String commentId = writer.createComment(run, comment);

        assertNull(link.getIdValue());
        assertNull(comment.getIdValue());
        ElementRelationship readLink = reader.readRelationships(run, ReadScope.full(), null).elements().get(0);
        assertEquals(linkId, readLink.getIdValue());
        assertEquals("relates", readLink.getNameValue());
        assertEquals("relates", readLink.getLabelValue());
        assertEquals("A-1", readLink.getFromItemIdValue());
        assertEquals("A-2", readLink.getToItemIdValue());
        ElementComment readComment = reader.readComments(run, ReadScope.full(), null).elements().get(0);
        assertEquals(commentId, readComment.getIdValue());
        assertEquals("A-1", readComment.getCommentToItemIdValue());
        assertEquals("Squeals.", readComment.getFieldByLabel("Body").getValueAsString());
    }
}
