package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.IdentifierKind;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.TckAdapterContext;
import com.rmm.adapter.tck.TckViolation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the case {@code theBindingDeclarationOnlyNarrowsItsType}. The TCK reports what the host records as
 * {@code DEFECT INSTANCE_INCONSISTENT}: wider capabilities, {@code null} instead of an {@code Optional}, a
 * key that only the type may declare, too many identifier kinds. A binding that only narrows passes. The
 * TCK's {@code config()} also reads the keys of the binding once they are declared.
 */
public class TestBindingDeclarationContract {

    private static final AdapterCapabilities OF_THE_TYPE = new AdapterCapabilities(
            Set.of(SyncDirection.TO_RMM), HierarchyModel.PATH_ATTRIBUTE, false, false, false, false,
            SyncPhase.artifactsOnly(), Set.of(), Set.of(), null);

    /** What the bound instance declares. */
    private record Declaration(Supplier<Optional<AdapterCapabilities>> capabilities, List<ConfigKey> keys,
                               Optional<List<IdentifierKind>> kinds) {
    }

    private static ToolAdapter adapterDeclaring(Declaration declaration) {
        return new ToolAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return new AdapterDescriptor("BINDTEST", "Binding Test", "0.1", AdapterApiVersion.MAJOR,
                        OF_THE_TYPE, List.of(), List.of());
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

                    @Override
                    public Optional<AdapterCapabilities> capabilities() {
                        return declaration.capabilities().get();
                    }

                    @Override
                    public List<ConfigKey> connectionKeys() {
                        return declaration.keys();
                    }

                    @Override
                    public Optional<List<IdentifierKind>> identifierKinds() {
                        return declaration.kinds();
                    }
                };
            }
        };
    }

    private static AdapterContractTest tckFor(Declaration declaration) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return adapterDeclaring(declaration);
            }

            @Override
            protected Map<String, String> configuration() {
                return Map.of();
            }
        };
    }

    private static String violationOf(Declaration declaration) {
        return assertThrows(TckViolation.class, () -> tckFor(declaration).theBindingDeclarationOnlyNarrowsItsType())
                .getMessage();
    }

    @Test
    public void aBindingThatOnlyNarrowsPasses() {
        AdapterCapabilities flat = new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
                false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null);
        assertDoesNotThrow(() -> tckFor(new Declaration(() -> Optional.of(flat),
                List.of(ConfigKey.optional("project.filter", ConfigType.STRING, "", "Filter"),
                        ConfigKey.required("service.token", ConfigType.SECRET, "Access")),
                Optional.of(List.of(IdentifierKind.of("wid", null, "(WI-\\d+)", "Identifier").withSample("WI-7")))))
                .theBindingDeclarationOnlyNarrowsItsType());
        assertDoesNotThrow(() -> tckFor(new Declaration(Optional::empty, List.of(), Optional.empty()))
                .theBindingDeclarationOnlyNarrowsItsType(), "without a declaration of its own");
    }

    @Test
    public void aWiderOrNullDeclarationIsAViolation() {
        AdapterCapabilities wider = new AdapterCapabilities(Set.of(SyncDirection.TO_RMM, SyncDirection.FROM_RMM),
                HierarchyModel.NATIVE_TREE, false, false, false, false, SyncPhase.artifactsOnly(),
                SyncPhase.artifactsOnly(), Set.of(), null);
        String message = violationOf(new Declaration(() -> Optional.of(wider), List.of(), Optional.empty()));
        assertTrue(message.contains("directions [FROM_RMM]") && message.contains("hierarchyModel NATIVE_TREE"),
                message);

        assertTrue(violationOf(new Declaration(() -> null, List.of(), Optional.empty()))
                .contains("capabilities() returned null"));
        assertTrue(violationOf(new Declaration(Optional::empty, null, Optional.empty()))
                .contains("connectionKeys() returned null"));
        assertTrue(violationOf(new Declaration(Optional::empty, List.of(), null))
                .contains("identifierKinds() returned null"));
    }

    @Test
    public void aKeyOnlyTheTypeMayDeclareOrTooManyKindsAreAViolation() {
        String url = violationOf(new Declaration(Optional::empty,
                List.of(ConfigKey.optional("service.url", ConfigType.URL, "", "Address")), Optional.empty()));
        assertTrue(url.contains("<service.url> is of type URL"), url);

        String host = violationOf(new Declaration(Optional::empty,
                List.of(ConfigKey.optional("project-spaces", ConfigType.STRING, "", "Name of the host")),
                Optional.empty()));
        assertTrue(host.contains("already declared by the host"), host);

        List<IdentifierKind> many = new ArrayList<>();
        for (int index = 0; index <= 32; index++) {
            many.add(IdentifierKind.of("kind" + index, null, null, "Kind " + index));
        }
        String kinds = violationOf(new Declaration(Optional::empty, List.of(), Optional.of(many)));
        assertTrue(kinds.contains("33 identifier kinds, at most 32"), kinds);

        String pattern = violationOf(new Declaration(Optional::empty, List.of(), Optional.of(List.of(
                IdentifierKind.of("wid", null, "(W\\d+)" + "x?".repeat(126), "Identifier")))));
        assertTrue(pattern.contains("a pattern of 258 characters (at most 256)"), pattern);
    }

    /** The TCK's {@code config()} reads a key of the binding only once it is declared, as the host does. */
    @Test
    public void theConfigOfTheTckReadsTheKeysOfTheBindingOnceDeclared() {
        AdapterDescriptor descriptor = new AdapterDescriptor("BINDTEST", "Binding Test", "0.1",
                AdapterApiVersion.MAJOR, OF_THE_TYPE,
                List.of(ConfigKey.optional("home.filter", ConfigType.STRING, "type", "Filter of the type")), List.of());
        TckAdapterContext context = TckAdapterContext.of(descriptor, Map.of("project.filter", "PRJ-A",
                "page.limit", "25"));
        try {
            assertThrows(IllegalArgumentException.class, () -> context.config().string("project.filter"));

            context.declareConnectionKeys(List.of(
                    ConfigKey.optional("project.filter", ConfigType.STRING, "", "Filter"),
                    ConfigKey.optional("page.limit", ConfigType.INTEGER, "50", "Pages"),
                    ConfigKey.optional("strict", ConfigType.BOOLEAN, "true", "Strict"),
                    ConfigKey.optional("home.filter", ConfigType.INTEGER, "1", "Name of the type"),
                    ConfigKey.optional("service.url", ConfigType.URL, "", "only the type")));
            assertEquals("PRJ-A", context.config().string("project.filter"));
            assertEquals(25, context.config().integer("page.limit"));
            assertTrue(context.config().bool("strict"), "the default");
            assertEquals("type", context.config().string("home.filter"), "a name of the type stays the type's");
            assertThrows(IllegalArgumentException.class, () -> context.config().string("service.url"),
                    "a key a binding may not declare stays undeclared");

            context.declareConnectionKeys(List.of());
            assertThrows(IllegalArgumentException.class, () -> context.config().string("project.filter"),
                    "each declaration replaces the previous one");
        } finally {
            context.release();
        }
    }

    /**
     * A required key of the binding without a value is reported as missing, with the key's name, like every
     * other value the configuration cannot deliver.
     */
    @Test
    public void aRequiredKeyOfTheBindingWithoutAValueIsReportedAsMissing() {
        AdapterDescriptor descriptor = new AdapterDescriptor("BINDTEST", "Binding Test", "0.1",
                AdapterApiVersion.MAJOR, OF_THE_TYPE, List.of(), List.of());
        TckAdapterContext context = TckAdapterContext.of(descriptor, Map.of());
        try {
            context.declareConnectionKeys(List.of(ConfigKey.required("strict", ConfigType.BOOLEAN, "Strict")));

            IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                    () -> context.config().bool("strict"));

            assertTrue(missing.getMessage().contains("<strict> is missing"), missing.getMessage());
        } finally {
            context.release();
        }
    }
}
