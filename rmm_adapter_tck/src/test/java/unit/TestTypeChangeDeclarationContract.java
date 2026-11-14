package unit;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the check of the type change declaration. {@code supportsArtifactTypeChange} promises that the
 * hook {@code convertArtifact} is overridden; the TCK must report a declaration that still rests on the
 * throwing default.
 */
public class TestTypeChangeDeclarationContract {

    /** Overrides the promised hook. */
    private static final class ConvertingWriter extends SingleHookWriter {
        @Override
        public void convertArtifact(SyncRunContext context, ElementArtifact artifact) {
        }
    }

    private static ToolAdapter adapterWith(TargetWriter writer) {
        return new ToolAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return new AdapterDescriptor("TYPETEST", "Type Test", "0.1", AdapterApiVersion.MAJOR,
                        new AdapterCapabilities(Set.of(SyncDirection.FROM_RMM), HierarchyModel.FLAT,
                                false, true, false, false, Set.of(), SyncPhase.all(), Set.of(), null),
                        List.of(), List.of());
            }

            @Override
            public AdapterInstance bind(AdapterContext context) {
                return new AdapterInstance() {
                    @Override
                    public Optional<SourceReader> reader() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<TargetWriter> writer() {
                        return Optional.of(writer);
                    }
                };
            }
        };
    }

    private static AdapterContractTest tckFor(TargetWriter writer) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return adapterWith(writer);
            }
        };
    }

    @Test
    public void aDeclaredTypeChangeWithoutOverriddenHookIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class,
                () -> tckFor(new SingleHookWriter()).bindYieldsAnInstanceConsistentWithTheCapabilities());

        assertTrue(violation.getMessage().contains("supportsArtifactTypeChange"),
                "the message names the empty declaration: " + violation.getMessage());
        assertTrue(violation.getMessage().contains("convertArtifact"),
                "the message names the missing hook: " + violation.getMessage());
    }

    @Test
    public void anOverriddenConvertHookSatisfiesTheDeclaration() {
        assertDoesNotThrow(() ->
                tckFor(new ConvertingWriter()).bindYieldsAnInstanceConsistentWithTheCapabilities());
    }
}
