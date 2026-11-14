package com.rmm.remote.tck;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.rmm.remote.tck.WireJson.textOf;

/**
 * The rules for the identifier kinds of a descriptor ({@code identifierKinds}
 * in {@code docs/REMOTE_PROTOCOL.md}, "GET /descriptor"), as the host reads
 * them: name, each name at most once, pattern, format with {@code {id}},
 * lengths and round trip.
 */
final class IdentifierKindChecks {

    /** The name of an identifier kind ({@code identifierKinds}). */
    static final Pattern IDENTIFIER_KIND_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    /** Maximum number of identifier kinds a service declares per binding (the host's limit). */
    static final int MAX_IDENTIFIER_KINDS = 32;
    /** Maximum length of an identifier kind's pattern (the host's limit). */
    static final int MAX_PATTERN_LENGTH = 256;
    /** Maximum length of every other field of an identifier kind (the host's limit). */
    static final int MAX_KIND_FIELD_LENGTH = 500;
    /** The round-trip sample when an identifier kind declares none (as in the adapter contract). */
    static final String DEFAULT_SAMPLE = "ABC-123";
    /** The identifier kinds built into Mirador; a service must not declare them. */
    static final Set<String> BUILT_IN_KINDS = Set.of("rmmId", "externalId");

    private IdentifierKindChecks() {
    }

    /** Requires that the identifier kinds of the descriptor follow the rules; {@code where} starts the message. */
    static void require(String where, JsonNode descriptor) {
        Set<String> seen = new HashSet<>();
        JsonNode kinds = descriptor.path("identifierKinds");
        if (kinds.size() > MAX_IDENTIFIER_KINDS) {
            throw new ServiceContractViolation(where + " declares " + kinds.size() + " identifier kinds — at most "
                    + MAX_IDENTIFIER_KINDS + " identifier kinds (docs/REMOTE_PROTOCOL.md, \"GET /descriptor\","
                    + " identifierKinds; Mirador holds the connection DEFECT INSTANCE_INCONSISTENT)");
        }
        for (JsonNode kind : kinds) {
            String name = requireName(where, kind, seen);
            requirePatternAndFormat(where, kind, name);
            String tooLong = tooLongField(kind);
            if (tooLong != null) {
                throw new ServiceContractViolation(where + ": identifier kind <" + name + "> has " + tooLong
                        + " (docs/REMOTE_PROTOCOL.md, \"GET /descriptor\", identifierKinds; Mirador holds the"
                        + " connection DEFECT INSTANCE_INCONSISTENT)");
            }
            String roundTrip = roundTripRefusal(kind);
            if (roundTrip != null) {
                throw new ServiceContractViolation(where + ": identifier kind <" + name + "> " + roundTrip
                        + " — what the format writes, the pattern must read back as the same id"
                        + " (docs/REMOTE_PROTOCOL.md, \"GET /descriptor\", identifierKinds)");
            }
        }
    }

    /** The name of the kind: well-formed, not built into Mirador, and not among the names {@code seen} so far. */
    private static String requireName(String where, JsonNode kind, Set<String> seen) {
        String name = textOf(kind, "name");
        if (!IDENTIFIER_KIND_NAME.matcher(name).matches() || BUILT_IN_KINDS.contains(name)) {
            throw new ServiceContractViolation(where + " declares the identifier kind <" + name + "> — a name of"
                    + " letters, digits, '-' and '_' that starts with a letter, and not rmmId or externalId"
                    + " (built into Mirador)");
        }
        if (!seen.add(name)) {
            throw new ServiceContractViolation(where + ": identifier kind <" + name + "> is declared twice —"
                    + " each name at most once (docs/REMOTE_PROTOCOL.md, \"GET /descriptor\", identifierKinds;"
                    + " Mirador holds the connection DEFECT INSTANCE_INCONSISTENT)");
        }
        return name;
    }

    /** A declared pattern is a regular expression, and a declared format names {@code {id}}. */
    private static void requirePatternAndFormat(String where, JsonNode kind, String name) {
        if (kind.hasNonNull("pattern")) {
            try {
                Pattern.compile(kind.get("pattern").asString(""));
            } catch (PatternSyntaxException unreadable) {
                throw new ServiceContractViolation(where + " declares the identifier kind <" + name + "> with"
                        + " a pattern that is no regular expression: " + kind.get("pattern").asString(""));
            }
        }
        String format = textOf(kind, "format");
        if (!format.isBlank() && !format.contains("{id}")) {
            throw new ServiceContractViolation(where + " declares the identifier kind <" + name + "> with the"
                    + " format <" + format + "> — a format names {id}");
        }
    }

    /** The first field of an identifier kind that is too long (the host's limits); null when none is. */
    private static String tooLongField(JsonNode kind) {
        String pattern = textOf(kind, "pattern");
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            return "a pattern of " + pattern.length() + " characters (at most " + MAX_PATTERN_LENGTH + ")";
        }
        for (String field : List.of("name", "fieldLabel", "format", "sample", "description")) {
            String value = textOf(kind, field);
            if (value.length() > MAX_KIND_FIELD_LENGTH) {
                return "a " + field + " of " + value.length() + " characters (at most " + MAX_KIND_FIELD_LENGTH + ")";
            }
        }
        return null;
    }

    /**
     * The round trip of an identifier kind by the rule of the adapter
     * contract ({@code IdentifierKind.verifyRoundTrip} in
     * {@code rmm_adapter_api}, rebuilt here without its classes). The sample
     * is {@code sample}; without one it is {@value #DEFAULT_SAMPLE}, provided
     * the pattern reads it from the format. What the format writes with the
     * sample, the pattern must read back as exactly that one id. Returns null
     * when the kind passes.
     */
    private static String roundTripRefusal(JsonNode kind) {
        String pattern = kind.hasNonNull("pattern") ? kind.get("pattern").asString("") : null;
        String format = textOf(kind, "format").isBlank() ? "{id}" : textOf(kind, "format");
        String sample = textOf(kind, "sample").trim();
        String probe;
        if (!sample.isEmpty()) {
            probe = sample;
        } else if (pattern == null) {
            probe = DEFAULT_SAMPLE;
        } else {
            probe = List.of(DEFAULT_SAMPLE).equals(readIds(pattern, format.replace("{id}", DEFAULT_SAMPLE)))
                    ? DEFAULT_SAMPLE : null;
        }
        if (probe == null) {
            return "needs a sample id its pattern <" + pattern + "> reads (sample)";
        }
        String written = format.replace("{id}", probe);
        List<String> read = readIds(pattern, written);
        return read.size() == 1 && probe.equals(read.get(0)) ? null
                : "writes <" + probe + "> as <" + written + "> and reads it back as " + read;
    }

    /** The ids the pattern reads from a value (capture group 1, else the whole match); no pattern: the whole value. */
    private static List<String> readIds(String pattern, String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        if (pattern == null) {
            return List.of(value.trim());
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(pattern).matcher(value);
        while (matcher.find()) {
            String id = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
            if (id != null && !id.isBlank()) {
                ids.add(id.trim());
            }
        }
        return List.copyOf(ids);
    }
}
