package unit_tests;

import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.BulkOperation;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.PathHierarchy;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capability declaration rejects contradictory values instead of leaving
 * them to the host: a direction without phases, phases without a direction,
 * bulk promises or link update without the declared write phase.
 */
class TestAdapterCapabilities {

    private static AdapterCapabilities capabilities(Set<SyncDirection> directions, Set<SyncPhase> reads,
                                                    Set<SyncPhase> writes) {
        return new AdapterCapabilities(directions, HierarchyModel.FLAT, false, false, false, false,
                reads, writes, Set.of(), null);
    }

    @Test
    void atLeastOneDirectionIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> capabilities(Set.of(), Set.of(), Set.of()));
    }

    @Test
    void theHierarchyModelIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> new AdapterCapabilities(
                Set.of(SyncDirection.TO_RMM), null, false, false, false, false,
                SyncPhase.artifactsOnly(), Set.of(), Set.of(), null));
        assertDoesNotThrow(() -> new AdapterCapabilities(
                Set.of(SyncDirection.TO_RMM), HierarchyModel.PATH_ATTRIBUTE,
                false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null));
    }

    @Test
    void pathHierarchyRequiresAttributeAndSeparator() {
        assertThrows(IllegalArgumentException.class, () -> new PathHierarchy(null, "Path", "/"));
        assertThrows(IllegalArgumentException.class, () -> new PathHierarchy("customfield_1", " ", "/"));
        assertThrows(IllegalArgumentException.class, () -> new PathHierarchy("customfield_1", "Path", ""));
        assertDoesNotThrow(() -> new PathHierarchy("customfield_10212", "Test Repository Path", "/"));
    }

    /** Every declared direction declares its phases, at least the artifacts; without a direction, no phases. */
    @Test
    void everyDirectionDeclaresAtLeastTheArtifacts() {
        assertThrows(IllegalArgumentException.class,
                () -> capabilities(Set.of(SyncDirection.TO_RMM), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> capabilities(Set.of(SyncDirection.TO_RMM), Set.of(SyncPhase.COMMENTS), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> capabilities(Set.of(SyncDirection.FROM_RMM), Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> capabilities(Set.of(SyncDirection.TO_RMM),
                SyncPhase.artifactsOnly(), SyncPhase.artifactsOnly()), "writes without FROM_RMM");
        assertThrows(IllegalArgumentException.class, () -> capabilities(Set.of(SyncDirection.FROM_RMM),
                SyncPhase.artifactsOnly(), SyncPhase.artifactsOnly()), "reads without TO_RMM");
        assertDoesNotThrow(() -> capabilities(Set.of(SyncDirection.TO_RMM), SyncPhase.artifactsOnly(), Set.of()));
        assertDoesNotThrow(() -> capabilities(Set.of(SyncDirection.TO_RMM, SyncDirection.FROM_RMM),
                SyncPhase.all(), SyncPhase.artifactsOnly()));
    }

    @Test
    void readsAndWritesAnswerPerPhase() {
        AdapterCapabilities capabilities = capabilities(Set.of(SyncDirection.TO_RMM, SyncDirection.FROM_RMM),
                Set.of(SyncPhase.ARTIFACTS, SyncPhase.COMMENTS), SyncPhase.artifactsOnly());
        assertTrue(capabilities.reads(SyncPhase.COMMENTS));
        assertFalse(capabilities.reads(SyncPhase.RELATIONSHIPS));
        assertTrue(capabilities.writes(SyncPhase.ARTIFACTS));
        assertFalse(capabilities.writes(SyncPhase.COMMENTS));
    }

    /** A bulk promise or a link update without the declared write phase is a contradiction. */
    @Test
    void bulkPromisesAndLinkUpdateNeedTheDeclaredPhase() {
        assertThrows(IllegalArgumentException.class, () -> new AdapterCapabilities(
                Set.of(SyncDirection.FROM_RMM), HierarchyModel.FLAT, false, false, false, false,
                Set.of(), SyncPhase.artifactsOnly(), Set.of(BulkOperation.COMMENT_CREATE), null));
        assertThrows(IllegalArgumentException.class, () -> new AdapterCapabilities(
                Set.of(SyncDirection.FROM_RMM), HierarchyModel.FLAT, true, false, false, false,
                Set.of(), SyncPhase.artifactsOnly(), Set.of(), null));
        assertDoesNotThrow(() -> new AdapterCapabilities(
                Set.of(SyncDirection.FROM_RMM), HierarchyModel.FLAT, true, false, false, false,
                Set.of(), SyncPhase.all(), Set.of(BulkOperation.COMMENT_CREATE), null));
    }
}
