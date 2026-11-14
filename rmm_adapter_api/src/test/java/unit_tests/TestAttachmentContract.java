package unit_tests;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.database_schema.element.ElementAttachment;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AttachmentContent;
import com.rmm.adapter.api.AttachmentContentSource;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The attachment contract: the fourth phase, its throwing defaults and the
 * byte channel with a length.
 */
class TestAttachmentContract {

    private static final SyncRunContext RUN = new SyncRunContext(UUID.randomUUID(), "PRJ-1");

    /** The shorthand all() covers only the three element phases; attachments are declared explicitly. */
    @Test
    void attachmentsAreAFourthPhaseThatAllDoesNotImply() {
        assertFalse(SyncPhase.all().contains(SyncPhase.ATTACHMENTS));
        assertTrue(SyncPhase.allWithAttachments().contains(SyncPhase.ATTACHMENTS));
        assertEquals(4, SyncPhase.allWithAttachments().size());
        // VARIABILITY is a fifth phase. Like attachments it is declared
        // explicitly: allWithAttachments() does not include it either.
        assertFalse(SyncPhase.allWithAttachments().contains(SyncPhase.VARIABILITY));
        assertEquals(5, SyncPhase.values().length);
    }

    @Test
    void theCapabilitiesAcceptAttachmentsNextToArtifacts() {
        assertDoesNotThrow(() -> new AdapterCapabilities(Set.of(SyncDirection.TO_RMM, SyncDirection.FROM_RMM),
                HierarchyModel.FLAT, false, false, false, false,
                Set.of(SyncPhase.ARTIFACTS, SyncPhase.ATTACHMENTS), SyncPhase.allWithAttachments(), Set.of(), null));
        //Without artifacts there is nothing an attachment could belong to
        assertThrows(IllegalArgumentException.class, () -> new AdapterCapabilities(Set.of(SyncDirection.TO_RMM),
                HierarchyModel.FLAT, false, false, false, false,
                Set.of(SyncPhase.ATTACHMENTS), Set.of(), Set.of(), null));
    }

    /** The defaults stand for "not declared" and name the phase, as for comments. */
    @Test
    void theDefaultHooksThrowAndNameThePhase() {
        SourceReader reader = (context, scope, cursor) -> null;
        TargetWriter writer = new ArtifactOnlyWriter();

        AdapterException read = assertThrows(AdapterException.class,
                () -> reader.readAttachments(RUN, ReadScope.full(), null));
        AdapterException open = assertThrows(AdapterException.class, () -> reader.openAttachment(RUN, "A-1"));
        AdapterException create = assertThrows(AdapterException.class,
                () -> writer.createAttachment(RUN, attachment(), () -> content("x")));
        AdapterException delete = assertThrows(AdapterException.class,
                () -> writer.deleteAttachment(RUN, attachment()));

        for (AdapterException failure : new AdapterException[]{read, open, create, delete}) {
            assertTrue(failure.getMessage().contains("ATTACHMENTS"), failure.getMessage());
        }
    }

    /** The byte channel: the length is known before the first byte, and closing closes the stream. */
    @Test
    void theContentCarriesItsLengthAndClosesItsStream() throws Exception {
        boolean[] closed = {false};
        InputStream stream = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };

        try (AttachmentContent content = new AttachmentContent(stream, 5, "text/plain")) {
            assertEquals(5, content.byteCount());
            assertEquals("text/plain", content.contentType());
        }

        assertTrue(closed[0], "close() must close the stream");
        assertThrows(IllegalArgumentException.class, () -> new AttachmentContent(stream, -1, null));
        assertThrows(NullPointerException.class, () -> new AttachmentContent(null, 0, null));
    }

    /** A source returns a fresh stream from the first byte on every open. */
    @Test
    void aContentSourceOpensAFreshStreamEveryTime() throws Exception {
        AttachmentContentSource source = () -> content("ab");

        try (AttachmentContent first = source.open(); AttachmentContent second = source.open()) {
            assertEquals("ab", new String(first.stream().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("ab", new String(second.stream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** The transfer object: the file name is both name and label; the element holds no bytes. */
    @Test
    void theTransferObjectCarriesMetadataOnly() {
        ElementAttachment attachment = attachment();

        assertEquals("A-1", attachment.getIdValue());
        assertEquals("REQ-7", attachment.getParentIdValue());
        assertEquals("evidence.pdf", attachment.getFileName());
        assertEquals("evidence.pdf", attachment.getName().getName());
        assertEquals("evidence.pdf", attachment.getLabel().getLabel());
        assertEquals(-1, attachment.getByteCount(), "an unknown length is -1, not 0");

        attachment.setFileName("standard.pdf");
        assertEquals("standard.pdf", attachment.getLabel().getLabel(), "the file name also sets name and label");
    }

    /**
     * Two attachments with the same name on different artifacts are different.
     * The base class would treat them as the same (descriptor equality by name
     * and label), and a host that keeps attachments in sets would never see one
     * of them.
     */
    @Test
    void sameFileNameAtDifferentParentsAreDifferentAttachments() {
        ElementAttachment atSeven = new ElementAttachment(new ElementId("A-1"), new ElementId("REQ-7"), "evidence.pdf");
        ElementAttachment atEight = new ElementAttachment(new ElementId("A-2"), new ElementId("REQ-8"), "evidence.pdf");
        ElementAttachment sameAgain = new ElementAttachment(new ElementId("A-1"), new ElementId("REQ-7"),
                "evidence.pdf");

        assertFalse(atSeven.equals(atEight));
        assertEquals(atSeven, sameAgain);
        assertEquals(atSeven.hashCode(), sameAgain.hashCode());
        assertEquals(2, new HashSet<>(List.of(atSeven, atEight, sameAgain)).size());
    }

    private static ElementAttachment attachment() {
        return new ElementAttachment(new ElementId("A-1"), new ElementId("REQ-7"), "evidence.pdf");
    }

    private static AttachmentContent content(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new AttachmentContent(new ByteArrayInputStream(bytes), bytes.length, "text/plain");
    }
}
