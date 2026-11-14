package com.rmm.adapter.api;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Read scope of a run, in exactly one of three forms:
 *
 * <ul>
 *   <li><b>Full run</b> ({@link #full()}): deliver everything.</li>
 *   <li><b>Delta run</b> ({@link #since(Instant)}): deliver only elements
 *       created or changed since the instant (for example JQL
 *       {@code updated >= ...} in a tool such as Jira). The sync core passes
 *       this form only to adapters with the capability
 *       {@code supportsDeltaRead}; the instant comes from the last successful
 *       run of the same sync point.</li>
 *   <li><b>These identifiers</b> ({@link #ofIds(Collection)}): deliver exactly
 *       the named elements. This is the form of an event run in which the tool
 *       sent the changed (and deleted) identifiers. Every adapter must support
 *       it; it needs no capability, because every tool can read elements by
 *       identifier. A named element that is not delivered counts for the core
 *       as deleted in the tool (limited to the named identifiers). The adapter
 *       may deliver a superset (unnamed elements are taken over but never
 *       counted as missing); it only must not omit a named element that the
 *       tool has.</li>
 * </ul>
 *
 * @param changedSince instant of the delta run, otherwise null.
 * @param elementIds   identifiers of the form "these identifiers", otherwise null.
 */
public record ReadScope(Instant changedSince, Set<String> elementIds) {

    private static final ReadScope FULL = new ReadScope(null, null);

    public ReadScope {
        if (changedSince != null && elementIds != null) {
            throw new IllegalArgumentException("A ReadScope is either delta or 'these identifiers', never both");
        }
        if (elementIds != null) {
            if (elementIds.isEmpty()) {
                throw new IllegalArgumentException("elementIds must not be empty — use full() for full runs");
            }
            Set<String> inGivenOrder = new LinkedHashSet<>();
            for (String elementId : elementIds) {
                inGivenOrder.add(Objects.requireNonNull(elementId, "elementIds must not contain null"));
            }
            elementIds = Collections.unmodifiableSet(inGivenOrder);
        }
    }

    /** Full run: read everything. */
    public static ReadScope full() {
        return FULL;
    }

    /** Delta run: read only changes since the instant. */
    public static ReadScope since(Instant changedSince) {
        if (changedSince == null) {
            throw new IllegalArgumentException("changedSince must not be null — use full() for full runs");
        }
        return new ReadScope(changedSince, null);
    }

    /** These identifiers: read exactly the named elements. */
    public static ReadScope ofIds(Collection<String> elementIds) {
        if (elementIds == null || elementIds.isEmpty()) {
            throw new IllegalArgumentException("elementIds must not be empty — use full() for full runs");
        }
        return new ReadScope(null, new TreeSet<>(elementIds));
    }

    public boolean isDelta() {
        return changedSince != null;
    }

    /** The form "these identifiers". */
    public boolean isExplicit() {
        return elementIds != null;
    }

    /** Full run: neither delta nor identifiers. */
    public boolean isFull() {
        return changedSince == null && elementIds == null;
    }
}
