package com.rmm.adapter.api;

import java.util.Optional;

/**
 * Persistent key-value store of an adapter, for cursors, markers and other
 * state that must survive a restart. The host keeps the data in the adapter's
 * own namespace; adapters of different tools never see each other's data, and
 * the core data is unreachable.
 *
 * <p>Values are strings; the adapter serializes structured data itself (for
 * example as JSON). {@link #collection(String)} provides named collections of
 * documents that can be queried by declared fields.
 */
public interface AdapterStore {

    Optional<String> read(String key);

    void write(String key, String value);

    void delete(String key);

    /**
     * A durable collection of documents in the namespace of the connection;
     * clearing the caches does not clear it. At the platform limit it throws
     * {@link AdapterStoreLimitException}. The name matches
     * {@code [a-z0-9_]{1,40}}.
     *
     * @throws UnsupportedOperationException if this store does not provide collections
     */
    default DocumentCollection collection(String name) {
        throw new UnsupportedOperationException("AdapterStore.collection() is not provided by this host");
    }
}
