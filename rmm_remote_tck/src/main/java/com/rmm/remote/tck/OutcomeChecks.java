package com.rmm.remote.tck;

import com.rmm.remote.tck.RemoteServiceContractTest.Expect;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

import static com.rmm.remote.tck.WireJson.abbreviated;
import static com.rmm.remote.tck.WireJson.textOf;
import static com.rmm.remote.tck.WireJson.textsOf;

/**
 * The rules of the outcome per element of a write answer
 * ({@code docs/REMOTE_PROTOCOL.md}, "Write calls"), read the way the host
 * reads them (case 5). In addition, none of the elements of the TCK may
 * fail.
 */
final class OutcomeChecks {

    private OutcomeChecks() {
    }

    /** Requires that the outcome of {@code call} follows the rules and carries what is expected. */
    static void requireOutcome(String call, JsonNode outcome, Expect expect) {
        String where = "Case 5: the outcome of " + call + " ";
        if (outcome == null || !outcome.isObject()) {
            throw new ServiceContractViolation(where + "is no JSON object: " + outcome);
        }
        if (outcome.hasNonNull("error")) {
            throw new ServiceContractViolation(where + "is an error <" + outcome.get("error").asString("") + "> — the"
                    + " service failed the well-formed element the TCK wrote (artifactToWrite, commentToWrite);"
                    + " an error also needs a non-empty reason: " + abbreviated(outcome.toString()));
        }
        requireNotes(where, outcome);
        if (isSkipped(outcome.path("skipped"))) {
            requireSkippedAlone(where, outcome, expect);
            return;
        }
        if (expect.id && textOf(outcome, "externalId").isBlank()) {
            throw new ServiceContractViolation(where + "names no externalId — creating answers the id the next read"
                    + " returns" + (expect.skippable ? " (or skipped with a reason)" : "") + ": " + outcome);
        }
        requireReasons(where, outcome, receiptOf(where, outcome, expect));
    }

    /** {@code notes}, when present, is an array of non-empty texts. */
    private static void requireNotes(String where, JsonNode outcome) {
        JsonNode notes = outcome.path("notes");
        if (!notes.isMissingNode() && !notes.isNull()) {
            if (!notes.isArray()) {
                throw new ServiceContractViolation(where + "carries notes that are no array: " + outcome);
            }
            for (JsonNode note : notes) {
                if (!note.isString() || note.asString("").isBlank()) {
                    throw new ServiceContractViolation(where + "carries a blank note — each note is a non-empty text"
                            + " for the operator: " + outcome);
                }
            }
        }
    }

    /** As the host reads it: skipped is any value except missing, {@code null} and {@code false}. */
    private static boolean isSkipped(JsonNode skipped) {
        return !skipped.isMissingNode() && !skipped.isNull() && !(skipped.isBoolean() && !skipped.asBoolean(false));
    }

    /** A skipped outcome is allowed for this call and carries a reason and nothing else. */
    private static void requireSkippedAlone(String where, JsonNode outcome, Expect expect) {
        if (!expect.skippable) {
            throw new ServiceContractViolation(where + "is skipped — only creating a comment may be skipped;"
                    + " Mirador fails the element on any other call: " + outcome);
        }
        JsonNode reasons = outcome.path("notApplied");
        if (outcome.path("skipped").path("reason").asString("").isBlank()
                || !textOf(outcome, "externalId").isBlank() || outcome.path("appliedFields").isArray()
                || reasons.isObject() && !reasons.isEmpty()) {
            throw new ServiceContractViolation(where + "is skipped without a reason or with an id, a receipt or"
                    + " reasons of fields — skipped carries {\"reason\": \"…\"} and nothing else: " + outcome);
        }
    }

    /** The fields the receipt ({@code appliedFields}) acknowledges; empty without one, which the call must allow. */
    private static Set<String> receiptOf(String where, JsonNode outcome, Expect expect) {
        Set<String> applied = new LinkedHashSet<>();
        JsonNode appliedFields = outcome.path("appliedFields");
        if (appliedFields.isMissingNode() || appliedFields.isNull()) {
            if (expect.receipt) {
                throw new ServiceContractViolation(where + "has no receipt (appliedFields) — an update in a batch"
                        + " (ARTIFACT_UPDATE) answers the accepted fields of each element: " + outcome);
            }
        } else {
            applied.addAll(textsOf(appliedFields, where + "appliedFields"));
        }
        return applied;
    }

    /** {@code notApplied}, when present, gives a reason per field and names no field of the receipt. */
    private static void requireReasons(String where, JsonNode outcome, Set<String> applied) {
        JsonNode notApplied = outcome.path("notApplied");
        if (!notApplied.isMissingNode() && !notApplied.isNull()) {
            if (!notApplied.isObject()) {
                throw new ServiceContractViolation(where + "carries notApplied that is no object: " + outcome);
            }
            notApplied.properties().forEach(reason -> {
                if (!reason.getValue().isString() || reason.getValue().asString("").isBlank()
                        || applied.contains(reason.getKey())) {
                    throw new ServiceContractViolation(where + "names the field <" + reason.getKey() + "> in"
                            + " notApplied without a reason, or although appliedFields acknowledges it — a field is"
                            + " never both: " + outcome);
                }
            });
        }
    }
}
