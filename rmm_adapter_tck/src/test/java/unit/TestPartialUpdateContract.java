package unit;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifactLocation;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the partial update case ({@code aDeclaredPartialUpdateLeavesTheOtherFieldsUntouched}). The TCK sends
 * what the host sends: the partial update carries name, label and location of the round-trip artifact and
 * one of its fields, because the host's write payload only trims the fields. An adapter that declares
 * {@code supportsPartialUpdate} but supplies a {@code roundTripArtifact()} with fewer than two fields fails
 * instead of being skipped.
 *
 * <p>Runs against the in-memory reference tool, which declares the capability, with a writer that records
 * the updates.</p>
 */
public class TestPartialUpdateContract {

    /** The location of the template: distinctive, so that a missing or foreign one is noticed. */
    private static final String PARENT = "PARENT-7";

    private static ElementArtifact template(int fields) {
        ElementArtifact artifact = ElementArtifact.ofNameAndLabel(new ElementName("Requirement"),
                new ElementLabel("Requirement"));
        artifact.setLocation(new ElementArtifactLocation(PARENT, true, 3, null));
        if (fields >= 1) {
            artifact.addField(new ElementFieldString(new ElementName("name"), new ElementLabel("Name"), "Brake"));
        }
        if (fields >= 2) {
            artifact.addField(new ElementFieldString(new ElementName("description"), new ElementLabel("Description"),
                    "The brake brakes."));
        }
        return artifact;
    }

    /** The reference tool with a writer that records every {@code updateArtifact} in {@code updates}. */
    private static ToolAdapter recording(List<ElementArtifact> updates) {
        return InterceptingTool.ofWriterHooks(TestInMemoryAdapterContract.seeded(), (writer, hook, parameters) -> {
            if (hook.getName().equals("updateArtifact")) {
                updates.add((ElementArtifact) parameters[1]);
            }
            return InterceptingTool.forward(writer, hook, parameters);
        });
    }

    /** The inherited contract cases against the recording tool, with a template of {@code fields} fields. */
    private static AdapterContractTest tckFor(List<ElementArtifact> updates, int fields) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return recording(updates);
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "MEM-PRJ"));
            }

            @Override
            protected Optional<ElementArtifact> roundTripArtifact() {
                return Optional.of(template(fields));
            }
        };
    }

    @Test
    void thePartialUpdateCarriesNameLabelAndLocationOfTheTemplateAndOneField() {
        List<ElementArtifact> updates = new CopyOnWriteArrayList<>();

        assertDoesNotThrow(() -> tckFor(updates, 2).aDeclaredPartialUpdateLeavesTheOtherFieldsUntouched());

        assertEquals(1, updates.size(), "exactly one partial update: " + updates);
        ElementArtifact partial = updates.get(0);
        assertNotNull(partial.getIdValue(), "with the identifier of the created artifact");
        assertEquals("Requirement", partial.getNameValue(),
                "the name of the template, which the host always sends");
        assertEquals("Requirement", partial.getLabelValue(),
                "the label of the template (the type in the tool), which the host always sends");
        assertNotNull(partial.getLocation(), "the location of the template, which the host always sends");
        assertEquals(PARENT, partial.getLocation().getParentID());
        assertTrue(partial.getLocation().isParentIsRoot());
        assertEquals(3, partial.getLocation().getOrderIndex());
        assertEquals(1, partial.getFields().size(), "exactly one of its fields: " + partial.getFields().keySet());
    }

    @Test
    void aDeclaredPartialUpdateWithFewerThanTwoRoundTripFieldsIsAViolationNotASkip() {
        for (int fields : new int[]{0, 1}) {
            List<ElementArtifact> updates = new CopyOnWriteArrayList<>();

            TckViolation violation = assertThrows(TckViolation.class,
                    () -> tckFor(updates, fields).aDeclaredPartialUpdateLeavesTheOtherFieldsUntouched(),
                    fields + " field(s): not skipped");

            assertTrue(violation.getMessage().contains("supportsPartialUpdate")
                    && violation.getMessage().contains("at least two")
                    && violation.getMessage().contains(fields + " field(s)"), violation.getMessage());
            assertTrue(updates.isEmpty(), "nothing written: " + updates);
        }
    }
}
