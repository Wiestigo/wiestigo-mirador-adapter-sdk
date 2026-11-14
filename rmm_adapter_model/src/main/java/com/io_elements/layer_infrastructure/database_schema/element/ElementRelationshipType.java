package com.io_elements.layer_infrastructure.database_schema.element;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import lombok.Getter;
import lombok.Setter;

/**
 * A relationship type of the external tool. The stored collection name
 * ({@code element_link_type}) is fixed; the host's store depends on it.
 */
@Getter
@Setter
public class ElementRelationshipType extends Element {

    String label_inward;
    String label_outward;

    public ElementRelationshipType() {
    }

    public ElementRelationshipType(ElementId id, ElementName name, ElementLabel label, String label_inward, String label_outward) {
        this.id = id;
        this.name = name;
        this.label = label;
        this.label_inward = label_inward;
        this.label_outward = label_outward;
    }
}
