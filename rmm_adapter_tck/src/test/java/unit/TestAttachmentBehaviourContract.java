package unit;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AttachmentContent;
import com.rmm.adapter.api.AttachmentContentSource;
import com.rmm.adapter.api.ElementFailure;
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Negative tests for the TCK's attachment cases: each violation of the attachment contract is built on its
 * own and must fail the inherited case, and the faultless adapter passes both cases.
 */
public class TestAttachmentBehaviourContract {

    /** The one violation that a fake tool commits. */
    private enum Defect {
        /** Conforms to the contract. */
        NONE,
        /** An attachment that was read names no parent element. */
        NO_PARENT,
        /** The byte stream announces more than it delivers. */
        SHORT_STREAM,
        /** The byte stream announces less than it delivers. */
        LONG_STREAM,
        /** An element counts as completely read although reading it failed. */
        COMPLETE_DESPITE_FAILURE,
        /** {@code createAttachment} withholds the tool's identifier. */
        BLANK_CREATE_ID,
        /** The second delete fails with "not found". */
        DELETE_NOT_IDEMPOTENT
    }

    private static final byte[] SEED_BYTES = "seed".getBytes(StandardCharsets.UTF_8);

    /**
     * A fake tool with exactly one violation. Everything else conforms, so the inherited case fails because
     * of that violation and nothing else.
     */
    private static final class AttachmentTool implements ToolAdapter {

        private final Defect defect;
        private final Map<String, ElementArtifact> artifacts = new LinkedHashMap<>();
        private final Map<String, ElementAttachment> attachments = new LinkedHashMap<>();
        private final Map<String, byte[]> bytes = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        private AttachmentTool(Defect defect) {
            this.defect = defect;
            ElementArtifact seed = ElementArtifact.ofNameAndLabel(new ElementName("Requirement"),
                    new ElementLabel("Requirement"));
            seed.setId(new ElementId("ART-SEED"));
            artifacts.put("ART-SEED", seed);
            ElementAttachment attachment = new ElementAttachment(new ElementId("ATT-SEED"),
                    defect == Defect.NO_PARENT ? null : new ElementId("ART-SEED"), "seed.txt");
            attachment.setByteCount(SEED_BYTES.length);
            attachments.put("ATT-SEED", attachment);
            bytes.put("ATT-SEED", SEED_BYTES);
        }

        @Override
        public AdapterDescriptor descriptor() {
            return new AdapterDescriptor("ATTTEST", "Attachment Test", "0.1", AdapterApiVersion.MAJOR,
                    new AdapterCapabilities(Set.of(SyncDirection.TO_RMM, SyncDirection.FROM_RMM),
                            HierarchyModel.FLAT, false, false, false, false,
                            SyncPhase.allWithAttachments(), SyncPhase.allWithAttachments(), Set.of(), null),
                    List.of(), List.of());
        }

        @Override
        public AdapterInstance bind(AdapterContext context) {
            SourceReader reader = new SourceReader() {
                @Override
                public SourcePage<ElementArtifact> readArtifacts(SyncRunContext context, ReadScope scope,
                                                                 PageCursor cursor) {
                    return new SourcePage<>(List.copyOf(artifacts.values()), List.of(), artifacts.size(), null, true);
                }

                @Override
                public SourcePage<com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship>
                readRelationships(SyncRunContext context, ReadScope scope, PageCursor cursor) {
                    return SourcePage.empty();
                }

                @Override
                public SourcePage<com.io_elements.layer_infrastructure.database_schema.element.ElementComment>
                readComments(SyncRunContext context, ReadScope scope, PageCursor cursor) {
                    return SourcePage.empty();
                }

                @Override
                public SourcePage<ElementAttachment> readAttachments(SyncRunContext context, ReadScope scope,
                                                                     PageCursor cursor) {
                    List<ElementFailure> failures = defect == Defect.COMPLETE_DESPITE_FAILURE
                            ? List.of(ElementFailure.of("ART-SEED", "the attachments of this element are unreadable"))
                            : List.of();
                    List<ElementAttachment> page = new ArrayList<>(attachments.values());
                    return new SourcePage<>(page, failures, page.size() + failures.size(), null, true,
                            List.of(), List.copyOf(artifacts.keySet()));
                }

                @Override
                public AttachmentContent openAttachment(SyncRunContext context, String externalId)
                        throws AdapterException {
                    byte[] content = bytes.get(externalId);
                    if (content == null) {
                        throw new AdapterException("No attachment <" + externalId + ">");
                    }
                    //The violation: the announced length does not match the bytes
                    long announced = content.length;
                    if (defect == Defect.SHORT_STREAM) {
                        announced = content.length + 1;
                    } else if (defect == Defect.LONG_STREAM) {
                        announced = content.length - 1;
                    }
                    return new AttachmentContent(new ByteArrayInputStream(content), announced, "text/plain");
                }
            };
            TargetWriter writer = new TargetWriter() {
                @Override
                public String createArtifact(SyncRunContext context, ElementArtifact artifact) {
                    String externalId = "ART-" + sequence.incrementAndGet();
                    artifact.setId(new ElementId(externalId));
                    artifacts.put(externalId, artifact);
                    return externalId;
                }

                @Override
                public TargetOutcome updateArtifact(SyncRunContext context, ElementArtifact artifact) {
                    return TargetOutcome.applied(List.of());
                }

                @Override
                public void updateArtifactHierarchy(SyncRunContext context, ElementArtifact artifact) {
                }

                @Override
                public void deleteArtifact(SyncRunContext context, String externalId) {
                    artifacts.remove(externalId);
                }

                @Override
                public String createRelationship(SyncRunContext context,
                                                 com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship relationship) {
                    return "REL-" + sequence.incrementAndGet();
                }

                @Override
                public void deleteRelationship(SyncRunContext context, String externalId) {
                }

                @Override
                public String createComment(SyncRunContext context,
                                            com.io_elements.layer_infrastructure.database_schema.element.ElementComment comment) {
                    return "C-" + sequence.incrementAndGet();
                }

                @Override
                public String createAttachment(SyncRunContext context, ElementAttachment attachment,
                                               AttachmentContentSource content) throws AdapterException {
                    byte[] uploaded;
                    try (AttachmentContent opened = content.open()) {
                        uploaded = opened.stream().readAllBytes();
                    } catch (IOException e) {
                        throw new AdapterException("unreadable source", e);
                    }
                    String externalId = "ATT-" + sequence.incrementAndGet();
                    ElementAttachment stored = new ElementAttachment(new ElementId(externalId),
                            attachment.getParentId(), attachment.getFileName());
                    stored.setByteCount(uploaded.length);
                    attachments.put(externalId, stored);
                    bytes.put(externalId, uploaded);
                    //The violation: the tool has the file, but the host never learns its identifier
                    return defect == Defect.BLANK_CREATE_ID ? "  " : externalId;
                }

                @Override
                public void deleteAttachment(SyncRunContext context, ElementAttachment attachment)
                        throws AdapterException {
                    if (defect == Defect.DELETE_NOT_IDEMPOTENT && !attachments.containsKey(attachment.getIdValue())) {
                        throw new AdapterException("No attachment <" + attachment.getIdValue() + ">");
                    }
                    attachments.remove(attachment.getIdValue());
                    bytes.remove(attachment.getIdValue());
                }
            };
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
    }

    /** The inherited contract cases, run against a fake tool with exactly one violation. */
    private static AdapterContractTest tckFor(Defect defect) {
        AttachmentTool tool = new AttachmentTool(defect);
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return tool;
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "ATT-PRJ"));
            }

            @Override
            protected Optional<ElementArtifact> roundTripArtifact() {
                return Optional.of(ElementArtifact.ofNameAndLabel(new ElementName("Requirement"),
                        new ElementLabel("Requirement")));
            }
        };
    }

    @Test
    public void aFaultlessToolPassesBothAttachmentTests() {
        assertDoesNotThrow(() -> tckFor(Defect.NONE).readAttachmentsAnnouncesUsableMetadataAndExactStreams());
        assertDoesNotThrow(() -> tckFor(Defect.NONE).attachmentRoundTripCreatesFindsAndDeletesTheFile());
    }

    @Test
    public void anAttachmentWithoutAParentIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class,
                () -> tckFor(Defect.NO_PARENT).readAttachmentsAnnouncesUsableMetadataAndExactStreams());

        assertTrue(violation.getMessage().contains("no parent element"), violation.getMessage());
    }

    @Test
    public void aStreamShorterThanAnnouncedIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class,
                () -> tckFor(Defect.SHORT_STREAM).readAttachmentsAnnouncesUsableMetadataAndExactStreams());

        assertTrue(violation.getMessage().contains("openAttachment"), violation.getMessage());
        assertTrue(violation.getMessage().contains("announced"), violation.getMessage());
    }

    /**
     * A stream that delivers more than announced is detected too. The case reads exactly one byte past the
     * announced length, never the whole file: a tool that understates its length would break the host's
     * object store in the middle of a write.
     */
    @Test
    public void aStreamLongerThanAnnouncedIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class,
                () -> tckFor(Defect.LONG_STREAM).readAttachmentsAnnouncesUsableMetadataAndExactStreams());

        assertTrue(violation.getMessage().contains("delivered more"), violation.getMessage());
    }

    /** Completely read and failed at once: the host would delete what was merely unreadable. */
    @Test
    public void anElementThatFailedMustNotCountAsCompletelyRead() {
        TckViolation violation = assertThrows(TckViolation.class,
                () -> tckFor(Defect.COMPLETE_DESPITE_FAILURE)
                        .readAttachmentsAnnouncesUsableMetadataAndExactStreams());

        assertTrue(violation.getMessage().contains("completeParentIds"), violation.getMessage());
    }

    @Test
    public void createAttachmentWithoutTheToolIdIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class,
                () -> tckFor(Defect.BLANK_CREATE_ID).attachmentRoundTripCreatesFindsAndDeletesTheFile());

        assertTrue(violation.getMessage().contains("createAttachment must return the id"), violation.getMessage());
    }

    @Test
    public void aSecondDeleteThatFailsIsAViolation() {
        TckViolation violation = assertThrows(TckViolation.class,
                () -> tckFor(Defect.DELETE_NOT_IDEMPOTENT).attachmentRoundTripCreatesFindsAndDeletesTheFile());

        assertTrue(violation.getMessage().contains("already gone"), violation.getMessage());
    }
}
