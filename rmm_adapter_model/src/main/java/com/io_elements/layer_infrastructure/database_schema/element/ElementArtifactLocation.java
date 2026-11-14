package com.io_elements.layer_infrastructure.database_schema.element;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Position of an {@link ElementArtifact} in the hierarchy of the external tool. */
@Getter
@Setter
@NoArgsConstructor
public class ElementArtifactLocation {

    private String parentID;
    private boolean parentIsRoot;
    private int orderIndex;
    private String precedingElementID;

    public ElementArtifactLocation(String parentID, int orderIndex) {
        this.parentID = parentID;
        this.orderIndex = orderIndex;
    }

    public ElementArtifactLocation(String parentID, boolean parentIsRoot, int orderIndex, String precedingElementID) {
        this.parentID = parentID;
        this.parentIsRoot = parentIsRoot;
        this.orderIndex = orderIndex;
        this.precedingElementID = precedingElementID;
    }
}
