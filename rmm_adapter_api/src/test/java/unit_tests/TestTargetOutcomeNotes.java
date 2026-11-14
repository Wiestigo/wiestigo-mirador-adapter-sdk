package unit_tests;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import com.rmm.adapter.api.TargetWriter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Notes per element and "skipped": {@link TargetOutcome#notes()} and
 * {@link TargetOutcome#skipped()} break nothing. Every way of building an
 * outcome without them yields no notes and is not skipped; a writer that
 * implements only {@link TargetWriter#createComment} behaves the same
 * through {@link TargetWriter#createCommentWithOutcome}.
 */
class TestTargetOutcomeNotes {

    /** A writer that reports no notes: comments only through createComment. */
    private static class WriterWithoutNotes extends ArtifactOnlyWriter {

        final List<ElementComment> created = new ArrayList<>();

        @Override
        public TargetOutcome updateArtifact(SyncRunContext context, ElementArtifact artifact) {
            return TargetOutcome.applied(List.of("Status"), Map.of("Created", "read-only"));
        }

        @Override
        public String createComment(SyncRunContext context, ElementComment comment) throws AdapterException {
            created.add(comment);
            if (created.size() == 2) {
                throw new AdapterException("refused");
            }
            return "comment-" + created.size();
        }
    }

    private static final SyncRunContext RUN = new SyncRunContext(UUID.randomUUID(), "project-1");

    @Test
    void outcomesBuiltWithoutNotesHaveNoneAndAreNotSkipped() throws AdapterException {
        WriterWithoutNotes writer = new WriterWithoutNotes();
        List<TargetOutcome> outcomes = new ArrayList<>(List.of(
                writer.updateArtifact(RUN, new ElementArtifact()),
                new TargetOutcome("ext-2", null, Set.of("Status")),
                new TargetOutcome("ext-3", null, Set.of("Status"), Map.of("Created", "read-only")),
                TargetOutcome.success(),
                TargetOutcome.success("ext-4"),
                TargetOutcome.applied(List.of()),
                TargetOutcome.failure(new AdapterException("refused")),
                new TargetOutcome(null, null, null, null, null, false)));
        outcomes.add(writer.createCommentWithOutcome(RUN, new ElementComment()));
        outcomes.addAll(writer.createArtifacts(RUN, List.of(new ElementArtifact())));
        outcomes.addAll(writer.createComments(RUN, List.of(new ElementComment(), new ElementComment())));

        for (TargetOutcome outcome : outcomes) {
            assertEquals(List.of(), outcome.notes(), outcome.toString());
            assertFalse(outcome.skipped(), outcome.toString());
        }
    }

    /**
     * The constructors with three and four arguments keep their meaning:
     * identifier, failure, receipt and reasons arrive as the adapter passes them.
     */
    @Test
    void theShorterConstructorsKeepWhatTheyCarry() {
        AdapterException refused = new AdapterException("refused");

        TargetOutcome three = new TargetOutcome("ext-2", refused, Set.of("Status"));
        TargetOutcome four = new TargetOutcome("ext-3", null, Set.of("Status"), Map.of("Created", "fixed"));

        assertEquals("ext-2", three.externalId());
        assertSame(refused, three.failure());
        assertEquals(Set.of("Status"), three.appliedFields());
        assertEquals(Map.of(), three.notApplied());
        assertEquals("ext-3", four.externalId());
        assertTrue(four.isApplied("Status"));
        assertEquals(Map.of("Created", "fixed"), four.notApplied());
    }

    /**
     * The constructors with three and four arguments stay public: compiled
     * adapters resolve them at run time.
     */
    @Test
    void theShorterConstructorsAreStillPublic() throws NoSuchMethodException {
        Constructor<TargetOutcome> threeArguments = TargetOutcome.class.getConstructor(String.class, Exception.class,
                Set.class);
        Constructor<TargetOutcome> fourArguments = TargetOutcome.class.getConstructor(String.class, Exception.class,
                Set.class, Map.class);

        assertTrue(Modifier.isPublic(threeArguments.getModifiers()));
        assertTrue(Modifier.isPublic(fourArguments.getModifiers()));
    }

    /**
     * The default of {@code createCommentWithOutcome} passes on to
     * {@code createComment}: same identifier, same failure.
     */
    @Test
    void theDefaultHookPassesOnToCreateComment() throws AdapterException {
        WriterWithoutNotes writer = new WriterWithoutNotes();

        TargetOutcome first = writer.createCommentWithOutcome(RUN, new ElementComment());
        AdapterException second = assertThrows(AdapterException.class,
                () -> writer.createCommentWithOutcome(RUN, new ElementComment()));
        List<TargetOutcome> batch = writer.createComments(RUN, List.of(new ElementComment()));

        assertEquals(TargetOutcome.success("comment-1"), first);
        assertEquals("refused", second.getMessage());
        assertEquals(List.of(TargetOutcome.success("comment-3")), batch);
        assertEquals(3, writer.created.size());
    }

    /**
     * The default of the batch hook carries the notes and "skipped" of
     * {@code createCommentWithOutcome}.
     */
    @Test
    void theDefaultBatchHookCarriesNotesAndSkips() throws AdapterException {
        TargetWriter writer = new WriterWithoutNotes() {
            @Override
            public TargetOutcome createCommentWithOutcome(SyncRunContext context, ElementComment comment) {
                return comment.getIdValue() == null ? TargetOutcome.skipped("nothing to render")
                        : TargetOutcome.success("c-" + comment.getIdValue()).withNote("one image was dropped");
            }
        };
        ElementComment withId = new ElementComment();
        withId.setId(new ElementId("7"));

        List<TargetOutcome> outcomes = writer.createComments(RUN, List.of(new ElementComment(), withId));

        assertTrue(outcomes.get(0).skipped());
        assertEquals(List.of("nothing to render"), outcomes.get(0).notes());
        assertEquals("c-7", outcomes.get(1).externalId());
        assertEquals(List.of("one image was dropped"), outcomes.get(1).notes());
    }

    @Test
    void theNotesTravelInTheOrderOfTheAdapterAndCannotBeChanged() {
        List<String> notes = new ArrayList<>(List.of("first", "second"));

        TargetOutcome outcome = TargetOutcome.success("ext-1").withNotes(notes).withNote("third");
        notes.add("added later");

        assertEquals(List.of("first", "second", "third"), outcome.notes());
        assertEquals("ext-1", outcome.externalId());
        assertThrows(UnsupportedOperationException.class, () -> outcome.notes().add("x"));
        TargetOutcome plain = TargetOutcome.success("ext-1");
        assertSame(plain, plain.withNotes(List.of()), "without new notes the same object");
    }

    /** Notes change nothing about the receipt, the reasons and the failure. */
    @Test
    void notesKeepTheReceiptTheReasonsAndTheFailure() {
        AdapterException refused = new AdapterException("refused");

        TargetOutcome receipt = TargetOutcome.applied(List.of("Status"), Map.of("Created", "fixed"))
                .withNote("one image was dropped");
        TargetOutcome failed = TargetOutcome.failure(refused).withNote("one image was dropped");

        assertTrue(receipt.isApplied("Status"));
        assertEquals(Map.of("Created", "fixed"), receipt.notApplied());
        assertSame(refused, failed.failure());
        assertFalse(failed.isSuccess());
        assertEquals(List.of("one image was dropped"), failed.notes());
    }

    /** A missing or blank note violates the contract. */
    @Test
    void aMissingOrBlankNoteIsRefused() {
        List<String> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> TargetOutcome.success().withNote(" "));
        assertThrows(IllegalArgumentException.class, () -> TargetOutcome.success().withNote(null));
        assertThrows(IllegalArgumentException.class, () -> TargetOutcome.success().withNotes(withNull));
        assertThrows(IllegalArgumentException.class,
                () -> new TargetOutcome(null, null, null, null, List.of("ok", ""), false));
    }

    /**
     * Skipped is neither failed nor written: no identifier, no failure, no
     * receipt, and always with a note that says why.
     */
    @Test
    void aSkippedOutcomeCarriesOnlyItsNotes() {
        TargetOutcome skipped = TargetOutcome.skipped("nothing to render").withNote("two images were dropped");

        assertTrue(skipped.skipped());
        assertTrue(skipped.isSuccess(), "not failed");
        assertNull(skipped.externalId());
        assertNull(skipped.failure());
        assertFalse(skipped.reportsFields());
        assertEquals(List.of("nothing to render", "two images were dropped"), skipped.notes());

        assertThrows(IllegalArgumentException.class, () -> TargetOutcome.skipped(null));
        assertThrows(IllegalArgumentException.class, () -> TargetOutcome.skipped(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new TargetOutcome(null, null, null, null, List.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new TargetOutcome("ext-1", null, null, null, List.of("x"), true));
        assertThrows(IllegalArgumentException.class,
                () -> new TargetOutcome(null, new AdapterException("x"), null, null, List.of("x"), true));
        assertThrows(IllegalArgumentException.class,
                () -> new TargetOutcome(null, null, Set.of(), null, List.of("x"), true));
        assertThrows(IllegalArgumentException.class,
                () -> new TargetOutcome(null, null, null, Map.of("a", "b"), List.of("x"), true));
    }
}
