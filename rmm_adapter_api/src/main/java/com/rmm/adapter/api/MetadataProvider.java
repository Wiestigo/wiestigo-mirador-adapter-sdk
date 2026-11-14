package com.rmm.adapter.api;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifactType;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationshipType;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldType;

import java.util.List;
import java.util.Optional;

/**
 * Type, field and relationship type descriptors of the tool. They are the basis
 * for mapping validation (do the identifiers and value sets in the mapping
 * match the tool?), for the administration views (which types and fields does
 * the tool have?) and for type checks when writing.
 */
public interface MetadataProvider {

    /** Artifact, item or issue types of the external project. */
    List<ElementArtifactType> artifactTypes(String externalProjectId) throws AdapterException;

    /** Field descriptors of the external project (including custom fields). */
    List<ElementFieldType> fieldTypes(String externalProjectId) throws AdapterException;

    /** Relationship types of the external project. */
    List<ElementRelationshipType> relationshipTypes(String externalProjectId) throws AdapterException;

    /**
     * The fields of comment text and thread predecessor, if they differ from
     * {@link AdapterConventions}. Empty means: the names from
     * {@link AdapterConventions} apply. The sync core reads them per binding
     * for the comment phase; if the method throws, the phase fails instead of
     * silently falling back to the conventions.
     */
    default Optional<CommentFields> commentFields() {
        return Optional.empty();
    }
}
