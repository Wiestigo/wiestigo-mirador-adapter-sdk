package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.IdentifierKind;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the identifier kind check: the TCK reports a kind whose pattern cannot read back its own format.
 * Otherwise every run would see a "changed" field.
 */
public class TestIdentifierKindContract {

    private static ToolAdapter adapterWith(IdentifierKind... kinds) {
        return new ToolAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return new AdapterDescriptor("KINDTEST", "Kind Test", "0.1", AdapterApiVersion.MAJOR,
                        new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
                                false, false, false, false, SyncPhase.all(), Set.of(), Set.of(), null),
                        List.of(), List.of(kinds));
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
                        return Optional.empty();
                    }
                };
            }
        };
    }

    private static AdapterContractTest tckFor(IdentifierKind... kinds) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return adapterWith(kinds);
            }

            @Override
            protected Map<String, String> configuration() {
                return Map.of();
            }
        };
    }

    @Test
    public void aWellFormedKindPasses() {
        assertDoesNotThrow(() -> tckFor(
                IdentifierKind.of("key", "Key", "([A-Z][A-Z0-9_]*-\\d+)", "issue key"),
                new IdentifierKind("link", null, "issues/([A-Z]+-\\d+)", "https://x/issues/{id}", null, "link"),
                IdentifierKind.of("gid", "Global ID", "(GID-\\d+)", "global id").withSample("GID-12345"),
                IdentifierKind.of("plain", "Plain", null, "whole value")).identifierKindsRoundTrip());
    }

    @Test
    public void aKindWhosePatternCannotReadItsOwnFormatIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(
                new IdentifierKind("key", "Key", "^([A-Z]+-\\d+)$", "[{id}]", "ABC-123", "anchored, bracketed"))
                .identifierKindsRoundTrip());
        //The message names the kind and shows the trip: what the format wrote and that the pattern read nothing
        String message = violation.getMessage();
        assertTrue(message.contains("<key>") && message.contains("writes <ABC-123> as <[ABC-123]>")
                && message.contains("reads it back as []"), message);
    }

    @Test
    public void aKindWhosePatternRejectsTheDefaultSampleMustDeclareOne() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(
                IdentifierKind.of("gid", "Global ID", "(GID-\\d+)", "no sample")).identifierKindsRoundTrip());
        //The message names the kind and its pattern and says how to declare the sample that is missing
        String message = violation.getMessage();
        assertTrue(message.contains("<gid>") && message.contains("needs a sample id its pattern <(GID-\\d+)>")
                && message.contains("withSample(...)"), message);
    }

    @Test
    public void aDescriptorRejectsDuplicateAndBuiltInKindNames() {
        assertThrows(IllegalArgumentException.class, () -> adapterWith(
                IdentifierKind.of("key", "Key", null, "a"), IdentifierKind.of("key", "Key", null, "b")).descriptor());
        assertThrows(IllegalArgumentException.class, () -> IdentifierKind.of("rmmId", null, null, "built in"));
        assertThrows(IllegalArgumentException.class, () -> new IdentifierKind("key", null, null, "no placeholder", null, null));
    }
}
