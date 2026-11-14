package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterRunStore;
import com.rmm.adapter.api.DocumentCollection;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The run store of one run: named collections that exist only while the run is active. */
final class TckAdapterRunStore implements AdapterRunStore {

    private final UUID runId;
    private final TckDocuments.Guard active;
    private final TckDocuments documents;
    private final Map<String, DocumentCollection> collections = new ConcurrentHashMap<>();

    /**
     * @param active    throws if the run has ended; checked before every access
     * @param documents the documents of the connection, which count the collections of all its stores
     */
    TckAdapterRunStore(UUID runId, TckDocuments.Guard active, TckDocuments documents) {
        this.runId = runId;
        this.active = active;
        this.documents = documents;
    }

    @Override
    public UUID runId() {
        return runId;
    }

    @Override
    public DocumentCollection collection(String name) {
        active.check();
        return collections.computeIfAbsent(TckDocuments.requireName(name),
                ignored -> documents.collection(TckDocuments.Kind.RUN, name, active, runId));
    }

    /** Empties the store, as the host does at the end of the run. */
    void drop() {
        collections.values().forEach(TckDocuments::drop);
        collections.clear();
    }

    /** How many documents the store holds. */
    int documents() {
        return collections.values().stream().mapToInt(TckDocuments::size).sum();
    }

    @Override
    public String toString() {
        return "AdapterRunStore[" + runId + "]";
    }
}
