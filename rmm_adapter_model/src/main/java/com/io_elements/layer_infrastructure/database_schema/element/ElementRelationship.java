package com.io_elements.layer_infrastructure.database_schema.element;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.ProjectId;
import lombok.Getter;
import lombok.Setter;

/**
 * A relationship between two artifacts in the external tool. The stored
 * collection and field names ({@code element_link}, {@code linkFromIssueId},
 * {@code linkToIssueId}) are fixed; the host's store depends on them.
 */
@Getter
@Setter
public class ElementRelationship extends Element {

    private ElementId fromItemId;
    private ElementId toItemId;
    // Needed for caching: a tool may deliver relationships together with the artifact that holds them,
    // and the project id is then the only way to find all cached relationships of a project
    private ProjectId projectId;

    public ElementRelationship() {
    }

    public ElementRelationship(ElementId id, ElementName name, ElementId fromItemId, ElementId toItemId) {
        setId(id);
        setName(name);
        setLabel(name == null ? null : new ElementLabel(name.getName()));
        setFromItemId(fromItemId);
        setToItemId(toItemId);
    }

    /** Descriptor form that carries only the identity (name, label), for example as a mapping key. */
    public static ElementRelationship ofNameAndLabel(ElementName name, ElementLabel label) {
        ElementRelationship relationship = new ElementRelationship();
        relationship.setName(name);
        relationship.setLabel(label);
        return relationship;
    }

    public String getFromItemIdValue() {
        return fromItemId == null ? null : fromItemId.getId();
    }

    public String getToItemIdValue() {
        return toItemId == null ? null : toItemId.getId();
    }
}
