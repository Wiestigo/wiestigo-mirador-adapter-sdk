package com.rmm.adapter.api;

import java.util.List;

/**
 * A feature tree as it travels from Mirador to the tool
 * ({@link TargetWriter#writeFeatureTrees}): name (used instance-wide in the
 * transport form {@code TREE.FEATURE}), label, description, scope
 * ({@code global}: visible to every project), the features in document order
 * and the cross-tree constraints. The tool mirrors the tree and Mirador owns
 * it; trees never flow back.
 */
public record VariabilityTree(String name, String label, String description, boolean global,
                              List<VariabilityFeature> features, List<VariabilityConstraint> constraints) {
    public VariabilityTree {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        features = features == null ? List.of() : List.copyOf(features);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }
}
