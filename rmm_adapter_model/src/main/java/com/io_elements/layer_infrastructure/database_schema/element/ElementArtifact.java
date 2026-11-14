package com.io_elements.layer_infrastructure.database_schema.element;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import lombok.Getter;
import lombok.Setter;

/**
 * An artifact (item, issue) in the external tool.
 *
 * <p>{@code location} is the position in the tool's hierarchy; {@code null}
 * for tools without a hierarchy.</p>
 */
@Getter
@Setter
public class ElementArtifact extends Element {

    private ElementArtifactLocation location;

    public ElementArtifact() {
    }

    /** Descriptor form that carries only the identity (name, label), for example as a mapping key. */
    public static ElementArtifact ofNameAndLabel(ElementName name, ElementLabel label) {
        ElementArtifact item = new ElementArtifact();
        item.setName(name);
        item.setLabel(label);
        return item;
    }
}
