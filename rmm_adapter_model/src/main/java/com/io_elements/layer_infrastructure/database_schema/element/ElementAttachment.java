package com.io_elements.layer_infrastructure.database_schema.element;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.ProjectId;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An attachment of an artifact in the external tool: the metadata, not the
 * bytes. The bytes travel through the byte channel of the adapter contract
 * ({@code SourceReader.openAttachment}, {@code AttachmentContentSource}), so
 * that even a very large attachment never sits in memory as an element.
 *
 * <p>Identity: {@code id} is the attachment's identifier in the tool;
 * {@code name} and {@code label} carry the file name. Equality differs from
 * the base class, which compares elements as descriptors over (name, label):
 * two attachments named "evidence.pdf" on different artifacts are different
 * attachments. Equal here means the same identifier, parent artifact and
 * file name, so that sets and maps of attachments never merge two that the
 * tool keeps apart. {@link #getParentId()} names the artifact in the tool
 * that the file is attached to.</p>
 *
 * <p>The host uses {@link #getContentHash()}, or the pair of
 * {@link #getByteCount()} and {@link #getModifiedAt()}, to decide whether it
 * has to fetch the bytes at all: it does not download again what it already
 * holds under the same hash, or under the same length and modification time.
 * A tool that provides no hash leaves it {@code null}.</p>
 */
@Getter
@Setter
public class ElementAttachment extends Element {

    /** The artifact in the external tool that the attachment belongs to. */
    private ElementId parentId;
    /**
     * Only for the cache of an adapter that collects attachments while it
     * reads the artifacts and hands them out page by page later, in the
     * attachment phase; it has no meaning for the synchronization itself.
     */
    private ProjectId projectId;
    /** The file name as the tool holds it; also the attachment's name in Mirador. */
    private String fileName;
    /** Media type as the tool states it; {@code null} if it has none. */
    private String contentType;
    /** Length in bytes; -1 if the tool does not state it in advance. */
    private long byteCount = -1;
    /** SHA-256 in hexadecimal notation if the tool provides it; otherwise {@code null}. */
    private String contentHash;
    /** Last modification of the file in the tool; {@code null} if unknown. */
    private Instant modifiedAt;
    /** Caption of the attachment in the tool; {@code null} if it has none. */
    private String description;

    public ElementAttachment() {
    }

    public ElementAttachment(ElementId id, ElementId parentId, String fileName) {
        super(id, new ElementName(fileName), new ElementLabel(fileName), List.of());
        this.parentId = parentId;
        this.fileName = fileName;
    }

    public String getParentIdValue() {
        return parentId == null ? null : parentId.getId();
    }

    /** Sets the file name and, with it, name and label (the descriptor identity). */
    public void setFileName(String fileName) {
        this.fileName = fileName;
        this.name = new ElementName(fileName);
        this.label = new ElementLabel(fileName);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ElementAttachment other)) {
            return false;
        }
        return Objects.equals(getIdValue(), other.getIdValue())
                && Objects.equals(getParentIdValue(), other.getParentIdValue())
                && Objects.equals(fileName, other.fileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getIdValue(), getParentIdValue(), fileName);
    }
}
