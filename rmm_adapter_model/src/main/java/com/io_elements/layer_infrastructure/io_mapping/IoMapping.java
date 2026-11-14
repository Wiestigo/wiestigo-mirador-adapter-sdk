package com.io_elements.layer_infrastructure.io_mapping;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationship;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementField;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Mapping between the type and attribute identifiers of an external data
 * set (the IO side: the tool) and those of Mirador ({@code rmm} in member
 * names), used in both directions.
 *
 * <p>The keys are transfer elements in their descriptor form, equal over
 * (name, label); the {@code map*} methods build descriptors with
 * name = label = the identifier of the respective side.</p>
 *
 * <p>A mapping is filled only through the symmetric {@code map*} methods
 * (each assignment maintains both directions; the first assignment of an
 * identifier wins) or through an {@link IoMappingReader}. Lookups go through
 * the {@code rmm*For} and {@code io*For} methods.</p>
 *
 * <p>Attribute values are transferred unchanged; only the identifiers are
 * mapped (for example the tool label "Item Type" to the Mirador name
 * "ArtifactType"). The one exception: an attribute with an explicit value
 * mapping ({@link #mapAttributeValue}) also translates its restricted value
 * set, such as a status vocabulary.</p>
 */
@Data
public class IoMapping {

    private Map<ElementArtifact, ElementArtifact> rmmArt2IOArt = new HashMap<>();
    private Map<ElementArtifact, ElementArtifact> ioArt2RMMArt = new HashMap<>();
    private Map<ElementField<?>, ElementField<?>> rmmAttr2IOAttr = new HashMap<>();
    private Map<ElementField<?>, ElementField<?>> ioAttr2RMMAttr = new HashMap<>();
    private Map<ElementRelationship, ElementRelationship> srcRMMEdge2IOEdge = new HashMap<>();
    private Map<ElementRelationship, ElementRelationship> srcIOEdge2RMMEdge = new HashMap<>();
    private Map<ElementRelationship, ElementRelationship> tarRMMEdge2IOEdge = new HashMap<>();
    private Map<ElementRelationship, ElementRelationship> tarIOEdge2RMMEdge = new HashMap<>();
    /** Attributes that are never transferred (for example internal ids). */
    private Set<ElementName> protectedAttributes = new HashSet<>();
    /**
     * Optional value translations per attribute, the one exception to
     * "values are transferred unchanged": attributes with a restricted value
     * set (status, pick lists) whose vocabulary differs between the tool and
     * Mirador (for example "Is compliant" and "Compliant"). The outer key is
     * the IO-side attribute identifier (the same as in
     * {@code attributeNameMappings}); the inner map translates value to value
     * for one direction. Attributes without an entry are transferred
     * unchanged. How an unmapped value of a listed attribute is handled is up
     * to the code that applies the mapping; the host skips the attribute and
     * reports a warning.
     */
    private Map<String, Map<String, String>> ioValue2RmmValueByAttribute = new HashMap<>();
    private Map<String, Map<String, String>> rmmValue2IoValueByAttribute = new HashMap<>();
    /**
     * Label of the IO-side field that carries the artifact type of an element
     * ("Issue Type" in a tool such as Jira, "Item Type" in one such as Jama).
     * The type is not an attribute: it determines the artifact, and is
     * therefore named here instead of through an entry in the attribute
     * mapping.
     */
    private String ioArtifactTypeField;
    /**
     * Rules for deriving relationships across tools: which attributes of the
     * source tool carry identifiers of artifacts in other systems, and as
     * which relationship they are derived. Empty if the configuration has no
     * {@code crossToolReferences} section.
     */
    private final List<CrossToolReferenceRule> crossToolReferenceRules = new ArrayList<>();
    /**
     * Target systems whose reference fields a third-party tool maintains (a
     * tool-to-tool synchronizer): rules against these systems run with
     * authority {@code tool}, and Mirador never writes there.
     */
    private final Set<String> externallySynchronisedSystems = new LinkedHashSet<>();
    /** Findings from loading that do not justify a failure; the caller logs them. */
    private final List<String> warnings = new ArrayList<>();

    /**
     * The identifier forms of this tool (section {@code identifiers}): how its
     * artifacts are named in reference fields of other tools. Rules of other
     * mappings refer to them by name; without an entry the adapter's default
     * applies.
     */
    private final Map<String, IdentifierForm> identifierForms = new java.util.LinkedHashMap<>();

    // ------------------------------------------------------------------
    // Filling: symmetric, the first assignment wins
    // ------------------------------------------------------------------

    /** Maps an IO artifact type to a Mirador artifact type (both directions). */
    public void mapArtifactType(ElementLabel ioType, ElementLabel rmmType) {
        ioArt2RMMArt.putIfAbsent(artifactDescriptor(ioType), artifactDescriptor(rmmType));
        rmmArt2IOArt.putIfAbsent(artifactDescriptor(rmmType), artifactDescriptor(ioType));
    }

    /** Maps an IO attribute identifier to a Mirador attribute identifier (both directions). */
    public void mapAttribute(ElementLabel ioAttribute, ElementLabel rmmAttribute) {
        ioAttr2RMMAttr.putIfAbsent(fieldDescriptor(ioAttribute), fieldDescriptor(rmmAttribute));
        rmmAttr2IOAttr.putIfAbsent(fieldDescriptor(rmmAttribute), fieldDescriptor(ioAttribute));
    }

    /** Maps an IO relationship type to a Mirador relationship type (both directions, source and target). */
    public void mapRelationshipType(ElementLabel ioRelationship, ElementLabel rmmRelationship) {
        srcIOEdge2RMMEdge.putIfAbsent(relationshipDescriptor(ioRelationship), relationshipDescriptor(rmmRelationship));
        srcRMMEdge2IOEdge.putIfAbsent(relationshipDescriptor(rmmRelationship), relationshipDescriptor(ioRelationship));
        tarIOEdge2RMMEdge.putIfAbsent(relationshipDescriptor(ioRelationship), relationshipDescriptor(rmmRelationship));
        tarRMMEdge2IOEdge.putIfAbsent(relationshipDescriptor(rmmRelationship), relationshipDescriptor(ioRelationship));
    }

    /** Marks an attribute as protected (it is never transferred). */
    public void protectAttribute(ElementName attribute) {
        protectedAttributes.add(attribute);
    }

    /**
     * Maps an IO value of an attribute to a Mirador value (both directions;
     * the first assignment of a value wins, as for the identifier mappings).
     *
     * @param ioAttribute IO-side attribute identifier (the same key as in
     *                    {@code attributeNameMappings}).
     */
    public void mapAttributeValue(ElementLabel ioAttribute, String ioValue, String rmmValue) {
        String attributeKey = ioAttribute.getLabel();
        ioValue2RmmValueByAttribute.computeIfAbsent(attributeKey, k -> new HashMap<>())
                .putIfAbsent(ioValue, rmmValue);
        rmmValue2IoValueByAttribute.computeIfAbsent(attributeKey, k -> new HashMap<>())
                .putIfAbsent(rmmValue, ioValue);
    }

    // ------------------------------------------------------------------
    // Lookup
    // ------------------------------------------------------------------

    public Optional<ElementArtifact> rmmArtifactTypeFor(ElementLabel ioType) {
        return Optional.ofNullable(ioArt2RMMArt.get(artifactDescriptor(ioType)));
    }

    public Optional<ElementArtifact> ioArtifactTypeFor(ElementLabel rmmType) {
        return Optional.ofNullable(rmmArt2IOArt.get(artifactDescriptor(rmmType)));
    }

    public Optional<ElementField<?>> rmmAttributeFor(ElementLabel ioAttribute) {
        return Optional.ofNullable(ioAttr2RMMAttr.get(fieldDescriptor(ioAttribute)));
    }

    public Optional<ElementField<?>> ioAttributeFor(ElementLabel rmmAttribute) {
        return Optional.ofNullable(rmmAttr2IOAttr.get(fieldDescriptor(rmmAttribute)));
    }

    /** Whether the attribute has a value mapping; only then does the exception to "values unchanged" apply. */
    public boolean hasAttributeValueMapping(ElementLabel ioAttribute) {
        return ioAttribute != null && ioValue2RmmValueByAttribute.containsKey(ioAttribute.getLabel());
    }

    /** The Mirador value for an IO value of the attribute; empty if the value has no entry. */
    public Optional<String> rmmValueFor(ElementLabel ioAttribute, String ioValue) {
        return Optional.ofNullable(ioValue2RmmValueByAttribute.get(ioAttribute.getLabel()))
                .map(values -> values.get(ioValue));
    }

    /** The IO value for a Mirador value of the attribute; empty if the value has no entry. */
    public Optional<String> ioValueFor(ElementLabel ioAttribute, String rmmValue) {
        return Optional.ofNullable(rmmValue2IoValueByAttribute.get(ioAttribute.getLabel()))
                .map(values -> values.get(rmmValue));
    }

    public Optional<ElementRelationship> rmmRelationshipTypeFor(ElementLabel ioRelationship) {
        return Optional.ofNullable(srcIOEdge2RMMEdge.get(relationshipDescriptor(ioRelationship)));
    }

    public Optional<ElementRelationship> ioRelationshipTypeFor(ElementLabel rmmRelationship) {
        return Optional.ofNullable(srcRMMEdge2IOEdge.get(relationshipDescriptor(rmmRelationship)));
    }

    public boolean isProtectedAttribute(ElementName attribute) {
        return protectedAttributes.contains(attribute);
    }

    public void addCrossToolReferenceRule(CrossToolReferenceRule rule) {
        crossToolReferenceRules.add(rule);
    }

    public List<CrossToolReferenceRule> getCrossToolReferenceRules() {
        return Collections.unmodifiableList(crossToolReferenceRules);
    }

    public void defineIdentifierForm(IdentifierForm form) {
        identifierForms.put(form.name(), form);
    }

    public Optional<IdentifierForm> identifierFormFor(String name) {
        return Optional.ofNullable(name == null ? null : identifierForms.get(name));
    }

    public Map<String, IdentifierForm> getIdentifierForms() {
        return java.util.Collections.unmodifiableMap(identifierForms);
    }

    public void markExternallySynchronised(String systemKey) {
        externallySynchronisedSystems.add(systemKey.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Whether a third-party tool maintains the pair with this target system.
     * Compares system keys only (ignoring case); it does not look at any data.
     */
    public boolean isExternallySynchronised(String systemKey) {
        return systemKey != null && externallySynchronisedSystems.contains(systemKey.toUpperCase(java.util.Locale.ROOT));
    }

    public Set<String> getExternallySynchronisedSystems() {
        return Collections.unmodifiableSet(externallySynchronisedSystems);
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    public List<String> getWarnings() {
        return Collections.unmodifiableList(warnings);
    }

    /** The IO-side type field as a label; empty if the mapping names none. */
    public Optional<ElementLabel> ioArtifactTypeFieldLabel() {
        return (ioArtifactTypeField == null || ioArtifactTypeField.isBlank())
                ? Optional.empty()
                : Optional.of(new ElementLabel(ioArtifactTypeField));
    }

    // ------------------------------------------------------------------
    // Descriptor convention: name = label = the identifier of the respective side
    // ------------------------------------------------------------------

    private static ElementArtifact artifactDescriptor(ElementLabel label) {
        return ElementArtifact.ofNameAndLabel(new ElementName(label.getLabel()), label);
    }

    private static ElementField<?> fieldDescriptor(ElementLabel label) {
        return new ElementFieldString(new ElementName(label.getLabel()), label, null);
    }

    private static ElementRelationship relationshipDescriptor(ElementLabel label) {
        return ElementRelationship.ofNameAndLabel(new ElementName(label.getLabel()), label);
    }
}
