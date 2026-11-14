package com.rmm.adapter.api;

/**
 * A feature of a feature tree as it travels from Mirador to the tool.
 * {@code name} is the immutable technical identifier (unique per tree),
 * {@code kind} the kind relative to the parent node ({@code MANDATORY},
 * {@code OPTIONAL}, {@code ALTERNATIVE}, {@code OR}; {@code null} is read as
 * {@code OPTIONAL}), {@code parentName} the name of the parent feature in the
 * same tree or {@code null} at the top level. The order in
 * {@link VariabilityTree#features()} is the document order.
 */
public record VariabilityFeature(String name, String label, String description, String kind,
                                 String parentName) {
    public VariabilityFeature {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        kind = kind == null ? "OPTIONAL" : kind;
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind must be MANDATORY, OPTIONAL, ALTERNATIVE or OR, not " + kind);
        }
    }

    private static final java.util.Set<String> KINDS = java.util.Set.of("MANDATORY", "OPTIONAL", "ALTERNATIVE", "OR");
}
