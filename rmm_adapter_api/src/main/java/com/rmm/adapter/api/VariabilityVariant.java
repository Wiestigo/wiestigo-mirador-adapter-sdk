package com.rmm.adapter.api;

import java.util.List;

/**
 * A variant as it travels from Mirador to the tool
 * ({@link TargetWriter#writeVariants}): name (unique per scope), optional code,
 * description, Mirador's validity verdict and the selected features as
 * qualified names {@code TREE.FEATURE}. Variants flow only from Mirador to the
 * tool.
 */
public record VariabilityVariant(String name, String code, String description, boolean global, boolean valid,
                                 List<String> selectedFeatures) {
    public VariabilityVariant {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        selectedFeatures = selectedFeatures == null ? List.of() : List.copyOf(selectedFeatures);
    }
}
