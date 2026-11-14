package unit;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementComment;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourcePage;
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
 * Tests the check of phase declarations. Declaring a phase in {@code reads} or {@code writes} promises that
 * the matching hooks are overridden, by the same rule in both directions. The TCK reports a declaration
 * that still rests on the API's throwing default; a hook that is overridden but not declared is allowed.
 */
public class TestPhaseDeclarationContract {

    /** Reads only artifacts. */
    private static class ArtifactReader implements SourceReader {
        @Override
        public SourcePage<ElementArtifact> readArtifacts(SyncRunContext context, ReadScope scope, PageCursor cursor) {
            return SourcePage.empty();
        }
    }

    private static final class FullReader extends ArtifactReader {
        @Override
        public SourcePage<ElementRelationship> readRelationships(SyncRunContext context, ReadScope scope,
                                                                 PageCursor cursor) {
            return SourcePage.empty();
        }

        @Override
        public SourcePage<ElementComment> readComments(SyncRunContext context, ReadScope scope, PageCursor cursor) {
            return SourcePage.empty();
        }
    }

    /** Reads the attachment pages but has no byte stream: half of the declared phase. */
    private static final class AttachmentPageOnlyReader extends ArtifactReader {
        @Override
        public SourcePage<com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment>
        readAttachments(SyncRunContext context, ReadScope scope, PageCursor cursor) {
            return SourcePage.empty();
        }
    }

    /** Declares variability but reads no feature selection: the phase would be empty. */
    private static final class VariabilityWriterOnlyReader extends ArtifactReader {
    }

    /** Writes trees and variants but not the selection per element: half of a product definition. */
    private static final class VariabilityTreesOnlyWriter extends ArtifactWriter {
        @Override
        public void writeFeatureTrees(SyncRunContext context, List<com.rmm.adapter.api.VariabilityTree> trees) {
        }

        @Override
        public void writeVariants(SyncRunContext context, List<com.rmm.adapter.api.VariabilityVariant> variants) {
        }
    }

    /** Creates attachments but cannot remove them: replacing one would be impossible. */
    private static final class AttachmentCreateOnlyWriter extends ArtifactWriter {
        @Override
        public String createAttachment(SyncRunContext context,
                                       com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment attachment,
                                       com.rmm.adapter.api.AttachmentContentSource content) {
            return "A-1";
        }
    }

    /** Writes only artifacts. */
    private static class ArtifactWriter implements TargetWriter {
        @Override
        public String createArtifact(SyncRunContext context, ElementArtifact artifact) {
            return "a-1";
        }

        @Override
        public TargetOutcome updateArtifact(SyncRunContext context, ElementArtifact artifact) {
            return TargetOutcome.success();
        }

        @Override
        public void updateArtifactHierarchy(SyncRunContext context, ElementArtifact artifact) {
        }

        @Override
        public void deleteArtifact(SyncRunContext context, String externalId) {
        }
    }

    /** Writes comments through {@code createComment}, the form without an outcome. */
    private static final class PlainCommentWriter extends ArtifactWriter {
        @Override
        public String createComment(SyncRunContext context, ElementComment comment) {
            return "c-1";
        }
    }

    /** Writes comments only through {@code createCommentWithOutcome}, the form that the host calls. */
    private static final class OutcomeCommentWriter extends ArtifactWriter {
        @Override
        public TargetOutcome createCommentWithOutcome(SyncRunContext context, ElementComment comment) {
            return TargetOutcome.success("c-1");
        }
    }

    private static final class RelationshipWriter extends ArtifactWriter {
        @Override
        public String createRelationship(SyncRunContext context, ElementRelationship relationship) {
            return "r-1";
        }

        @Override
        public void deleteRelationship(SyncRunContext context, String externalId) {
        }
    }

    private static ToolAdapter adapterWith(Set<SyncPhase> reads, Set<SyncPhase> writes, boolean linkUpdate,
                                           SourceReader reader, TargetWriter writer) {
        return new ToolAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return new AdapterDescriptor("PHASETEST", "Phase Test", "0.1", AdapterApiVersion.MAJOR,
                        new AdapterCapabilities(Set.of(SyncDirection.TO_RMM, SyncDirection.FROM_RMM),
                                HierarchyModel.FLAT, linkUpdate, false, false, false, reads, writes, Set.of(), null),
                        List.of(), List.of());
            }

            @Override
            public AdapterInstance bind(AdapterContext context) {
                return new AdapterInstance() {
                    @Override
                    public Optional<SourceReader> reader() {
                        return Optional.of(reader);
                    }

                    @Override
                    public Optional<TargetWriter> writer() {
                        return Optional.of(writer);
                    }
                };
            }
        };
    }

    private static AdapterContractTest tckFor(ToolAdapter adapter) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return adapter;
            }
        };
    }

    @Test
    public void aDeclaredReadPhaseWithoutOverriddenReaderIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(adapterWith(
                Set.of(SyncPhase.ARTIFACTS, SyncPhase.COMMENTS), SyncPhase.artifactsOnly(), false,
                new ArtifactReader(), new ArtifactWriter()))
                .bindYieldsAnInstanceConsistentWithTheCapabilities());

        assertTrue(violation.getMessage().contains("reads COMMENTS"), violation.getMessage());
        assertTrue(violation.getMessage().contains("readComments"), violation.getMessage());
    }

    /**
     * Writing comments: either form of the hook is enough. The host calls only
     * {@code createCommentWithOutcome}, whose default delegates to {@code createComment}. With neither
     * overridden, the declaration is empty.
     */
    @Test
    public void aDeclaredCommentWriteNeedsEitherFormOfTheHook() {
        Set<SyncPhase> comments = Set.of(SyncPhase.ARTIFACTS, SyncPhase.COMMENTS);
        assertDoesNotThrow(() -> tckFor(adapterWith(SyncPhase.artifactsOnly(), comments, false,
                new ArtifactReader(), new OutcomeCommentWriter())).bindYieldsAnInstanceConsistentWithTheCapabilities());
        assertDoesNotThrow(() -> tckFor(adapterWith(SyncPhase.artifactsOnly(), comments, false,
                new ArtifactReader(), new PlainCommentWriter())).bindYieldsAnInstanceConsistentWithTheCapabilities());

        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(adapterWith(
                SyncPhase.artifactsOnly(), comments, false, new ArtifactReader(), new ArtifactWriter()))
                .bindYieldsAnInstanceConsistentWithTheCapabilities());
        assertTrue(violation.getMessage().contains("writes COMMENTS"), violation.getMessage());
        assertTrue(violation.getMessage().contains("createCommentWithOutcome")
                && violation.getMessage().contains("createComment("), violation.getMessage());
    }

    @Test
    public void aDeclaredWritePhaseWithoutOverriddenWriterIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(adapterWith(
                SyncPhase.artifactsOnly(), Set.of(SyncPhase.ARTIFACTS, SyncPhase.RELATIONSHIPS), false,
                new ArtifactReader(), new ArtifactWriter()))
                .bindYieldsAnInstanceConsistentWithTheCapabilities());

        assertTrue(violation.getMessage().contains("writes RELATIONSHIPS"), violation.getMessage());
        assertTrue(violation.getMessage().contains("createRelationship"), violation.getMessage());
    }

    /** Declaring relationship updates additionally promises {@code updateRelationship}. */
    @Test
    public void aDeclaredRelationshipUpdateNeedsTheUpdateHook() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(adapterWith(
                SyncPhase.artifactsOnly(), Set.of(SyncPhase.ARTIFACTS, SyncPhase.RELATIONSHIPS), true,
                new ArtifactReader(), new RelationshipWriter()))
                .bindYieldsAnInstanceConsistentWithTheCapabilities());

        assertTrue(violation.getMessage().contains("updateRelationship"), violation.getMessage());
    }

    /** Attachments need the page and the byte stream: without {@code openAttachment} the declaration is empty. */
    @Test
    public void aDeclaredAttachmentReadNeedsThePageAndTheByteChannel() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(adapterWith(
                Set.of(SyncPhase.ARTIFACTS, SyncPhase.ATTACHMENTS), SyncPhase.artifactsOnly(), false,
                new AttachmentPageOnlyReader(), new ArtifactWriter()))
                .bindYieldsAnInstanceConsistentWithTheCapabilities());

        assertTrue(violation.getMessage().contains("reads ATTACHMENTS"), violation.getMessage());
        assertTrue(violation.getMessage().contains("openAttachment"), violation.getMessage());
    }

    /** Attachments need create and delete: the host replaces an attachment by deleting and creating it. */
    @Test
    public void aDeclaredAttachmentWriteNeedsCreateAndDelete() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(adapterWith(
                SyncPhase.artifactsOnly(), Set.of(SyncPhase.ARTIFACTS, SyncPhase.ATTACHMENTS), false,
                new ArtifactReader(), new AttachmentCreateOnlyWriter()))
                .bindYieldsAnInstanceConsistentWithTheCapabilities());

        assertTrue(violation.getMessage().contains("writes ATTACHMENTS"), violation.getMessage());
        assertTrue(violation.getMessage().contains("deleteAttachment"), violation.getMessage());
    }

    /** Reading variability means delivering the feature selection per element. */
    @Test
    public void aDeclaredVariabilityReadNeedsTheElementFeatures() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(adapterWith(
                Set.of(SyncPhase.ARTIFACTS, SyncPhase.VARIABILITY), SyncPhase.artifactsOnly(), false,
                new VariabilityWriterOnlyReader(), new ArtifactWriter()))
                .bindYieldsAnInstanceConsistentWithTheCapabilities());

        assertTrue(violation.getMessage().contains("reads VARIABILITY"), violation.getMessage());
        assertTrue(violation.getMessage().contains("readElementFeatures"), violation.getMessage());
    }

    /** Writing variability means trees, variants and the feature selection per element. */
    @Test
    public void aDeclaredVariabilityWriteNeedsTreesVariantsAndElementFeatures() {
        TckViolation violation = assertThrows(TckViolation.class, () -> tckFor(adapterWith(
                SyncPhase.artifactsOnly(), Set.of(SyncPhase.ARTIFACTS, SyncPhase.VARIABILITY), false,
                new ArtifactReader(), new VariabilityTreesOnlyWriter()))
                .bindYieldsAnInstanceConsistentWithTheCapabilities());

        assertTrue(violation.getMessage().contains("writes VARIABILITY"), violation.getMessage());
        assertTrue(violation.getMessage().contains("writeElementFeatures"), violation.getMessage());
    }

    @Test
    public void declarationsBackedByOverriddenHooksPass() {
        assertDoesNotThrow(() -> tckFor(adapterWith(SyncPhase.all(), Set.of(SyncPhase.ARTIFACTS,
                SyncPhase.RELATIONSHIPS), false, new FullReader(), new RelationshipWriter()))
                .bindYieldsAnInstanceConsistentWithTheCapabilities());
        //Overridden but not declared: allowed (for example an adapter that only delegates)
        assertDoesNotThrow(() -> tckFor(adapterWith(SyncPhase.artifactsOnly(), SyncPhase.artifactsOnly(), false,
                new FullReader(), new RelationshipWriter()))
                .bindYieldsAnInstanceConsistentWithTheCapabilities());
    }
}
