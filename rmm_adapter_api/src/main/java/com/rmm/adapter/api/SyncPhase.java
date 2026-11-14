package com.rmm.adapter.api;

import java.util.Set;

/**
 * The element phases of a synchronization run that an adapter declares per
 * direction ({@link AdapterCapabilities#reads()} from the tool to Mirador,
 * {@link AdapterCapabilities#writes()} from Mirador to the tool). The sync core
 * calls and reconciles only declared phases: what an adapter does not declare,
 * the core never reads or writes, and it draws no conclusion from the absence.
 * Both directions follow the same rule and the same TCK check.
 *
 * <p>{@link #ARTIFACTS} is required in every declared direction; the hierarchy
 * phase of the inbound direction depends on the artifacts and is not declared
 * separately.
 *
 * <p>{@link #ATTACHMENTS}: attachments are files on artifacts, and besides the
 * page with metadata their contract needs a byte channel
 * ({@link SourceReader#openAttachment}, {@link AttachmentContentSource}). An
 * adapter therefore declares the phase explicitly; {@link #all()} does not
 * contain it, so that no adapter promises a phase it does not serve just by
 * using the shorthand.
 */
public enum SyncPhase {
    ARTIFACTS,
    RELATIONSHIPS,
    COMMENTS,
    ATTACHMENTS,
    /**
     * Variability: feature trees and variants flow only from Mirador to the
     * tool (they are product definition), the feature selection per element in
     * both declared directions. Declared explicitly like {@link #ATTACHMENTS};
     * {@link #all()} and {@link #allWithAttachments()} do not contain it.
     * Without the phase the core transfers variability through the reserved
     * mapping targets {@code @features} and {@code @variants} in the artifact
     * phase.
     */
    VARIABILITY;

    /**
     * The three element phases, for adapters that serve artifacts,
     * relationships and comments. Without {@link #ATTACHMENTS}: attachments
     * need the byte channel and are declared separately
     * ({@link #allWithAttachments()}).
     */
    public static Set<SyncPhase> all() {
        return Set.of(ARTIFACTS, RELATIONSHIPS, COMMENTS);
    }

    /** The four phases up to attachments, without {@link #VARIABILITY}, which is declared separately. */
    public static Set<SyncPhase> allWithAttachments() {
        return Set.of(ARTIFACTS, RELATIONSHIPS, COMMENTS, ATTACHMENTS);
    }

    /** All five phases, for adapters that also serve variability. */
    public static Set<SyncPhase> allWithVariability() {
        return Set.of(ARTIFACTS, RELATIONSHIPS, COMMENTS, ATTACHMENTS, VARIABILITY);
    }

    /** Artifacts only: the smallest valid declaration of a direction. */
    public static Set<SyncPhase> artifactsOnly() {
        return Set.of(ARTIFACTS);
    }
}
