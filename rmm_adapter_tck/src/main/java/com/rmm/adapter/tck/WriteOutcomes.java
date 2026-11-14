package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.TargetOutcome;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules for the outcomes a write hook answers: one per element in input
 * order, none of them {@code null} or "skipped", and no field reported that
 * the hook was never given.
 */
final class WriteOutcomes {

    private WriteOutcomes() {
    }

    /** A batch hook answers exactly one outcome per element; a list of another size cannot be mapped. */
    static void requireOnePerElement(String hook, List<TargetOutcome> outcomes, int elements) {
        if (outcomes == null || outcomes.size() != elements) {
            throw new TckViolation(hook + " must answer exactly one TargetOutcome per element, in input"
                    + " order — got " + (outcomes == null ? "null" : outcomes.size()) + " for " + elements
                    + " elements; Mirador maps outcomes to elements by position");
        }
    }

    /**
     * The outcome of the element at {@code position}. A batch hook that
     * answers {@code null} in the place of an outcome is a violation.
     */
    static TargetOutcome outcomeOf(String hook, List<TargetOutcome> outcomes, int position) {
        TargetOutcome outcome = outcomes.get(position);
        if (outcome == null) {
            throw new TckViolation(hook + " answered null instead of a TargetOutcome for element " + position
                    + " — Mirador maps outcomes to elements by position and reads from each whether its element"
                    + " was written");
        }
        return outcome;
    }

    /**
     * An outcome of a hook that does not create a comment. It is never
     * "skipped": only creating a comment may be skipped, at every other hook
     * the host fails the element. And a field reported as not written
     * ({@code notApplied}) is one the hook was given.
     *
     * @param sent the element written, {@code null} if it has no fields
     */
    static void requireWritten(String hook, TargetOutcome outcome, ElementArtifact sent) {
        if (outcome == null) {
            return;
        }
        if (outcome.skipped()) {
            throw new TckViolation(hook + " answered TargetOutcome.skipped " + outcome.notes() + " — only creating"
                    + " a comment may be skipped; at every other hook Mirador fails the element");
        }
        if (sent != null && !outcome.notApplied().isEmpty()) {
            Set<String> given = new HashSet<>();
            if (sent.getFields() != null) {
                sent.getFields().values().forEach(field -> given.add(field.getLabelValue()));
            }
            if (!given.containsAll(outcome.notApplied().keySet())) {
                throw new TckViolation(hook + " reported fields as not written that it was never given: "
                        + outcome.notApplied().keySet() + " (sent " + given + ")");
            }
        }
    }
}
