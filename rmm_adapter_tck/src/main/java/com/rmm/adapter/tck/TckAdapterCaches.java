package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterCaches;
import com.rmm.adapter.api.DocumentCollection;
import com.rmm.adapter.api.MemoryCache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * {@code caches()} of a connection: document collections that last until they are cleared, and the memory caches
 * of the current binding, which evict the entry that was used least recently.
 */
final class TckAdapterCaches implements AdapterCaches {

    private final String bindingId;
    private final TckDocuments documents;
    private final Supplier<Map<String, MemoryCache<?>>> memory;
    private final Map<String, DocumentCollection> collections = new ConcurrentHashMap<>();

    /**
     * @param documents the documents of the connection, which count the collections of all its stores
     * @param memory    the memory caches of the current binding by name; they start empty for each binding
     */
    TckAdapterCaches(String bindingId, TckDocuments documents, Supplier<Map<String, MemoryCache<?>>> memory) {
        this.bindingId = bindingId;
        this.documents = documents;
        this.memory = memory;
    }

    @Override
    public DocumentCollection collection(String name) {
        return collections.computeIfAbsent(TckDocuments.requireName(name),
                ignored -> documents.collection(TckDocuments.Kind.CACHE, name, () -> {
                }, null));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> MemoryCache<V> memory(String name, int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        return (MemoryCache<V>) memory.get().computeIfAbsent(TckDocuments.requireName(name),
                ignored -> new LruCache<>(Math.min(maxEntries, TckAdapterContext.MEMORY_CACHE_LIMIT)));
    }

    /** Empties every collection, as the administrator can. */
    void dropCollections() {
        collections.values().forEach(TckDocuments::drop);
    }

    /** How many documents the collections hold. */
    long documents() {
        return collections.values().stream().mapToLong(TckDocuments::size).sum();
    }

    @Override
    public String toString() {
        return "AdapterCaches[connection " + bindingId + "]";
    }

    /** A cache with LRU eviction. */
    private static final class LruCache<V> implements MemoryCache<V> {

        private final int maxEntries;
        private final LinkedHashMap<String, V> entries;

        private LruCache(int maxEntries) {
            this.maxEntries = maxEntries;
            this.entries = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                    return size() > LruCache.this.maxEntries;
                }
            };
        }

        @Override
        public synchronized Optional<V> get(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        @Override
        public synchronized void put(String key, V value) {
            entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        }

        @Override
        public synchronized void remove(String key) {
            entries.remove(key);
        }

        @Override
        public synchronized void clear() {
            entries.clear();
        }

        @Override
        public synchronized int size() {
            return entries.size();
        }

        @Override
        public String toString() {
            return "MemoryCache[" + maxEntries + "]";
        }
    }
}
