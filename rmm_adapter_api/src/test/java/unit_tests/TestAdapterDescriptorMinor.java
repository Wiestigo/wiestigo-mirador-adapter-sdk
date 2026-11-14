package unit_tests;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.IdentifierKind;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The minor version of the contract: {@link AdapterApiVersion#MINOR} is 1,
 * and {@link AdapterDescriptor} has the eighth component
 * {@code apiMinorVersion} (since contract 1.1). The constructor with seven
 * arguments keeps the signature of the canonical constructor of contract 1.0
 * and sets 0: a plugin compiled against the contract jar of 1.0 links its
 * call unchanged and counts as built against minor version 0.
 */
class TestAdapterDescriptorMinor {

    private static final AdapterCapabilities CAPABILITIES = new AdapterCapabilities(Set.of(SyncDirection.TO_RMM),
            HierarchyModel.FLAT, false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null);

    @Test
    void theMinorVersionOfTheContractIsOne() {
        assertEquals(1, AdapterApiVersion.MINOR);
        assertEquals(1, AdapterApiVersion.MAJOR);
    }

    @Test
    void theDescriptorHasTheEighthComponentApiMinorVersion() {
        RecordComponent[] components = AdapterDescriptor.class.getRecordComponents();
        assertEquals(8, components.length);
        assertEquals("apiMinorVersion", components[7].getName());
        assertEquals(int.class, components[7].getType());

        AdapterDescriptor descriptor = new AdapterDescriptor("TOOL", "Tool", "1.0", AdapterApiVersion.MAJOR,
                CAPABILITIES, List.of(), List.of(), AdapterApiVersion.MINOR);
        assertEquals(AdapterApiVersion.MINOR, descriptor.apiMinorVersion());
    }

    /**
     * Binary compatibility: the call that a plugin compiled against the
     * contract jar of 1.0 contains is an {@code invokespecial} on {@code <init>}
     * with exactly this type. It resolves and yields minor version 0, as does
     * the call from newly compiled code.
     */
    @Test
    void theConstructorWithSevenArgumentsStaysAndSetsZero() throws Throwable {
        MethodHandle sevenArguments = MethodHandles.publicLookup().findConstructor(AdapterDescriptor.class,
                MethodType.methodType(void.class, String.class, String.class, String.class, int.class,
                        AdapterCapabilities.class, List.class, List.class));
        AdapterDescriptor linked = (AdapterDescriptor) sevenArguments.invoke("TOOL", "Tool", "1.0",
                AdapterApiVersion.MAJOR, CAPABILITIES, List.<ConfigKey>of(), List.<IdentifierKind>of());
        assertEquals(0, linked.apiMinorVersion());

        AdapterDescriptor compiled = new AdapterDescriptor("TOOL", "Tool", "1.0", AdapterApiVersion.MAJOR,
                CAPABILITIES, null, null);
        assertEquals(0, compiled.apiMinorVersion());
        assertEquals(new AdapterDescriptor("TOOL", "Tool", "1.0", AdapterApiVersion.MAJOR, CAPABILITIES, List.of(),
                List.of(), 0), compiled, "the seven arguments are the canonical ones with minor 0");
        assertTrue(compiled.configKeys().isEmpty() && compiled.identifierKinds().isEmpty(),
                "the checks of the canonical constructor apply");
    }

    @Test
    void theMinorVersionCountsInEqualityAndIsNeverNegative() {
        AdapterDescriptor zero = new AdapterDescriptor("TOOL", "Tool", "1.0", 1, CAPABILITIES, List.of(), List.of());
        AdapterDescriptor one = new AdapterDescriptor("TOOL", "Tool", "1.0", 1, CAPABILITIES, List.of(), List.of(), 1);
        assertNotEquals(zero, one);

        IllegalArgumentException negative = assertThrows(IllegalArgumentException.class,
                () -> new AdapterDescriptor("TOOL", "Tool", "1.0", 1, CAPABILITIES, List.of(), List.of(), -1));
        assertTrue(negative.getMessage().contains("apiMinorVersion"), negative.getMessage());
    }
}
