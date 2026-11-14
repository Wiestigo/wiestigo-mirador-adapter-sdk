package com.rmm.adapter.api;

/**
 * A cross-tree constraint between two features: {@code kind} is
 * {@code REQUIRES} or {@code EXCLUDES}; both sides are qualified names
 * {@code TREE.FEATURE}. The other feature may be in another (global) tree.
 */
public record VariabilityConstraint(String kind, String feature, String otherFeature) {
    public VariabilityConstraint {
        if (!"REQUIRES".equals(kind) && !"EXCLUDES".equals(kind)) {
            throw new IllegalArgumentException("kind must be REQUIRES or EXCLUDES, not " + kind);
        }
        if (feature == null || feature.isBlank() || otherFeature == null || otherFeature.isBlank()) {
            throw new IllegalArgumentException("feature and otherFeature must not be empty");
        }
    }
}
