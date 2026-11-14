package unit_tests;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Not written: field and reason": {@link TargetOutcome#notApplied()} breaks
 * nothing. Every way of building an outcome without reasons yields an empty
 * map, and a writer that uses only the factory methods and the constructor
 * with three arguments compiles and runs unchanged
 * ({@link WriterWithoutReasons}).
 */
class TestTargetOutcomeNotApplied {

    /**
     * A writer that reports no reasons: only the factory methods and the
     * constructor with three arguments. If it compiles, the source is
     * compatible.
     */
    private static final class WriterWithoutReasons extends ArtifactOnlyWriter {

        @Override
        public TargetOutcome updateArtifact(SyncRunContext context, ElementArtifact artifact) {
            return TargetOutcome.applied(List.of("Status"));
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

        TargetOutcome byTheThreeArgumentConstructor() {
            return new TargetOutcome("ext-2", null, Set.of("Status"));
        }
    }

    @Test
    void outcomesBuiltWithoutReasonsHaveNone() throws AdapterException {
        WriterWithoutReasons writer = new WriterWithoutReasons();
        SyncRunContext context = new SyncRunContext(UUID.randomUUID(), "project-1");

        List<TargetOutcome> outcomes = List.of(
                writer.updateArtifact(context, new ElementArtifact()),
                writer.byTheThreeArgumentConstructor(),
                TargetOutcome.success(),
                TargetOutcome.success("ext-3"),
                TargetOutcome.applied(List.of()),
                TargetOutcome.failure(new AdapterException("refused")),
                new TargetOutcome(null, null, null, null));
        //The defaults of the batch hooks also build their outcomes without reasons
        outcomes = new ArrayList<>(outcomes);
        outcomes.addAll(writer.createArtifacts(context, List.of(new ElementArtifact())));
        outcomes.addAll(writer.updateArtifacts(context, List.of(new ElementArtifact())));

        for (TargetOutcome outcome : outcomes) {
            assertEquals(Map.of(), outcome.notApplied(), outcome.toString());
        }
        assertTrue(outcomes.get(0).isApplied("Status"), "the receipt is unchanged");
    }

    /**
     * The constructor with three arguments keeps its meaning: identifier and
     * receipt arrive as the adapter passes them. If the receipt were lost, every
     * field of such an adapter would count as unconfirmed, and every reference
     * field would fail in every run.
     */
    @Test
    void theThreeArgumentConstructorKeepsIdAndReceipt() {
        TargetOutcome outcome = new WriterWithoutReasons().byTheThreeArgumentConstructor();

        assertEquals("ext-2", outcome.externalId());
        assertEquals(Set.of("Status"), outcome.appliedFields());
        assertTrue(outcome.reportsFields());
        assertTrue(outcome.isApplied("Status"));
        assertTrue(outcome.isSuccess());
        assertEquals(Map.of(), outcome.notApplied());

        AdapterException refused = new AdapterException("refused");
        TargetOutcome failed = new TargetOutcome("ext-3", refused, null);
        assertEquals("ext-3", failed.externalId());
        assertEquals(refused, failed.failure());
        assertFalse(failed.reportsFields());
        assertFalse(failed.isApplied("Status"));
    }

    /**
     * The constructor with three arguments stays public: compiled adapters
     * resolve it at run time.
     */
    @Test
    void theThreeArgumentConstructorIsStillPublic() throws NoSuchMethodException {
        Constructor<TargetOutcome> threeArguments = TargetOutcome.class.getConstructor(String.class, Exception.class,
                Set.class);

        assertTrue(Modifier.isPublic(threeArguments.getModifiers()));
        //The public factory methods; skipped is covered by TestTargetOutcomeNotes
        assertEquals(Set.of("applied", "success", "failure", "skipped"),
                Set.of(TargetOutcome.class.getDeclaredMethods())
                .stream().filter(method -> Modifier.isStatic(method.getModifiers())
                        && Modifier.isPublic(method.getModifiers()))
                .map(Method::getName).collect(Collectors.toSet()));
    }

    @Test
    void theReasonsTravelInTheOrderOfTheAdapterAndCannotBeChanged() {
        Map<String, String> reasons = new LinkedHashMap<>();
        reasons.put("Global ID", "read-only");
        reasons.put("Created By", "fixed field of the tool");

        TargetOutcome outcome = TargetOutcome.applied(List.of("Status"), reasons);
        reasons.put("Later", "added later");

        assertEquals(List.of("Global ID", "Created By"), List.copyOf(outcome.notApplied().keySet()));
        assertEquals("read-only", outcome.notApplied().get("Global ID"));
        assertTrue(outcome.isApplied("Status"));
        assertFalse(outcome.isApplied("Global ID"));
        assertThrows(UnsupportedOperationException.class, () -> outcome.notApplied().put("x", "y"));
    }

    /**
     * A reason without a field, a field without a reason, or an applied field
     * with a reason violates the contract.
     */
    @Test
    void aReasonWithoutFieldOrAFieldBothAppliedAndNotIsRefused() {
        Map<String, String> withoutField = new LinkedHashMap<>();
        withoutField.put(null, "read-only");
        Map<String, String> withoutReason = new LinkedHashMap<>();
        withoutReason.put("Global ID", " ");

        assertThrows(IllegalArgumentException.class, () -> TargetOutcome.applied(List.of(), withoutField));
        assertThrows(IllegalArgumentException.class, () -> TargetOutcome.applied(List.of(), withoutReason));
        assertThrows(IllegalArgumentException.class,
                () -> TargetOutcome.applied(List.of("Status"), Map.of("Status", "read-only")));
    }
}
