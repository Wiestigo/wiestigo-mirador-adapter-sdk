package com.io_elements.layer_infrastructure.io_mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A rule for cross-tool references: a tool holds the identifier of an
 * artifact of other origin as a <b>field value</b> ("Trace Reference:
 * PROJ-12, PROJ-15"). The rule states which tool field that is, as which
 * relationship it is read, who maintains the relationships, and in which
 * identifier form the values are written and read.
 *
 * <ul>
 *   <li>{@code attributeName}: the tool's identifier of the reference field
 *       (rule key {@code attribute}), for example a field name or a field
 *       label. The field is not a Mirador attribute: on import it is read
 *       from the transfer element, and on the way back it is generated from
 *       the relationships.</li>
 *   <li>{@code targetSystem}: the system key of the tool whose artifacts are
 *       referenced. Required for tool identifier forms; optional for
 *       {@link #IDENTIFIER_RMM_ID}, where without it all counterparts of the
 *       relationship type count.</li>
 *   <li>{@code relationshipTypeName}: the relationship type from the Mirador
 *       vocabulary.</li>
 *   <li>{@code attributeOwnerIsSource}: the direction; the artifact that
 *       holds the field is the source ({@code true}) or the target
 *       ({@code false}) of the relationship.</li>
 *   <li>{@code authority}: who maintains the relationships of the field.
 *       With {@link Authority#TOOL} Mirador only reads the field, with
 *       {@link Authority#RMM} it writes it, with {@link Authority#SHARED} it
 *       does both.</li>
 *   <li>{@code identifier}: the identifier form in which Mirador writes and
 *       which it reads; {@code readIdentifiers}: additional forms accepted
 *       when reading.</li>
 *   <li>{@code idPattern}: the read pattern of the form {@code externalId},
 *       wherever it appears in the rule (as write form or as read form);
 *       {@code rmmId} brings its own pattern. Without a pattern the whole
 *       trimmed value is one identifier. With a pattern every match counts;
 *       if the pattern has a capturing group, group 1 is the identifier.</li>
 *   <li>{@code idFormat}, {@code separator}, {@code emptyValue}: how the
 *       value is written; {@code {id}} is replaced by the identifier.</li>
 * </ul>
 */
public final class CrossToolReferenceRule {

    /** Who maintains the relationships of a reference field. */
    public enum Authority {
        TOOL, RMM, SHARED;

        public static Authority parse(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }

        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Identifier form: Mirador's internal artifact id (a UUID), also when embedded in a deep link. */
    public static final String IDENTIFIER_RMM_ID = "rmmId";
    /** Identifier form: the artifact's id in the target system (its external id). */
    public static final String IDENTIFIER_EXTERNAL_ID = "externalId";

    /** Placeholder for "no target system": an {@code rmmId} rule that takes all counterparts of the type. */
    public static final String ANY_TARGET_SYSTEM = "*";
    public static final String DEFAULT_ID_FORMAT = "{id}";
    public static final String DEFAULT_SEPARATOR = ", ";
    public static final String DEFAULT_EMPTY_VALUE = "";
    public static final String ATTRIBUTE_TYPE_TEXT = "text";
    public static final String ATTRIBUTE_TYPE_LIST = "list";
    /** Placeholder in {@code idFormat} for Mirador's public address ({@code rmm.public.url}). */
    public static final String PUBLIC_URL_PLACEHOLDER = "{publicUrl}";
    /** Path of the artifact view below the public address; the artifact id follows it. */
    public static final String DEEP_LINK_PATH = "/a/";
    private static final String ID_PLACEHOLDER = "{id}";

    /** A UUID, bare or embedded in a link: the form in which Mirador writes. */
    public static final Pattern RMM_ID_PATTERN = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    /** An identifier that was read, together with the form in which it stood in the field. */
    public record ExtractedId(String identifier, String id) {
    }

    private final String attributeName;
    private final String targetSystem;
    private final String relationshipTypeName;
    private final boolean attributeOwnerIsSource;
    private final Authority authority;
    private final String identifier;
    private final List<String> readIdentifiers;
    private final Pattern idPattern;
    private final String idFormat;
    private final String separator;
    private final String emptyValue;
    /** The tool field is a list field (one entry per identifier) instead of a text field. */
    private final boolean listField;
    /** Read forms in order: the write form first, then the additional ones; a {@code null} pattern = whole value. */
    private final Map<String, Pattern> readPatterns;
    /** The resolved read patterns of the tool identifier forms ({@link #withIdentifierForms}). */
    private final Map<String, Pattern> formPatterns;
    /**
     * The budgets of the resolved tool forms: only forms declared by a binding
     * carry one ({@link IdentifierForm#budget()}); without an entry the pattern
     * reads unbounded.
     */
    private final Map<String, PatternBudget> formBudgets;

    /**
     * Short form of the most common rule: a read-only rule
     * ({@link Authority#TOOL}) in the identifier form {@code externalId},
     * with an optional pattern.
     */
    public CrossToolReferenceRule(String attributeName, String targetSystem, String relationshipTypeName,
                                  boolean attributeOwnerIsSource, Pattern idPattern) {
        this(attributeName, targetSystem, relationshipTypeName, attributeOwnerIsSource, Authority.TOOL,
                IDENTIFIER_EXTERNAL_ID, List.of(), idPattern, null, null, null);
    }

    public CrossToolReferenceRule(String attributeName, String targetSystem, String relationshipTypeName,
                                  boolean attributeOwnerIsSource, Authority authority, String identifier,
                                  List<String> readIdentifiers, Pattern idPattern, String idFormat,
                                  String separator, String emptyValue) {
        this(attributeName, targetSystem, relationshipTypeName, attributeOwnerIsSource, authority, identifier,
                readIdentifiers, idPattern, idFormat, separator, emptyValue, false);
    }

    public CrossToolReferenceRule(String attributeName, String targetSystem, String relationshipTypeName,
                                  boolean attributeOwnerIsSource, Authority authority, String identifier,
                                  List<String> readIdentifiers, Pattern idPattern, String idFormat,
                                  String separator, String emptyValue, boolean listField) {
        this(attributeName, targetSystem, relationshipTypeName, attributeOwnerIsSource, authority, identifier,
                readIdentifiers, idPattern, idFormat, separator, emptyValue, listField, Map.of(), Map.of());
    }

    /**
     * The variant with resolved tool identifier forms: {@code formPatterns}
     * names the read pattern of each tool form (from the {@code identifiers}
     * section of the target system or from the adapter's default). A tool form
     * without an entry reads the whole field value.
     */
    private CrossToolReferenceRule(String attributeName, String targetSystem, String relationshipTypeName,
                                   boolean attributeOwnerIsSource, Authority authority, String identifier,
                                   List<String> readIdentifiers, Pattern idPattern, String idFormat,
                                   String separator, String emptyValue, boolean listField,
                                   Map<String, Pattern> formPatterns, Map<String, PatternBudget> formBudgets) {
        this.listField = listField;
        this.formBudgets = formBudgets == null ? Map.of() : Map.copyOf(formBudgets);
        //Not Map.copyOf: a form without a pattern (whole value) is stored with a null value
        this.formPatterns = formPatterns == null ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(formPatterns));
        this.attributeName = Objects.requireNonNull(attributeName, "attributeName");
        this.targetSystem = targetSystem;
        this.relationshipTypeName = Objects.requireNonNull(relationshipTypeName, "relationshipTypeName");
        this.attributeOwnerIsSource = attributeOwnerIsSource;
        this.authority = authority == null ? Authority.TOOL : authority;
        this.identifier = identifier == null || identifier.isBlank() ? IDENTIFIER_EXTERNAL_ID : identifier;
        this.readIdentifiers = readIdentifiers == null ? List.of() : List.copyOf(readIdentifiers);
        this.idPattern = idPattern;
        this.idFormat = idFormat == null || idFormat.isBlank() ? DEFAULT_ID_FORMAT : idFormat;
        this.separator = separator == null ? DEFAULT_SEPARATOR : separator;
        this.emptyValue = emptyValue == null ? DEFAULT_EMPTY_VALUE : emptyValue;
        this.readPatterns = new LinkedHashMap<>();
        readPatterns.put(this.identifier, patternFor(this.identifier, idPattern, this.formPatterns));
        for (String form : this.readIdentifiers) {
            readPatterns.putIfAbsent(form, patternFor(form, idPattern, this.formPatterns));
        }
    }

    /**
     * The read pattern of an identifier form: {@code rmmId} brings its own,
     * {@code externalId} takes the rule's {@code idPattern} (without one the
     * whole value counts), wherever the form appears, as write form or as
     * additional read form.
     */
    private static Pattern patternFor(String identifier, Pattern idPattern, Map<String, Pattern> formPatterns) {
        if (IDENTIFIER_EXTERNAL_ID.equals(identifier)) {
            return idPattern;
        }
        if (isBuiltInIdentifier(identifier)) {
            return builtInPattern(identifier);
        }
        return formPatterns.get(identifier);
    }

    /** Whether the form is a tool identifier form (neither {@code rmmId} nor {@code externalId}). */
    public static boolean isToolIdentifier(String identifier) {
        return identifier != null && !isBuiltInIdentifier(identifier);
    }

    /** The tool identifier forms that this rule reads or writes; empty if none. */
    public List<String> toolIdentifierForms() {
        List<String> forms = new ArrayList<>();
        for (String form : getAllReadIdentifiers()) {
            if (isToolIdentifier(form)) {
                forms.add(form);
            }
        }
        return forms;
    }

    /** Whether this rule writes in a tool identifier form (for example issue keys). */
    public boolean writesToolIdentifier() {
        return isToolIdentifier(identifier);
    }

    /** Whether the tool form {@code form} of this rule is resolved (its pattern is known). */
    public boolean hasResolvedForm(String form) {
        return formPatterns.containsKey(form);
    }

    /**
     * A copy of this rule with the identifier forms of the target system
     * resolved: each tool form gets its read pattern; if the rule writes in a
     * tool form and has no {@code idFormat} of its own, the write template of
     * the form applies. Forms that {@code forms} does not contain stay
     * unresolved (whole field value); mapping validation reports them.
     */
    public CrossToolReferenceRule withIdentifierForms(Map<String, IdentifierForm> forms) {
        Map<String, Pattern> patterns = new LinkedHashMap<>(formPatterns);
        Map<String, PatternBudget> budgets = new LinkedHashMap<>(formBudgets);
        String format = idFormat;
        for (String form : toolIdentifierForms()) {
            IdentifierForm definition = forms == null ? null : forms.get(form);
            if (definition == null) {
                continue;
            }
            patterns.put(form, definition.pattern());
            if (definition.budget() != null) {
                budgets.put(form, definition.budget());
            } else {
                budgets.remove(form);
            }
            if (form.equals(identifier) && hasDefaultIdFormat()) {
                format = definition.format();
            }
        }
        return new CrossToolReferenceRule(attributeName, targetSystem, relationshipTypeName, attributeOwnerIsSource,
                authority, identifier, readIdentifiers, idPattern, format, separator, emptyValue, listField, patterns,
                budgets);
    }

    /** Pattern of a built-in identifier form; {@code null} if the form has none (whole value). */
    public static Pattern builtInPattern(String identifier) {
        return IDENTIFIER_RMM_ID.equals(identifier) ? RMM_ID_PATTERN : null;
    }

    /** Whether the form is built in; all others come from the {@code identifiers} section of the target tool. */
    public static boolean isBuiltInIdentifier(String identifier) {
        return IDENTIFIER_RMM_ID.equals(identifier) || IDENTIFIER_EXTERNAL_ID.equals(identifier);
    }

    /** The tool's identifier of the reference field. */
    public String getAttributeName() {
        return attributeName;
    }

    /** {@code null} for {@code rmmId} rules that are not restricted to a target tool. */
    public String getTargetSystem() {
        return targetSystem;
    }

    public boolean hasTargetSystem() {
        return targetSystem != null && !targetSystem.isBlank();
    }

    /** The target system, or {@link #ANY_TARGET_SYSTEM} if the rule names none. */
    public String getTargetSystemOrAny() {
        return hasTargetSystem() ? targetSystem : ANY_TARGET_SYSTEM;
    }

    public String getRelationshipTypeName() {
        return relationshipTypeName;
    }

    public boolean isAttributeOwnerSource() {
        return attributeOwnerIsSource;
    }

    public Authority getAuthority() {
        return authority;
    }

    /** The identifier form in which Mirador writes (and reads). */
    public String getIdentifier() {
        return identifier;
    }

    /** Additional identifier forms that are recognized when reading. */
    public List<String> getReadIdentifiers() {
        return readIdentifiers;
    }

    /** All read forms in order, the write form first. */
    public List<String> getAllReadIdentifiers() {
        return new ArrayList<>(readPatterns.keySet());
    }

    public Pattern getIdPattern() {
        return idPattern;
    }

    public String getIdFormat() {
        return idFormat;
    }

    public String getSeparator() {
        return separator;
    }

    public String getEmptyValue() {
        return emptyValue;
    }

    /** Whether the tool field is a list field ({@code attributeType: list}); the default is a text field. */
    public boolean isListField() {
        return listField;
    }

    /** Whether {@code idFormat} is the default ({@code {id}}), that is, not set by the operator. */
    public boolean hasDefaultIdFormat() {
        return DEFAULT_ID_FORMAT.equals(idFormat);
    }

    /**
     * A copy of this rule with another {@code idFormat}; used to insert the
     * deep link when Mirador's public address is known only at run time.
     */
    public CrossToolReferenceRule withIdFormat(String newIdFormat) {
        return new CrossToolReferenceRule(attributeName, targetSystem, relationshipTypeName, attributeOwnerIsSource,
                authority, identifier, readIdentifiers, idPattern, newIdFormat, separator, emptyValue, listField,
                formPatterns, formBudgets);
    }

    /**
     * This rule with the {@code idFormat} that is actually written. Without a
     * format of its own, an {@code rmmId} rule writes the deep link
     * {@code <publicUrl>/a/{id}} once a public address is known; a format of
     * its own may insert the address as {@code {publicUrl}}. Without an
     * address the bare identifier is written in both cases; both forms can be
     * read back.
     */
    public CrossToolReferenceRule effectiveFor(String publicUrl) {
        String base = publicUrl == null ? "" : publicUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (idFormat.contains(PUBLIC_URL_PLACEHOLDER)) {
            // Without an address the link would be relative and dead: write
            // the bare identifier instead, as with the default
            return withIdFormat(base.isEmpty() ? DEFAULT_ID_FORMAT : idFormat.replace(PUBLIC_URL_PLACEHOLDER, base));
        }
        if (hasDefaultIdFormat() && IDENTIFIER_RMM_ID.equals(identifier) && !base.isEmpty()) {
            return withIdFormat(base + DEEP_LINK_PATH + ID_PLACEHOLDER);
        }
        return this;
    }

    /**
     * The key under which the reconciliation state keeps what this rule read:
     * relationship type, direction and target system. Several read rules may
     * interpret the same tool field under different types; each reads with its
     * own pattern and therefore needs its own state.
     */
    public String ruleKey() {
        return relationshipTypeName + "|" + (attributeOwnerIsSource ? "outgoing" : "incoming") + "|"
                + getTargetSystemOrAny();
    }

    /** Whether Mirador writes this field (authority {@code rmm} or {@code shared}). */
    public boolean isWrittenByRmm() {
        return authority != Authority.TOOL;
    }

    /** A copy of this rule with authority {@code tool} (read only). */
    public CrossToolReferenceRule asToolLed() {
        return new CrossToolReferenceRule(attributeName, targetSystem, relationshipTypeName, attributeOwnerIsSource,
                Authority.TOOL, identifier, readIdentifiers, idPattern, idFormat, separator, emptyValue, listField,
                formPatterns, formBudgets);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Extracts the identifiers of all read forms from a field value, in order
     * of appearance, duplicates once. An empty value carries no reference. A
     * form without a pattern takes the whole trimmed value as one identifier;
     * it applies only if no form with a pattern matched (otherwise a link
     * built from Mirador UUIDs would also count as one external identifier).
     */
    public List<ExtractedId> extractReferences(String fieldValue) {
        if (fieldValue == null || fieldValue.isBlank()) {
            return List.of();
        }
        LinkedHashMap<String, ExtractedId> found = new LinkedHashMap<>();
        boolean patternHit = false;
        for (Map.Entry<String, Pattern> form : readPatterns.entrySet()) {
            if (form.getValue() == null) {
                continue;
            }
            //The pattern of a form declared by a binding reads under its budget
            for (String id : PatternBudget.ids(form.getValue(), fieldValue, budgetOf(form.getKey()))) {
                patternHit = true;
                found.putIfAbsent(form.getKey() + "\0" + id, new ExtractedId(form.getKey(), id));
            }
        }
        if (!patternHit) {
            for (Map.Entry<String, Pattern> form : readPatterns.entrySet()) {
                if (form.getValue() == null) {
                    found.putIfAbsent(form.getKey() + "\0" + fieldValue.trim(),
                            new ExtractedId(form.getKey(), fieldValue.trim()));
                    break;
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    /**
     * The budget of the pattern of a read form; {@code null} = unbounded (a
     * built-in form, or a form from the adapter type or the mapping).
     */
    private PatternBudget budgetOf(String form) {
        return isToolIdentifier(form) ? formBudgets.get(form) : null;
    }

    /** Whether the tool form {@code form} of this rule carries a budget (a form declared by a binding). */
    public boolean hasBudgetFor(String form) {
        return formBudgets.containsKey(form);
    }

    /** Like {@link #extractReferences}, for list fields: one value per entry. */
    public List<ExtractedId> extractReferences(List<String> fieldValues) {
        if (fieldValues == null || fieldValues.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<ExtractedId> found = new LinkedHashSet<>();
        for (String value : fieldValues) {
            found.addAll(extractReferences(value));
        }
        return new ArrayList<>(found);
    }

    /** Only the identifiers, without their form; in order of first appearance, duplicates once. */
    public List<String> extractIds(String attributeValue) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (ExtractedId reference : extractReferences(attributeValue)) {
            ids.add(reference.id());
        }
        return new ArrayList<>(ids);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** One identifier formatted with {@code idFormat}. */
    public String formatId(String id) {
        return idFormat.replace(ID_PLACEHOLDER, id);
    }

    /** The value of a text field for the identifiers in this order; an empty list yields {@code emptyValue}. */
    public String formatIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return emptyValue;
        }
        LinkedHashSet<String> formatted = new LinkedHashSet<>();
        for (String id : ids) {
            formatted.add(formatId(id));
        }
        return String.join(separator, formatted);
    }

    /** The entries of a list field for the identifiers in this order. */
    public List<String> formatIdsAsList(List<String> ids) {
        LinkedHashSet<String> formatted = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                formatted.add(formatId(id));
            }
        }
        return new ArrayList<>(formatted);
    }

    /**
     * The round-trip check: what the rule writes, it must read back as exactly
     * this identifier; otherwise every run would see a "changed" field. Returns
     * {@code null} on success, otherwise the finding.
     */
    public String verifyRoundTrip(String sampleId) {
        String written = formatId(sampleId);
        List<ExtractedId> read = extractReferences(written);
        if (read.size() == 1 && identifier.equals(read.get(0).identifier()) && sampleId.equals(read.get(0).id())) {
            return null;
        }
        return "writing <" + sampleId + "> as <" + written + "> reads back as " + read
                + " instead of one <" + identifier + ":" + sampleId + ">";
    }

    @Override
    public String toString() {
        return "CrossToolReferenceRule{" + attributeName + " -> " + (targetSystem == null ? "*" : targetSystem)
                + " as " + relationshipTypeName + (attributeOwnerIsSource ? " (owner is source)" : " (owner is target)")
                + ", authority " + authority.configValue() + ", identifier " + identifier
                + (listField ? ", list field" : "")
                + (readIdentifiers.isEmpty() ? "" : ", reads " + readIdentifiers)
                + (idPattern == null ? "" : ", pattern " + idPattern.pattern()) + "}";
    }
}
