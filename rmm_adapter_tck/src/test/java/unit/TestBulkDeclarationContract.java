package unit;

import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.BulkOperation;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetOutcome;
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
 * Tests the check of bulk declarations. A declared {@link BulkOperation} promises that the matching batch
 * hook is overridden; only then does the host pass a batch through as one call. The TCK must report a
 * declaration without the override.
 */
public class TestBulkDeclarationContract {

    /** Overrides the promised batch hook. */
    private static final class BatchHookWriter extends SingleHookWriter {
        @Override
        public List<TargetOutcome> createRelationships(SyncRunContext context,
                                                       List<ElementRelationship> relationships) {
            return relationships.stream().map(relationship -> TargetOutcome.success("r-1")).toList();
        }
    }

    private static ToolAdapter adapterWith(TargetWriter writer) {
        return new ToolAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return new AdapterDescriptor("BULKTEST", "Bulk Test", "0.1", AdapterApiVersion.MAJOR,
                        new AdapterCapabilities(Set.of(SyncDirection.FROM_RMM), HierarchyModel.FLAT,
                                false, false, false, false, Set.of(), SyncPhase.all(),
                                Set.of(BulkOperation.RELATIONSHIP_CREATE), null),
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
    public void aDeclaredBulkOperationWithoutOverriddenHookIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class,
                () -> tckFor(new SingleHookWriter()).bindYieldsAnInstanceConsistentWithTheCapabilities());

        assertTrue(violation.getMessage().contains("RELATIONSHIP_CREATE"),
                "the message names the empty declaration: " + violation.getMessage());
        assertTrue(violation.getMessage().contains("createRelationships"),
                "the message names the missing hook: " + violation.getMessage());
    }

    @Test
    public void anOverriddenBatchHookSatisfiesTheDeclaration() {
        assertDoesNotThrow(() ->
                tckFor(new BatchHookWriter()).bindYieldsAnInstanceConsistentWithTheCapabilities());
    }
}
