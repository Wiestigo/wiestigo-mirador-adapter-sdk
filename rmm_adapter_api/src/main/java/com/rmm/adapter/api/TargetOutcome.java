package com.rmm.adapter.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of a write hook of the {@link TargetWriter}: on success the ID the
 * tool assigned (null for operations without a new ID), otherwise the failure.
 * A failed element gets no receipt and is retried in the next run; the other
 * elements of a batch continue.
 *
 * <p><b>Receipt</b> ({@code appliedFields}; for
 * {@link TargetWriter#updateArtifact}): the fields of the mapped view the tool
 * accepted, as tool identifiers like the keys of {@code fields}. An adapter
 * may skip a field the tool does not know or does not allow to be written, but
 * not silently: the sync core acknowledges only confirmed fields, and an
 * unconfirmed reference field comes again in the next run. {@code null} means
 * "no statement": normal for hooks without fields (delete, hierarchy); for an
 * update it confirms no field.
 *
 * <p><b>Reasons</b> ({@code notApplied}, field to reason): why a field was
 * skipped (for example "read-only"); the core shows the reason on the element.
 * Keys are tool identifiers; a field never appears in both. A reason is a
 * short, fixed text for the operator: no secret, no response of the tool.
 *
 * <p><b>Notes</b> ({@code notes}, {@link #withNotes(Collection)}): what the
 * operator should know about an element that has not failed (for example "two
 * images without a web address were dropped"). The core shows each note on the
 * element in the run report, as one line, shortened and without the secrets of
 * the binding. A note is not an error and does not change the receipt. It is a
 * short, non-empty text: no secret and no content of the element.
 *
 * <p><b>Skipped</b> ({@link #skipped(String)}): the element was deliberately
 * not written and has not failed, for example a comment of which nothing
 * presentable would remain in the tool. The core gives it no receipt, reports
 * no error, shows the note that explains the skip, and presents the element
 * again in the next run. A skipped result carries no identifier, no failure,
 * no receipt and at least one note. Only creating a comment may skip
 * ({@link TargetWriter#createCommentWithOutcome},
 * {@link TargetWriter#createComments}); in any other hook it violates the
 * contract and the element fails.
 */
public record TargetOutcome(String externalId, Exception failure, Set<String> appliedFields,
                            Map<String, String> notApplied, List<String> notes, boolean skipped) {

    public TargetOutcome {
        appliedFields = appliedFields == null ? null : inGivenOrder(appliedFields);
        notApplied = reasonsOf(notApplied, appliedFields);
        notes = notesOf(notes);
        if (skipped && (externalId != null || failure != null || appliedFields != null || !notApplied.isEmpty())) {
            throw new IllegalArgumentException("A skipped element carries no identifier, no failure and"
                    + " no receipt");
        }
        if (skipped && notes.isEmpty()) {
            throw new IllegalArgumentException("A skipped element needs a note that says why");
        }
    }

    /** A result without reasons ({@link #notApplied()} empty) and without notes, not skipped. */
    public TargetOutcome(String externalId, Exception failure, Set<String> appliedFields) {
        this(externalId, failure, appliedFields, Map.of(), List.of(), false);
    }

    /** A result without notes ({@link #notes()} empty), not skipped. */
    public TargetOutcome(String externalId, Exception failure, Set<String> appliedFields,
                         Map<String, String> notApplied) {
        this(externalId, failure, appliedFields, notApplied, List.of(), false);
    }

    public static TargetOutcome success() {
        return new TargetOutcome(null, null, null);
    }

    public static TargetOutcome success(String externalId) {
        return new TargetOutcome(externalId, null, null);
    }

    /** Success with a receipt: the tool accepted exactly these fields (tool identifiers). */
    public static TargetOutcome applied(Collection<String> appliedFields) {
        return applied(appliedFields, Map.of());
    }

    /**
     * Success with a receipt and the reasons for the skipped fields: the tool
     * accepted {@code appliedFields} and did not accept the keys of
     * {@code notApplied}, each with its reason.
     */
    public static TargetOutcome applied(Collection<String> appliedFields, Map<String, String> notApplied) {
        if (appliedFields == null) {
            throw new IllegalArgumentException(
                    "appliedFields must not be null — success() is the answer without a statement");
        }
        return new TargetOutcome(null, null, new LinkedHashSet<>(appliedFields), notApplied);
    }

    public static TargetOutcome failure(Exception failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        return new TargetOutcome(null, failure, null);
    }

    /**
     * Skipped: deliberately not written and not failed; the note tells the
     * operator why. Only when creating a comment; add further notes with
     * {@link #withNotes}.
     */
    public static TargetOutcome skipped(String note) {
        return new TargetOutcome(null, null, null, Map.of(), note == null ? null : List.of(note), true);
    }

    /**
     * The same result with these notes added after the existing ones, in their
     * order. Without new notes, the same object.
     */
    public TargetOutcome withNotes(Collection<String> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        List<String> all = new ArrayList<>(notes);
        all.addAll(more);
        return new TargetOutcome(externalId, failure, appliedFields, notApplied, all, skipped);
    }

    /** {@link #withNotes(Collection)} with one note. */
    public TargetOutcome withNote(String note) {
        return withNotes(Collections.singletonList(note));
    }

    /**
     * Not failed. A skipped element is not failed either, but it is not
     * written ({@link #skipped()}).
     */
    public boolean isSuccess() {
        return failure == null;
    }

    /** Whether the adapter stated which fields arrived. */
    public boolean reportsFields() {
        return appliedFields != null;
    }

    /** Whether the tool confirmed this field (tool identifier). Without a receipt: no. */
    public boolean isApplied(String fieldLabel) {
        return isSuccess() && appliedFields != null && fieldLabel != null && appliedFields.contains(fieldLabel);
    }

    /**
     * The reasons as an immutable copy in the adapter's order; {@code null}
     * becomes empty. A field without a name or without a reason, and a field
     * that is also in the receipt, violate the contract.
     */
    /** An unmodifiable copy that keeps the order in which the adapter named the fields. */
    private static Set<String> inGivenOrder(Set<String> fields) {
        Set<String> copy = new LinkedHashSet<>();
        for (String field : fields) {
            copy.add(java.util.Objects.requireNonNull(field, "appliedFields must not contain null"));
        }
        return Collections.unmodifiableSet(copy);
    }

    private static Map<String, String> reasonsOf(Map<String, String> notApplied, Set<String> appliedFields) {
        if (notApplied == null || notApplied.isEmpty()) {
            return Map.of();
        }
        Map<String, String> reasons = new LinkedHashMap<>();
        notApplied.forEach((field, reason) -> {
            if (field == null || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("notApplied needs a name and a reason for every field: <"
                        + field + "> → <" + reason + ">");
            }
            if (appliedFields != null && appliedFields.contains(field)) {
                throw new IllegalArgumentException("The field <" + field + "> is in the receipt and at the same time"
                        + " not written");
            }
            reasons.put(field, reason);
        });
        return Collections.unmodifiableMap(reasons);
    }

    /**
     * The notes as an immutable copy in the adapter's order; {@code null}
     * becomes empty. A missing or empty note violates the contract.
     */
    private static List<String> notesOf(List<String> notes) {
        if (notes == null || notes.isEmpty()) {
            return List.of();
        }
        for (String note : notes) {
            if (note == null || note.isBlank()) {
                throw new IllegalArgumentException("A note needs a text: <" + note + ">");
            }
        }
        return List.copyOf(notes);
    }
}
