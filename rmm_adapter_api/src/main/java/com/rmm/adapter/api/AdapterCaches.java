package com.rmm.adapter.api;

/**
 * Caches of an adapter: tool metadata, page caches, image lists, everything
 * that can be reloaded from the tool. The administrator can clear them;
 * afterwards the host rebinds and the adapter reloads.
 *
 * <p>Data minimization: caches hold only the fields that are needed, for
 * example ID and display name of tool users.
 *
 * <p>Names of collections and memory caches match {@code [a-z0-9_]{1,40}}; any
 * other name is a programming error ({@link IllegalArgumentException}).
 */
public interface AdapterCaches {

    /**
     * A collection of documents, durable until cleared. At the platform limit
     * it stops storing without throwing; the host notes this on the connection.
     */
    DocumentCollection collection(String name);

    /**
     * An in-memory cache with at most {@code maxEntries} entries; the host
     * reduces this to the platform limit. The same name returns the same cache
     * within the same binding. The host does not check the value type: using
     * the same name with a different {@code V} leads to a
     * {@link ClassCastException} on read.
     *
     * @param maxEntries greater than 0
     */
    <V> MemoryCache<V> memory(String name, int maxEntries);
}
