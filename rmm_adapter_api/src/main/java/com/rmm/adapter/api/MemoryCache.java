package com.rmm.adapter.api;

import java.util.Optional;

/**
 * An in-memory cache with LRU eviction, obtained from
 * {@link AdapterCaches#memory(String, int)}. It belongs to the binding and is
 * empty after a rebind. Safe for concurrent calls.
 *
 * @param <V> type of the values; the keys are strings
 */
public interface MemoryCache<V> {

    Optional<V> get(String key);

    /** Stores the value; if the cache is full, the least recently used entry is evicted. */
    void put(String key, V value);

    void remove(String key);

    void clear();

    /** Number of entries, at most the limit of the cache. */
    int size();
}
