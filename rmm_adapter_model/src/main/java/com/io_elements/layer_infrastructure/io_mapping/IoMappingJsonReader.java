package com.io_elements.layer_infrastructure.io_mapping;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Fills an {@link IoMapping} from a JSON mapping file:
 *
 * <pre>
 * {
 *   "artifactTypeField":     "Issue Type",
 *   "typeMappings":          { "&lt;tool&gt;ToRmm": { "tool type": "Mirador type", ... } },
 *   "attributeNameMappings": { "&lt;tool&gt;ToRmm": { "tool attribute": "Mirador attribute", ... } },
 *   "relationshipMappings":  { "&lt;tool&gt;ToRmm": { "tool relationship": "Mirador relationship", ... } },
 *   "protectedAttributes":   [ "Id", ... ]
 * }
 * </pre>
 *
 * <p>{@code artifactTypeField} names the IO-side field that carries the
 * artifact type ("Issue Type" in a tool such as Jira, "Item Type" in one
 * such as Jama). The type is not an attribute; it is resolved to the Mirador
 * type through {@code typeMappings}. The field is required for
 * synchronization: the host rejects a run whose mapping has no
 * {@code artifactTypeField}.</p>
 *
 * <p>Tool-neutral: any child whose key ends in {@code ToRmm} is accepted as
 * the direction key ({@code jiraToRmm}, {@code interfaceToRmm}, ...); the
 * reverse direction follows from the symmetric {@code map*} methods of
 * {@link IoMapping}. Keys with a leading {@code _} are comments and are
 * ignored.</p>
 *
 * <p>Optional sections: {@code identifiers}, {@code attributeValueMappings}
 * and {@code crossToolReferences}.</p>
 */
public class IoMappingJsonReader implements IoMappingReader<Path> {

    private static final String DIRECTION_KEY_SUFFIX = "ToRmm";

    private final ObjectMapper objectMapper = JsonMapper.builderWithJackson2Defaults().build();

    @Override
    public IoMapping read(Path source) throws ExceptionIoMapping {
        return read(loadJson(source));
    }

    /**
     * Reads the same mapping from JSON text, for example a mapping stored in a
     * database, or an upload that must pass the same structural checks before
     * it is saved as a file does on loading. {@code description} names the
     * source in error messages.
     */
    public IoMapping read(String json, String description) throws ExceptionIoMapping {
        JsonNode root;
        try {
            root = objectMapper.readTree(json == null ? "" : json);
        } catch (tools.jackson.core.JacksonException e) {
            throw new ExceptionIoMapping("Could not read mapping source " + description + ": " + e.getMessage(), e);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new ExceptionIoMapping("Could not read mapping source " + description + ": empty document");
        }
        if (!root.isObject()) {
            throw new ExceptionIoMapping("Could not read mapping source " + description
                    + ": the top level must be a JSON object");
        }
        return read(root);
    }

    private IoMapping read(JsonNode root) throws ExceptionIoMapping {
        IoMapping mapping = new IoMapping();
        JsonNode artifactTypeField = root.path("artifactTypeField");
        if (artifactTypeField.isString() && !artifactTypeField.asString("").isBlank()) {
            mapping.setIoArtifactTypeField(artifactTypeField.asString(""));
        }
        readSection(root, "typeMappings", mapping::mapArtifactType);
        readSection(root, "attributeNameMappings", mapping::mapAttribute);
        readSection(root, "relationshipMappings", mapping::mapRelationshipType);
        readIdentifierForms(root, mapping);
        readAttributeValueMappings(root, mapping);
        readProtectedAttributes(root, mapping);
        readCrossToolReferences(root, mapping);
        return mapping;
    }

    @Override
    public boolean isAvailable(Path source) {
        return source != null && Files.isReadable(source);
    }

    /**
     * The source for logs and messages. A file inside a jar (the mapping
     * template of a plugin) is described by its URI, which names the jar and
     * not only the path inside it.
     */
    @Override
    public String getSourceDescription(Path source) {
        if (source == null) {
            return "File: <unknown>";
        }
        return source.getFileSystem() == java.nio.file.FileSystems.getDefault() ? "File: " + source
                : "File: " + source.toUri();
    }

    // ------------------------------------------------------------------
    // Internal
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface MappingConsumer {
        void map(ElementLabel io, ElementLabel rmm);
    }

    private JsonNode loadJson(Path source) throws ExceptionIoMapping {
        try (java.io.InputStream content = Files.newInputStream(source)) {
            return objectMapper.readTree(content);
        } catch (java.io.IOException | tools.jackson.core.JacksonException e) {
            throw new ExceptionIoMapping("Could not read mapping source " + getSourceDescription(source), e);
        }
    }

    /**
     * Reads a section with a direction child ({@code *ToRmm}) and passes each
     * pair of IO and Mirador identifier to the symmetric {@code map*} method.
     * A missing section is allowed (empty mapping).
     */
    private void readSection(JsonNode root, String section, MappingConsumer consumer) throws ExceptionIoMapping {
        JsonNode sectionNode = root.path(section);
        if (sectionNode.isMissingNode()) {
            return;
        }
        JsonNode direction = directionChild(sectionNode, section);
        Iterator<Map.Entry<String, JsonNode>> entries = direction.properties().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            if (entry.getKey().startsWith("_")) {
                continue;
            }
            consumer.map(new ElementLabel(entry.getKey()), new ElementLabel(entry.getValue().asString("")));
        }
    }

    /**
     * Optional value translations for attributes with a restricted value set.
     * Structured like the name mapping sections (direction child
     * {@code *ToRmm}), but two levels deep: per attribute an object that maps
     * IO values to Mirador values. A missing section is allowed; if an
     * attribute is listed, its entry must be an object, so that a typo rejects
     * the file instead of silently running without value translation.
     */
    private void readAttributeValueMappings(JsonNode root, IoMapping mapping) throws ExceptionIoMapping {
        JsonNode sectionNode = root.path("attributeValueMappings");
        if (sectionNode.isMissingNode()) {
            return;
        }
        JsonNode direction = directionChild(sectionNode, "attributeValueMappings");
        Iterator<Map.Entry<String, JsonNode>> attributes = direction.properties().iterator();
        while (attributes.hasNext()) {
            Map.Entry<String, JsonNode> attribute = attributes.next();
            if (attribute.getKey().startsWith("_")) {
                continue;
            }
            if (!attribute.getValue().isObject()) {
                throw new ExceptionIoMapping("attributeValueMappings entry <" + attribute.getKey()
                        + "> must be an object of ioValue -> rmmValue pairs");
            }
            Iterator<Map.Entry<String, JsonNode>> values = attribute.getValue().properties().iterator();
            while (values.hasNext()) {
                Map.Entry<String, JsonNode> value = values.next();
                if (value.getKey().startsWith("_")) {
                    continue;
                }
                mapping.mapAttributeValue(new ElementLabel(attribute.getKey()),
                        value.getKey(), value.getValue().asString(""));
            }
        }
    }

    private JsonNode directionChild(JsonNode sectionNode, String section) throws ExceptionIoMapping {
        Iterator<Map.Entry<String, JsonNode>> children = sectionNode.properties().iterator();
        while (children.hasNext()) {
            Map.Entry<String, JsonNode> child = children.next();
            if (!child.getKey().startsWith("_") && child.getKey().endsWith(DIRECTION_KEY_SUFFIX)
                    && child.getValue().isObject()) {
                return child.getValue();
            }
        }
        throw new ExceptionIoMapping("Section <" + section + "> has no <*" + DIRECTION_KEY_SUFFIX + "> direction child");
    }

    /**
     * The identifier forms of this tool:
     * <pre>
     * "identifiers": {
     *   "key": { "attribute": "Key", "pattern": "([A-Z]+-\\d+)", "format": "{id}", "sample": "PROJ-12" }
     * }
     * </pre>
     * Every form must pass the round-trip check; the built-in names
     * ({@code rmmId}, {@code externalId}) cannot be redefined. Keys with a
     * leading underscore are comments.
     */
    private void readIdentifierForms(JsonNode root, IoMapping mapping) throws ExceptionIoMapping {
        JsonNode section = root.path("identifiers");
        if (section.isMissingNode()) {
            return;
        }
        if (!section.isObject()) {
            throw new ExceptionIoMapping("Section <identifiers> must be an object of identifier forms");
        }
        Iterator<Map.Entry<String, JsonNode>> forms = section.properties().iterator();
        while (forms.hasNext()) {
            Map.Entry<String, JsonNode> entry = forms.next();
            String name = entry.getKey().trim();
            if (name.startsWith("_")) {
                continue;
            }
            requireIdentifierName(name, "section <identifiers>");
            if (CrossToolReferenceRule.isBuiltInIdentifier(name)) {
                throw new ExceptionIoMapping("Section <identifiers> redefines the built-in form <" + name + ">");
            }
            JsonNode definition = entry.getValue();
            if (!definition.isObject()) {
                throw new ExceptionIoMapping("Identifier form <" + name + "> must be an object (attribute, pattern, format)");
            }
            java.util.regex.Pattern pattern = null;
            String patternText = optionalRuleText(definition, "pattern");
            if (patternText != null) {
                try {
                    pattern = java.util.regex.Pattern.compile(patternText);
                } catch (java.util.regex.PatternSyntaxException e) {
                    throw new ExceptionIoMapping("Identifier form <" + name + "> has an invalid pattern: "
                            + e.getMessage(), e);
                }
            }
            IdentifierForm form = new IdentifierForm(name, optionalRuleText(definition, "attribute"), pattern,
                    optionalRuleText(definition, "format"), optionalRuleText(definition, "sample"));
            if (!form.format().contains(IdentifierForm.ID_PLACEHOLDER)) {
                throw new ExceptionIoMapping("Identifier form <" + name + "> has a format without "
                        + IdentifierForm.ID_PLACEHOLDER);
            }
            String finding = form.verifyRoundTrip();
            if (finding != null) {
                throw new ExceptionIoMapping("Identifier form <" + name + "> fails the round-trip check: " + finding);
            }
            mapping.defineIdentifierForm(form);
        }
    }

    /**
     * The {@code crossToolReferences} section: an array of rules under the
     * direction key and, optionally, the list {@code externallySynchronised}
     * of target systems whose fields a third-party tool maintains. The rules
     * are validated here, on loading: an error that surfaced only during a
     * run would mean a run without the relationships the rule exists for.
     */
    private void readCrossToolReferences(JsonNode root, IoMapping mapping) throws ExceptionIoMapping {
        JsonNode section = root.path("crossToolReferences");
        if (section.isMissingNode()) {
            return;
        }
        for (JsonNode system : section.path("externallySynchronised")) {
            if (system.isString() && !system.asString("").isBlank()) {
                mapping.markExternallySynchronised(system.asString("").trim());
            }
        }
        JsonNode rules = null;
        Iterator<Map.Entry<String, JsonNode>> children = section.properties().iterator();
        while (children.hasNext()) {
            Map.Entry<String, JsonNode> child = children.next();
            if (!child.getKey().startsWith("_") && child.getKey().endsWith(DIRECTION_KEY_SUFFIX)
                    && child.getValue().isArray()) {
                rules = child.getValue();
                break;
            }
        }
        if (rules == null) {
            throw new ExceptionIoMapping("Section <crossToolReferences> has no <*" + DIRECTION_KEY_SUFFIX
                    + "> direction child carrying a rule array");
        }
        for (JsonNode rule : rules) {
            mapping.addCrossToolReferenceRule(readCrossToolReferenceRule(rule, mapping));
        }
        validateCrossToolReferences(mapping);
    }

    private CrossToolReferenceRule readCrossToolReferenceRule(JsonNode rule, IoMapping mapping)
            throws ExceptionIoMapping {
        String field = resolveRuleField(rule);
        String relationshipType = requireRuleText(rule, "relationshipType");
        boolean outgoing = readOutgoing(rule, field);
        CrossToolReferenceRule.Authority requested = readAuthority(rule, field);
        String targetSystem = optionalRuleText(rule, "targetSystem");
        // The forced read-only mode is decided before the defaults: a rule
        // against an externally synchronized pair only reads, and then reads
        // the format of the third-party tool, not Mirador ids.
        boolean externallyForced = targetSystem != null && mapping.isExternallySynchronised(targetSystem)
                && requested != CrossToolReferenceRule.Authority.TOOL;
        CrossToolReferenceRule.Authority authority = externallyForced
                ? CrossToolReferenceRule.Authority.TOOL : requested;
        String identifier = optionalRuleText(rule, "identifier");
        if (identifier == null) {
            identifier = authority == CrossToolReferenceRule.Authority.TOOL
                    ? CrossToolReferenceRule.IDENTIFIER_EXTERNAL_ID : CrossToolReferenceRule.IDENTIFIER_RMM_ID;
        }
        requireIdentifierName(identifier, "rule for field <" + field + ">");
        List<String> readIdentifiers = readReadIdentifiers(rule, field);
        requireTargetSystemForToolIds(field, targetSystem, identifier, readIdentifiers);
        java.util.regex.Pattern idPattern = readIdPattern(rule, field);
        boolean listField = readListField(rule, field);
        CrossToolReferenceRule parsed = new CrossToolReferenceRule(field, targetSystem, relationshipType,
                outgoing, authority, identifier, readIdentifiers, idPattern,
                optionalRuleText(rule, "idFormat"), rawRuleText(rule, "separator"), rawRuleText(rule, "emptyValue"),
                listField);
        requireRoundTrip(rule, field, parsed);
        if (externallyForced) {
            mapping.addWarning("Cross-tool reference rule for field <" + field + "> asks for authority <"
                    + requested.configValue() + ">, but the pair with " + targetSystem
                    + " is externally synchronised — the rule runs as <tool> (read only)");
        }
        return parsed;
    }

    /** {@code direction}: whether the field's owner is the source of the relationship; the default is outgoing. */
    private boolean readOutgoing(JsonNode rule, String field) throws ExceptionIoMapping {
        String direction = rule.path("direction").isMissingNode() ? "outgoing" : rule.path("direction").asString("");
        if (!"outgoing".equals(direction) && !"incoming".equals(direction)) {
            throw new ExceptionIoMapping("Cross-tool reference rule for field <" + field
                    + "> has an unknown direction <" + direction + "> (outgoing|incoming)");
        }
        return "outgoing".equals(direction);
    }

    /** {@code authority} as the rule asks for it; the default is {@code tool}. */
    private CrossToolReferenceRule.Authority readAuthority(JsonNode rule, String field) throws ExceptionIoMapping {
        JsonNode authorityNode = rule.path("authority");
        if (!authorityNode.isString() || authorityNode.asString("").isBlank()) {
            return CrossToolReferenceRule.Authority.TOOL;
        }
        try {
            return CrossToolReferenceRule.Authority.parse(authorityNode.asString(""));
        } catch (IllegalArgumentException e) {
            throw new ExceptionIoMapping("Cross-tool reference rule for field <" + field
                    + "> has an unknown authority <" + authorityNode.asString("") + "> (tool|rmm|shared)");
        }
    }

    /** {@code readIdentifiers}: the additional read forms, each with a valid name; blank entries are skipped. */
    private List<String> readReadIdentifiers(JsonNode rule, String field) throws ExceptionIoMapping {
        List<String> readIdentifiers = new ArrayList<>();
        for (JsonNode form : rule.path("readIdentifiers")) {
            if (form.isString() && !form.asString("").isBlank()) {
                requireIdentifierName(form.asString("").trim(), "rule for field <" + field + ">");
                readIdentifiers.add(form.asString("").trim());
            }
        }
        return readIdentifiers;
    }

    /** Only a rule that reads and writes nothing but {@code rmmId} may leave out the target system. */
    private void requireTargetSystemForToolIds(String field, String targetSystem, String identifier,
                                               List<String> readIdentifiers) throws ExceptionIoMapping {
        boolean needsTargetSystem = !CrossToolReferenceRule.IDENTIFIER_RMM_ID.equals(identifier)
                || readIdentifiers.stream().anyMatch(form -> !CrossToolReferenceRule.IDENTIFIER_RMM_ID.equals(form));
        if (targetSystem == null && needsTargetSystem) {
            throw new ExceptionIoMapping("Cross-tool reference rule for field <" + field
                    + "> needs a <targetSystem>: only rules that read and write nothing but the internal"
                    + " Mirador id (identifier rmmId) may leave it out");
        }
    }

    /** {@code idPattern} compiled; {@code null} if the rule names none. */
    private java.util.regex.Pattern readIdPattern(JsonNode rule, String field) throws ExceptionIoMapping {
        JsonNode patternNode = rule.path("idPattern");
        if (!patternNode.isString() || patternNode.asString("").isBlank()) {
            return null;
        }
        try {
            return java.util.regex.Pattern.compile(patternNode.asString(""));
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new ExceptionIoMapping("Cross-tool reference rule for field <" + field
                    + "> has an invalid idPattern: " + e.getMessage(), e);
        }
    }

    /** {@code attributeType}: whether the tool field is a list field; the default is a text field. */
    private boolean readListField(JsonNode rule, String field) throws ExceptionIoMapping {
        String attributeType = optionalRuleText(rule, "attributeType");
        if (attributeType == null) {
            attributeType = CrossToolReferenceRule.ATTRIBUTE_TYPE_TEXT;
        }
        if (!CrossToolReferenceRule.ATTRIBUTE_TYPE_TEXT.equals(attributeType)
                && !CrossToolReferenceRule.ATTRIBUTE_TYPE_LIST.equals(attributeType)) {
            throw new ExceptionIoMapping("Cross-tool reference rule for field <" + field
                    + "> has an unknown attributeType <" + attributeType + "> (text|list)");
        }
        return CrossToolReferenceRule.ATTRIBUTE_TYPE_LIST.equals(attributeType);
    }

    /**
     * The round trip for every writing rule: what the rule writes, it must
     * read back as exactly one identifier of its write form. {@code rmmId} is
     * checked with a UUID, {@code externalId} with the rule's sample (default
     * ABC-123); tool forms are checked by mapping validation with the form's
     * sample, once the mapping of the target system is known.
     */
    private void requireRoundTrip(JsonNode rule, String field, CrossToolReferenceRule parsed)
            throws ExceptionIoMapping {
        if (!parsed.isWrittenByRmm() || parsed.writesToolIdentifier()) {
            return;
        }
        boolean rmmId = CrossToolReferenceRule.IDENTIFIER_RMM_ID.equals(parsed.getIdentifier());
        String declaredSample = optionalRuleText(rule, "sample");
        String sample = rmmId ? RMM_ID_SAMPLE : declaredSample == null ? DEFAULT_EXTERNAL_ID_SAMPLE : declaredSample;
        String finding = parsed.verifyRoundTrip(sample);
        if (finding != null) {
            throw new ExceptionIoMapping("Cross-tool reference rule for field <" + field
                    + "> fails the round-trip check: " + finding
                    + (CrossToolReferenceRule.IDENTIFIER_EXTERNAL_ID.equals(parsed.getIdentifier())
                    ? " (an idFormat that decorates the id needs an idPattern that reads it back;"
                    + " name a <sample> id the pattern accepts if ABC-123 does not)" : ""));
        }
    }

    /** Sample identifier for the round-trip check of an rmmId rule. */
    private static final String RMM_ID_SAMPLE = "123e4567-e89b-12d3-a456-426614174000";

    /** Sample identifier for the round-trip check of an externalId rule without a {@code sample} of its own. */
    private static final String DEFAULT_EXTERNAL_ID_SAMPLE = "ABC-123";

    /**
     * {@code attribute}: the tool's identifier of the reference field, written
     * as in {@code attributeNameMappings}. Required; the field does not become
     * a Mirador attribute.
     */
    private String resolveRuleField(JsonNode rule) throws ExceptionIoMapping {
        String field = optionalRuleText(rule, "attribute");
        if (field == null) {
            throw new ExceptionIoMapping("Cross-tool reference rule is missing the required field <attribute>"
                    + " (the tool's field label): " + rule);
        }
        return field;
    }

    /**
     * Checks the spelling of an identifier form name: built in ({@code rmmId},
     * {@code externalId}) or a tool form from the {@code identifiers} section
     * of the target system. This file does not know that section; whether the
     * form exists is checked by mapping validation against the mapping and the
     * adapter of the target system.
     */
    private void requireIdentifierName(String identifier, String where) throws ExceptionIoMapping {
        if (!identifier.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new ExceptionIoMapping("The identifier form <" + identifier + "> in " + where
                    + " may only carry letters, digits, '-' and '_'");
        }
    }

    /**
     * Checks the rules of one file against each other: a field has exactly one
     * writer, a field that Mirador writes has exactly one rule (so that a
     * re-import cannot create a second relationship under another type), and
     * such a field is not also a mapped attribute (two writers in Mirador).
     */
    private void validateCrossToolReferences(IoMapping mapping) throws ExceptionIoMapping {
        Map<String, List<CrossToolReferenceRule>> byField = new java.util.LinkedHashMap<>();
        for (CrossToolReferenceRule rule : mapping.getCrossToolReferenceRules()) {
            byField.computeIfAbsent(rule.getAttributeName(), key -> new ArrayList<>()).add(rule);
        }
        for (Map.Entry<String, List<CrossToolReferenceRule>> entry : byField.entrySet()) {
            long writers = entry.getValue().stream().filter(CrossToolReferenceRule::isWrittenByRmm).count();
            if (writers == 0) {
                continue;
            }
            if (writers > 1) {
                throw new ExceptionIoMapping("Field <" + entry.getKey() + "> has " + writers
                        + " cross-tool reference rules with authority rmm/shared — a field has exactly one writer");
            }
            if (entry.getValue().size() > 1) {
                throw new ExceptionIoMapping("Field <" + entry.getKey() + "> is written by Mirador and read by "
                        + entry.getValue().size() + " rules — a field Mirador writes has exactly one rule,"
                        + " or a re-import would create a second edge under another relationship type");
            }
            if (mapping.rmmAttributeFor(new ElementLabel(entry.getKey())).isPresent()) {
                throw new ExceptionIoMapping("Field <" + entry.getKey() + "> is written by Mirador (authority"
                        + " rmm/shared) and also mapped as an attribute in attributeNameMappings — it would have"
                        + " two writers in Mirador; drop the attribute mapping or set authority tool");
            }
        }
    }

    private String optionalRuleText(JsonNode rule, String field) {
        JsonNode value = rule.path(field);
        return value.isString() && !value.asString("").isBlank() ? value.asString("").trim() : null;
    }

    /** Like {@link #optionalRuleText}, but untrimmed and possibly empty (separator, empty value). */
    private String rawRuleText(JsonNode rule, String field) {
        JsonNode value = rule.path(field);
        return value.isString() ? value.asString("") : null;
    }

    private String requireRuleText(JsonNode rule, String field) throws ExceptionIoMapping {
        JsonNode value = rule.path(field);
        if (!value.isString() || value.asString("").isBlank()) {
            throw new ExceptionIoMapping("Cross-tool reference rule is missing the required field <" + field
                    + ">: " + rule);
        }
        return value.asString("");
    }

    private void readProtectedAttributes(JsonNode root, IoMapping mapping) {
        for (JsonNode attribute : root.path("protectedAttributes")) {
            mapping.protectAttribute(new ElementName(attribute.asString("")));
        }
    }
}
