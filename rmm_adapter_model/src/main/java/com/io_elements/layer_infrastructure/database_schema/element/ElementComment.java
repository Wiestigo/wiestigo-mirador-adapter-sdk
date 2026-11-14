package com.io_elements.layer_infrastructure.database_schema.element;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.ProjectId;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementField;
import lombok.Getter;
import lombok.Setter;

import java.util.Collection;

/**
 * A comment on an artifact in the external tool. The stored collection and
 * field names are fixed; the host's store depends on them.
 */
@Getter
@Setter
public class ElementComment extends Element {

    /** The artifact in the external tool that the comment belongs to. */
    private ElementId commentToItemId;
    // Identifies cached comments per project; not needed for the synchronization itself
    private ProjectId projectId;

    public ElementComment() {
    }

    public ElementComment(ElementId id, ElementName name, ElementLabel label, Collection<ElementField<?>> fields, ElementId commentToItemId) {
        super(id, name, label, fields);
        this.commentToItemId = commentToItemId;
    }

    /** Descriptor form that carries only the identity (name, label), for example as a mapping key. */
    public static ElementComment ofNameAndLabel(ElementName name, ElementLabel label) {
        ElementComment comment = new ElementComment();
        comment.setName(name);
        comment.setLabel(label);
        return comment;
    }

    public String getCommentToItemIdValue() {
        return commentToItemId == null ? null : commentToItemId.getId();
    }
}
