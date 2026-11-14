package com.rmm.adapter.api;

import java.util.List;

/**
 * The feature selection of one tool element. {@code externalId} is the
 * identifier of the element in the tool; in Mirador it is used at exactly one
 * place of use and therefore also identifies that place. {@code featureNames}
 * is the selection as qualified names {@code TREE.FEATURE}. An empty list
 * means no selection (the place applies in no variant); that too is a statement
 * the core transfers.
 *
 * <p>From the tool to Mirador, the core resolves the names against the feature
 * trees visible to the specification; an unknown or invisible name is reported
 * in the sync report and not stored, while the other names apply.
 */
public record ElementFeatures(String externalId, List<String> featureNames) {
    public ElementFeatures {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId must not be empty");
        }
        featureNames = featureNames == null ? List.of() : List.copyOf(featureNames);
    }
}
