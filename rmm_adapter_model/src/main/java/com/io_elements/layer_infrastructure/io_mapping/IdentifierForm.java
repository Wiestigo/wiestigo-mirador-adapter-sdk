package com.io_elements.layer_infrastructure.io_mapping;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * An identifier form of a tool: how people or the tool itself name an
 * artifact in a reference field, for example {@code PROJ-12} ({@code key})
 * in a tool such as Jira or {@code SYS-REQ-7} ({@code documentKey}) in one
 * such as Jama. It is defined in the {@code identifiers} section of the
 * mapping file of the tool that owns the identifier; without an entry the
 * adapter's default applies. Rules of other tools refer to the form by its
 * name in {@code identifier} or {@code readIdentifiers}.
 *
 * @param name      name of the form, as rules refer to it
 * @param attribute the tool field (label, as in {@code attributeNameMappings})
 *                  that carries the identifier on the artifact; {@code null} =
 *                  the external id itself
 * @param pattern   read pattern: every match is one identifier, taken from
 *                  capturing group 1 if the pattern has one; {@code null} =
 *                  the whole trimmed value
 * @param format    write template containing {@code {id}}
 * @param sample    a sample identifier that the pattern reads, used for the
 *                  round-trip check; {@code null} = {@code ABC-123}, provided
 *                  the pattern reads it
 * @param budget    the budget of the pattern ({@link PatternBudget}): only
 *                  forms declared by a binding carry one; {@code null} =
 *                  unbounded (a form from the adapter type or the mapping)
 */
public record IdentifierForm(String name, String attribute, Pattern pattern, String format, String sample,
                             PatternBudget budget) {

    public static final String ID_PLACEHOLDER = "{id}";
    public static final String DEFAULT_FORMAT = ID_PLACEHOLDER;
    public static final String DEFAULT_SAMPLE = "ABC-123";

    /** Without a sample of its own. */
    public IdentifierForm(String name, String attribute, Pattern pattern, String format) {
        this(name, attribute, pattern, format, null);
    }

    /** Without a budget: a form from the adapter type or the mapping. */
    public IdentifierForm(String name, String attribute, Pattern pattern, String format, String sample) {
        this(name, attribute, pattern, format, sample, null);
    }

    public IdentifierForm {
        Objects.requireNonNull(name, "name");
        format = format == null || format.isBlank() ? DEFAULT_FORMAT : format;
    }

    /** Whether a tool field carries the identifier (instead of the external id). */
    public boolean hasAttribute() {
        return attribute != null && !attribute.isBlank();
    }

    /** One identifier in the write template. */
    public String formatId(String id) {
        return format.replace(ID_PLACEHOLDER, id);
    }

    /** The identifiers of this form in a field value, in order, duplicates once. */
    public List<String> extractIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        if (pattern == null) {
            return List.of(value.trim());
        }
        return PatternBudget.ids(pattern, value, budget);
    }

    /**
     * The first match of the pattern in a value, under the form's budget if it
     * has one (no match on timeout); empty without a pattern.
     */
    public Optional<MatchResult> firstMatch(String value) {
        if (pattern == null || value == null) {
            return Optional.empty();
        }
        return PatternBudget.firstMatch(pattern, value, budget);
    }

    /**
     * The sample for the round-trip check: the declared sample, otherwise
     * {@code ABC-123} if the pattern reads it as exactly one identifier;
     * {@code null} if neither fits, in which case the form must declare one.
     */
    public String roundTripSample() {
        if (sample != null && !sample.isBlank()) {
            return sample.trim();
        }
        //The default sample is usable if the pattern reads it back from its written form
        List<String> read = extractIds(formatId(DEFAULT_SAMPLE));
        return read.size() == 1 && DEFAULT_SAMPLE.equals(read.get(0)) ? DEFAULT_SAMPLE : null;
    }

    /** The round-trip check with the form's own sample; without a usable sample, a finding that asks for one. */
    public String verifyRoundTrip() {
        if (pattern == null && !DEFAULT_FORMAT.equals(format)) {
            //Without a pattern the whole value is the identifier: a template that
            //adds anything around it would never read the same identifier back
            return "identifier form <" + name + "> has no pattern but the decorating format <" + format
                    + ">; without a pattern the whole value is the id, so the format must be " + ID_PLACEHOLDER;
        }
        String probe = roundTripSample();
        if (probe == null) {
            return "identifier form <" + name + "> needs a <sample> id its pattern reads (" + pattern.pattern() + ")";
        }
        return verifyRoundTrip(probe);
    }

    /**
     * The round-trip check: what the template writes, the pattern must read as
     * exactly this identifier. Returns {@code null} on success, otherwise the
     * finding.
     */
    public String verifyRoundTrip(String sampleId) {
        String written = formatId(sampleId);
        List<String> read = extractIds(written);
        if (read.size() == 1 && sampleId.equals(read.get(0))) {
            return null;
        }
        return "identifier form <" + name + "> writes <" + sampleId + "> as <" + written
                + "> and reads it back as " + read;
    }
}
