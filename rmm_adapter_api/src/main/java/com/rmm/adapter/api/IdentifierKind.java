package com.rmm.adapter.api;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * An identifier kind of the tool that an adapter declares as a default: how
 * people or the tool itself name an artifact in a reference field, for example
 * {@code PROJ-12} as {@code key} in a tool such as Jira or {@code SYS-REQ-7} as
 * {@code documentKey} in a tool such as Jama. Mirador needs it to read such
 * fields; it writes with its own internal ID. The tool's mapping file (section
 * {@code identifiers}) may override each default.
 *
 * @param name        name of the kind as rules use it in {@code identifier} and
 *                    {@code readIdentifiers} ({@code key},
 *                    {@code documentKey}); not {@code rmmId} or
 *                    {@code externalId}, which are built in.
 * @param fieldLabel  the tool field (its label, as in the
 *                    {@code attributeNameMappings}) that carries the identifier
 *                    on the artifact; {@code null} = the external ID itself.
 * @param pattern     regular expression for reading: every match is one
 *                    identifier, capturing group 1 is the identifier;
 *                    {@code null} = the whole field value.
 * @param format      template for writing, containing {@code {id}};
 *                    {@code null} = {@code {id}}. Round trip required: what the
 *                    format writes, the pattern must read as the same
 *                    identifier.
 * @param sample      a sample identifier that the pattern reads (for example
 *                    {@code PROJ-12}), used to check the round trip. Required
 *                    as soon as the pattern does not read the default
 *                    {@code ABC-123}; the TCK then demands it.
 * @param description short description for administration and diagnostics.
 */
public record IdentifierKind(String name, String fieldLabel, String pattern, String format, String sample,
                             String description) {

    public static final String ID_PLACEHOLDER = "{id}";

    public IdentifierKind {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("identifier kind name must not be blank");
        }
        if (!name.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("identifier kind name '" + name
                    + "' may only carry letters, digits, '-' and '_'");
        }
        if ("rmmId".equals(name) || "externalId".equals(name)) {
            throw new IllegalArgumentException("identifier kind '" + name
                    + "' is built into Mirador and cannot be declared by an adapter");
        }
        if (pattern != null) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("identifier kind '" + name + "' has an invalid pattern: "
                        + e.getMessage(), e);
            }
        }
        format = format == null || format.isBlank() ? ID_PLACEHOLDER : format;
        if (!format.contains(ID_PLACEHOLDER)) {
            throw new IllegalArgumentException("identifier kind '" + name + "' has a format without " + ID_PLACEHOLDER);
        }
    }

    /** A kind that is read as a field: a pattern without a write format and without its own sample. */
    public static IdentifierKind of(String name, String fieldLabel, String pattern, String description) {
        return new IdentifierKind(name, fieldLabel, pattern, null, null, description);
    }

    /** The same kind with a sample identifier for the round trip. */
    public IdentifierKind withSample(String sampleId) {
        return new IdentifierKind(name, fieldLabel, pattern, format, sampleId, description);
    }

    /**
     * The sample for the round trip: the declared sample identifier, otherwise
     * {@code ABC-123} if the pattern reads it as exactly one identifier;
     * {@code null} if no sample fits, in which case the declaration is missing.
     */
    public String roundTripSample() {
        return roundTripSampleOf(sample, pattern, format);
    }

    public static final String DEFAULT_SAMPLE = "ABC-123";

    /**
     * The round-trip check with the kind's own sample: {@code null} if the
     * pattern reads exactly {@code sample} back from {@code format(sample)},
     * otherwise the problem, also if no sample fits (then {@code sample} is
     * missing). The same rule the sync core reads with.
     */
    public String verifyRoundTrip() {
        String probe = roundTripSample();
        if (probe == null) {
            return "identifier kind <" + name + "> needs a sample id its pattern <" + pattern
                    + "> reads — declare it with withSample(...)";
        }
        String written = format.replace(ID_PLACEHOLDER, probe);
        List<String> read = readIds(written);
        if (read.size() == 1 && probe.equals(read.get(0))) {
            return null;
        }
        return "identifier kind <" + name + "> writes <" + probe + "> as <" + written + "> and reads it back as "
                + read;
    }

    /** The identifiers of this kind in a value; without a pattern, the whole trimmed value. */
    public List<String> readIds(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        if (pattern == null) {
            return List.of(value.trim());
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = Pattern.compile(pattern).matcher(value);
        while (matcher.find()) {
            String id = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Shared rule for descriptor defaults and kinds from the mapping file: the
     * default sample is usable if the pattern reads it back from the written
     * form ({@code format(ABC-123)}) as exactly one identifier.
     *
     * @return the declared sample if there is one, else the default sample if
     *         it is usable, else {@code null} (also for a pattern that does
     *         not compile)
     */
    public static String roundTripSampleOf(String declaredSample, String patternText, String formatText) {
        if (declaredSample != null && !declaredSample.isBlank()) {
            return declaredSample.trim();
        }
        if (patternText == null) {
            return DEFAULT_SAMPLE;
        }
        String written = (formatText == null ? ID_PLACEHOLDER : formatText).replace(ID_PLACEHOLDER, DEFAULT_SAMPLE);
        List<String> read;
        try {
            read = new IdentifierKind("probe", null, patternText, null, null, null).readIds(written);
        } catch (IllegalArgumentException invalidPattern) {
            //A pattern that does not compile reads nothing back; declaring the kind reports it by its name
            return null;
        }
        return read.size() == 1 && DEFAULT_SAMPLE.equals(read.get(0)) ? DEFAULT_SAMPLE : null;
    }
}
