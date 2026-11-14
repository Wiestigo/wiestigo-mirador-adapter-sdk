package com.io_elements.layer_infrastructure.database_schema.element;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import lombok.Getter;
import lombok.Setter;

/** An artifact type (item type, issue type) of the external tool. */
@Getter
@Setter
public class ElementArtifactType extends Element {

    public ElementArtifactType() {
    }

    public ElementArtifactType(ElementId id, ElementName name, ElementLabel label) {
        this.id = id;
        this.name = name;
        this.label = label;
    }
}
