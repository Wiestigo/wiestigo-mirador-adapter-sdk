package com.rmm.adapter.api;

import java.util.UUID;

/**
 * The run store: intermediate state between the phases of a run, such as links
 * and comments per project or a list of items. It is bound to exactly one run;
 * the host clears it at the end of the run, also after cancellation and
 * failure. What must outlive a run belongs in {@link AdapterContext#store()}.
 * After the end of the run every access throws {@link IllegalStateException},
 * also through a collection that is still held.
 *
 * <p>Names of collections match {@code [a-z0-9_]{1,40}}.
 */
public interface AdapterRunStore {

    /** The run this store is bound to. */
    UUID runId();

    /** A collection of the run; at the platform limit it throws {@link AdapterStoreLimitException}. */
    DocumentCollection collection(String name);
}
