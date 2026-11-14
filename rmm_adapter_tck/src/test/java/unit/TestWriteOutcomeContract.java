package unit;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the checks on the outcome of a write hook. Only creating a comment may answer "skipped"; at any
 * other hook the host fails the element. The fields not written ({@code notApplied}) must be fields that
 * were sent, like the applied ones. Runs against the in-memory reference tool, whose {@code updateArtifact}
 * reports a different outcome here.
 */
public class TestWriteOutcomeContract {

    private static ElementArtifact template() {
        ElementArtifact artifact = ElementArtifact.ofNameAndLabel(new ElementName("Requirement"),
                new ElementLabel("Requirement"));
        artifact.addField(new ElementFieldString(new ElementName("name"), new ElementLabel("Name"), "Brake"));
        artifact.addField(new ElementFieldString(new ElementName("description"), new ElementLabel("Description"),
                "The brake brakes."));
        return artifact;
    }

    /** The reference tool; its {@code updateArtifact} writes and reports what {@code answer} makes of the outcome. */
    private static ToolAdapter answering(UnaryOperator<TargetOutcome> answer) {
        return InterceptingTool.ofWriterHooks(TestInMemoryAdapterContract.seeded(), (writer, hook, parameters) -> {
            Object outcome = InterceptingTool.forward(writer, hook, parameters);
            return hook.getName().equals("updateArtifact") ? answer.apply((TargetOutcome) outcome) : outcome;
        });
    }

    private static AdapterContractTest tckFor(UnaryOperator<TargetOutcome> answer) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return answering(answer);
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "MEM-PRJ"));
            }

            @Override
            protected Optional<ElementArtifact> roundTripArtifact() {
                return Optional.of(template());
            }
        };
    }

    @Test
    void anUpdateThatAnswersSkippedIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class,
                () -> tckFor(written -> TargetOutcome.skipped("nothing to write")).updateAndDeleteAreIdempotent());

        assertTrue(violation.getMessage().contains("only creating a comment may be skipped"),
                violation.getMessage());
    }

    @Test
    void aFieldNotWrittenMustBeOneThatWasSent() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(written ->
                TargetOutcome.applied(written.appliedFields(), Map.of("Ghost", "not in this tool")))
                .roundTripCreatesFindsAndDeletesAnArtifact());

        assertTrue(violation.getMessage().contains("never given") && violation.getMessage().contains("Ghost"),
                violation.getMessage());
    }

    @Test
    void aSentFieldNotWrittenWithItsReasonPasses() {
        assertDoesNotThrow(() -> tckFor(written -> TargetOutcome.applied(List.of("Name"),
                Map.of("Description", "read only in this tool"))).roundTripCreatesFindsAndDeletesAnArtifact());
    }
}
