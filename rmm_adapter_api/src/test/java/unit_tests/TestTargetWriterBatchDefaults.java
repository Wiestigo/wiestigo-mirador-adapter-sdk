package unit_tests;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterStoreLimitException;
import com.rmm.adapter.api.IdentifierKind;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import com.rmm.adapter.api.TargetWriter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core guarantee of the default batch implementation: exactly one
 * outcome per element, in input order, and a failed element does not take
 * the others down (failure isolation per element).
 */
class TestTargetWriterBatchDefaults {

    /** Fails for artifacts named "fail"; otherwise the id is "ext-" + name. */
    private static class RecordingWriter implements TargetWriter {

        @Override
        public String createArtifact(SyncRunContext context, ElementArtifact artifact) throws AdapterException {
            String name = artifact.getNameValue();
            if ("fail".equals(name)) {
                throw new AdapterException("the tool refuses the artifact");
            }
            return "ext-" + name;
        }

        @Override
        public com.rmm.adapter.api.TargetOutcome updateArtifact(SyncRunContext context, ElementArtifact artifact) {
            return com.rmm.adapter.api.TargetOutcome.success();
        }

        @Override
        public void updateArtifactHierarchy(SyncRunContext context, ElementArtifact artifact) {
        }

        @Override
        public void deleteArtifact(SyncRunContext context, String externalId) {
        }

        @Override
        public String createRelationship(SyncRunContext context, ElementRelationship relationship) {
            return "rel-1";
        }

        @Override
        public String updateRelationship(SyncRunContext context, ElementRelationship relationship) {
            return "rel-1";
        }

        @Override
        public void deleteRelationship(SyncRunContext context, String externalId) {
        }

        @Override
        public String createComment(SyncRunContext context, ElementComment comment) {
            return "comment-1";
        }
    }

    private static ElementArtifact artifact(String name) {
        return ElementArtifact.ofNameAndLabel(new ElementName(name), new ElementLabel(name));
    }

    @Test
    void batchReturnsOneOutcomePerElementInOrderAndIsolatesFailures() throws Exception {
        TargetWriter writer = new RecordingWriter();
        SyncRunContext context = new SyncRunContext(UUID.randomUUID(), "project-1");

        List<TargetOutcome> outcomes = writer.createArtifacts(context,
                List.of(artifact("a"), artifact("fail"), artifact("b")));

        assertEquals(3, outcomes.size());
        assertTrue(outcomes.get(0).isSuccess());
        assertEquals("ext-a", outcomes.get(0).externalId());
        assertFalse(outcomes.get(1).isSuccess(), "the middle element fails in isolation");
        assertTrue(outcomes.get(2).isSuccess(), "the element AFTER the failure is still processed");
        assertEquals("ext-b", outcomes.get(2).externalId());
    }

    /**
     * A store limit is not the failure of one element: the default batch lets
     * it pass, so the run ends instead of failing element by element.
     */
    @Test
    void aStoreLimitPassesThroughTheDefaultBatch() {
        TargetWriter writer = new RecordingWriter() {
            @Override
            public String createArtifact(SyncRunContext context, ElementArtifact artifact) {
                throw new AdapterStoreLimitException(AdapterStoreLimitException.Limit.CONNECTION_DOCUMENTS, null);
            }
        };
        SyncRunContext context = new SyncRunContext(UUID.randomUUID(), "project-1");

        assertThrows(AdapterStoreLimitException.class,
                () -> writer.createArtifacts(context, List.of(artifact("a"), artifact("b"))));
    }

    /** The receipt of a write and the identifiers of a read keep the order in which they were named. */
    @Test
    void namedFieldsAndIdentifiersKeepTheirOrder() {
        Set<String> applied = new LinkedHashSet<>(List.of("Zeta", "Alpha", "Mid", "Beta", "Omega", "Gamma"));

        assertEquals(List.copyOf(applied), List.copyOf(TargetOutcome.applied(applied).appliedFields()));
        assertEquals(List.of("A-1", "B-2", "C-3", "D-4", "E-5", "F-6"),
                List.copyOf(ReadScope.ofIds(List.of("F-6", "B-2", "E-5", "A-1", "D-4", "C-3")).elementIds()),
                "ofIds names the identifiers in ascending order");
    }

    /** A pattern that does not compile has no usable default sample; declaring the kind reports the pattern. */
    @Test
    void aPatternThatDoesNotCompileHasNoDefaultSample() {
        assertNull(IdentifierKind.roundTripSampleOf(null, "(unclosed", null));
        assertEquals("XY-9", IdentifierKind.roundTripSampleOf(" XY-9 ", "(unclosed", null), "a declared sample wins");
    }
}
