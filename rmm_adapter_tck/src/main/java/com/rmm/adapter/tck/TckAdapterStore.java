package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterStore;
import com.rmm.adapter.api.DocumentCollection;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** {@code store()} of a connection: keys and values in memory, plus named collections of the connection. */
final class TckAdapterStore implements AdapterStore {

    private final String bindingId;
    private final TckDocuments documents;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<String, DocumentCollection> collections = new ConcurrentHashMap<>();

    /** @param documents the documents of the connection, which count the collections of all its stores */
    TckAdapterStore(String bindingId, TckDocuments documents) {
        this.bindingId = bindingId;
        this.documents = documents;
    }

    @Override
    public Optional<String> read(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public void write(String key, String value) {
        values.put(key, value);
    }

    @Override
    public void delete(String key) {
        values.remove(key);
    }

    @Override
    public DocumentCollection collection(String name) {
        return collections.computeIfAbsent(TckDocuments.requireName(name),
                ignored -> documents.collection(TckDocuments.Kind.STORE, name, () -> {
                }, null));
    }

    /** The keys and values at this moment. */
    Map<String, String> entries() {
        return Map.copyOf(values);
    }

    @Override
    public String toString() {
        return "AdapterStore[connection " + bindingId + "]";
    }
}
