package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterStoreLimitException;
import com.rmm.adapter.api.DocumentCollection;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.RawBsonDocument;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The in-memory document stores of a connection in the TCK: the collections
 * of {@code store()}, {@code caches()} and {@code runStore()}. They follow
 * the rules of {@link DocumentCollection}, so that an adapter gets the same
 * answers as in the host:
 * <ul>
 *   <li>Typed equality in {@code find}: a text matches only a text, a number
 *       every number of the same value ({@code 5} and {@code 5.0}) but never
 *       the text {@code "5"}; {@code null}, lists and objects never match,
 *       not even a list that contains the value.</li>
 *   <li>{@code all(afterId, limit)} pages by id in UTF-8 byte order (the
 *       order of Unicode code points, not of {@link String#compareTo}), at
 *       most {@link DocumentCollection#MAX_PAGE} per page.</li>
 *   <li>Limits: {@value #MAX_DOCUMENT_BYTES} bytes per document, measured in
 *       BSON as MongoDB does, including {@code _id} and, in the run store,
 *       {@code runId} (a list costs a type byte and an index name per
 *       element, far more than in JSON);
 *       {@value #MAX_CONNECTION_DOCUMENTS} documents per connection across
 *       all three stores; {@value #MAX_COLLECTIONS} collections per store
 *       that hold or have held a document (a cleared cache frees its slot);
 *       {@value #MAX_INDEXED_FIELDS} declared fields per collection. At a
 *       limit, {@code store()} and {@code runStore()} throw
 *       {@link AdapterStoreLimitException}; {@code caches()} only stops
 *       saving and notes it ({@link #cacheHints()}).</li>
 *   <li>Reserved names: {@code _id} at the top level and, in the run store,
 *       {@code runId} (in a document and in {@code index}). A number outside
 *       the double range ({@code 1e400}) is rejected; BSON would store it as
 *       infinity.</li>
 *   <li>A document is returned as MongoDB returns it: rewritten from BSON
 *       with numbers by their type ({@code 1e2} comes back as
 *       {@code 100.0}), not as the text that was written. Parse it as JSON
 *       before comparing or hashing.</li>
 * </ul>
 * <p>The size counts the adapter's id, not the run prefix the host adds to
 * ids in the run store. Differences from the host: the TCK has no limit per
 * tenant (5 million documents in the host), because it has only one
 * connection, and it rejects an integer beyond 64 bits, as the BSON reader
 * does.</p>
 */
final class TckDocuments {

    /** Maximum size of a document in bytes (4 MiB). */
    static final int MAX_DOCUMENT_BYTES = 4 * 1024 * 1024;
    /** Maximum number of documents of a connection across all three stores. */
    static final long MAX_CONNECTION_DOCUMENTS = 1_000_000L;
    /** Maximum number of collections per store of a connection, as in the host. */
    static final int MAX_COLLECTIONS = 64;
    /** Maximum number of declared fields per collection, as in the host. */
    static final int MAX_INDEXED_FIELDS = 16;
    /** The field that holds the run in a document of the run store; reserved at the top level there. */
    static final String RUN_FIELD = "runId";

    private static final Pattern COLLECTION_NAME = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    /** The form in which the driver writes a document as JSON ({@code Document.toJson()}). */
    private static final JsonWriterSettings AS_MONGO_WRITES = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED).build();
    private static final BsonDocumentCodec CODEC = new BsonDocumentCodec();

    /** Order of the UTF-8 bytes: code point by code point (UTF-8 preserves their order). */
    static final Comparator<String> UTF8_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int left = a.codePointAt(i);
            int right = b.codePointAt(j);
            if (left != right) {
                return Integer.compare(left, right);
            }
            i += Character.charCount(left);
            j += Character.charCount(right);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    /** Which store; it determines what happens at a limit. */
    enum Kind {
        STORE, CACHE, RUN
    }

    /** Checks before every access that the collection is still valid (its run has not ended). */
    @FunctionalInterface
    interface Guard {
        void check();
    }

    private final AtomicLong documents = new AtomicLong();
    private final List<String> cacheHints = new CopyOnWriteArrayList<>();
    /** Per store, the names of the collections that hold or have held a document. */
    private final Map<Kind, Set<String>> names = new ConcurrentHashMap<>();

    /** Validates the name of a collection ({@code [a-z0-9_]{1,40}}). */
    static String requireName(String name) {
        if (name == null || !COLLECTION_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("collection name <" + name + "> must match [a-z0-9_]{1,40}");
        }
        return name;
    }

    /** A new, empty collection of this connection; {@code runId} only in the run store. */
    DocumentCollection collection(Kind kind, String name, Guard guard, java.util.UUID runId) {
        return new Collection(kind, requireName(name), guard, runId == null ? null : runId.toString());
    }

    /**
     * The document as MongoDB would store it ({@code _id}, in the run store
     * {@code runId}, then the fields) and its size in BSON.
     *
     * @throws IllegalArgumentException if BSON cannot hold the content (for
     *                                  example an integer beyond 64 bits)
     */
    static Bson bson(String id, String runId, String json) {
        BsonDocument fields;
        try {
            fields = BsonDocument.parse(json);
        } catch (RuntimeException unusable) {
            //Without the reader's message: it quotes the content
            throw new IllegalArgumentException("document cannot be stored as BSON ("
                    + unusable.getClass().getSimpleName() + ") — for example an integer beyond 64 bits");
        }
        requireFinite(fields);
        BsonDocument stored = new BsonDocument("_id", new BsonString(id));
        if (runId != null) {
            stored.append(RUN_FIELD, new BsonString(runId));
        }
        fields.forEach(stored::append);
        int bytes = new RawBsonDocument(stored, CODEC).getByteBuffer().remaining();
        return new Bson(fields.toJson(AS_MONGO_WRITES), bytes);
    }

    /** A number outside the double range would come back from BSON as infinity; rejected as in the host. */
    private static void requireFinite(BsonValue value) {
        if (value.isDouble() && !Double.isFinite(value.asDouble().getValue())) {
            throw new IllegalArgumentException("document holds a number out of range");
        }
        if (value instanceof BsonDocument document) {
            document.values().forEach(TckDocuments::requireFinite);
        } else if (value instanceof BsonArray array) {
            array.forEach(TckDocuments::requireFinite);
        }
    }

    /** Whether the store has room for the collection: it is already counted, or the store is below its limit. */
    private boolean admitted(Kind kind, String name) {
        Set<String> known = names.computeIfAbsent(kind, ignored -> ConcurrentHashMap.newKeySet());
        synchronized (known) {
            if (known.contains(name)) {
                return true;
            }
            if (known.size() >= MAX_COLLECTIONS) {
                return false;
            }
            known.add(name);
            return true;
        }
    }

    /** A document in MongoDB's form: the text as the driver reads it, and the size in BSON. */
    record Bson(String json, int bytes) {
        @Override
        public String toString() {
            return "Bson[" + bytes + " bytes]";
        }
    }

    /** Each time {@code caches()} did not save a document at a limit; the hints shown on the connection. */
    List<String> cacheHints() {
        return List.copyOf(cacheHints);
    }

    /** Number of documents of the connection across all stores. */
    long documents() {
        return documents.get();
    }

    /** A collection; all methods run under one lock and are safe for concurrent calls. */
    private final class Collection implements DocumentCollection {

        private final Kind kind;
        private final String name;
        private final Guard guard;
        private final String runId;
        private final TreeMap<String, Stored> byId = new TreeMap<>(UTF8_ORDER);
        private final Set<String> indexed = ConcurrentHashMap.newKeySet();

        private Collection(Kind kind, String name, Guard guard, String runId) {
            this.kind = kind;
            this.name = name;
            this.guard = guard;
            this.runId = runId;
        }

        @Override
        public void index(String field) {
            guard.check();
            if (field == null || !FIELD_NAME.matcher(field).matches()) {
                throw new IllegalArgumentException("index field <" + field
                        + "> must match [A-Za-z][A-Za-z0-9_]{0,63}");
            }
            if (runId != null && field.equals(RUN_FIELD)) {
                throw new IllegalArgumentException("index field <" + field + "> is reserved in the run store - the"
                        + " host keeps the run there");
            }
            synchronized (indexed) {
                if (!indexed.contains(field) && indexed.size() >= MAX_INDEXED_FIELDS) {
                    throw new IllegalArgumentException("collection <" + name + "> has " + MAX_INDEXED_FIELDS
                            + " indexed fields already - at most " + MAX_INDEXED_FIELDS + " per collection");
                }
                indexed.add(field);
            }
        }

        @Override
        public synchronized void put(String id, String json) {
            guard.check();
            requireId(id);
            Map<String, Object> fields = TckJson.object(json);
            if (runId != null && fields.containsKey(RUN_FIELD)) {
                throw new IllegalArgumentException("field name <" + RUN_FIELD + "> is reserved at the top level - the"
                        + " host keeps the run there");
            }
            Bson stored = bson(id, runId, json);
            if (stored.bytes() > MAX_DOCUMENT_BYTES) {
                refuse(AdapterStoreLimitException.Limit.DOCUMENT_SIZE, "document of " + stored.bytes()
                        + " bytes in BSON, at most " + MAX_DOCUMENT_BYTES);
                return;
            }
            boolean added = !byId.containsKey(id);
            if (added && documents.get() >= MAX_CONNECTION_DOCUMENTS) {
                refuse(AdapterStoreLimitException.Limit.CONNECTION_DOCUMENTS, "at most " + MAX_CONNECTION_DOCUMENTS
                        + " documents per connection");
                return;
            }
            if (added && !admitted(kind, name)) {
                //AdapterStoreLimitException.Limit has no kind for the number of collections; the detail names it
                refuse(AdapterStoreLimitException.Limit.CONNECTION_DOCUMENTS, "at most " + MAX_COLLECTIONS
                        + " collections in the " + kind.name().toLowerCase(java.util.Locale.ROOT)
                        + " of a connection");
                return;
            }
            byId.put(id, new Stored(stored.json(), fields));
            if (added) {
                documents.incrementAndGet();
            }
        }

        /** At a limit: the store and the run store throw, a cache only stops saving. */
        private void refuse(AdapterStoreLimitException.Limit limit, String detail) {
            if (kind == Kind.CACHE) {
                cacheHints.add("cache <" + name + "> did not save a document: " + detail);
                return;
            }
            throw new AdapterStoreLimitException(limit, detail);
        }

        @Override
        public synchronized Optional<String> get(String id) {
            guard.check();
            requireId(id);
            Stored stored = byId.get(id);
            return stored == null ? Optional.empty() : Optional.of(stored.json());
        }

        @Override
        public Stream<Document> find(String field, String value) {
            if (value == null) {
                throw new IllegalArgumentException("find needs a value — null never matches");
            }
            return matching(field, stored -> value.equals(stored));
        }

        @Override
        public Stream<Document> find(String field, long value) {
            BigDecimal wanted = BigDecimal.valueOf(value);
            return matching(field, stored -> stored instanceof BigDecimal number && number.compareTo(wanted) == 0);
        }

        @Override
        public Stream<Document> find(String field, boolean value) {
            return matching(field, stored -> stored instanceof Boolean flag && flag == value);
        }

        private synchronized Stream<Document> matching(String field, java.util.function.Predicate<Object> matches) {
            guard.check();
            if (field == null || !indexed.contains(field)) {
                throw new IllegalArgumentException("field <" + field + "> is not declared with index(...) in"
                        + " collection <" + name + ">");
            }
            List<Document> found = new ArrayList<>();
            byId.forEach((id, stored) -> {
                Object value = stored.fields().get(field);
                //Only text, number and boolean; null, list and object never match
                if (value != null && value != TckJson.NULL && !(value instanceof Map) && !(value instanceof List)
                        && matches.test(value)) {
                    found.add(new Document(id, stored.json()));
                }
            });
            return found.stream();
        }

        @Override
        public synchronized Stream<Document> all(String afterId, int limit) {
            guard.check();
            if (limit < 1 || limit > MAX_PAGE) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE + ": " + limit);
            }
            Map<String, Stored> tail = afterId == null ? byId : byId.tailMap(afterId, false);
            List<Document> page = new ArrayList<>();
            for (Map.Entry<String, Stored> entry : tail.entrySet()) {
                if (page.size() == limit) {
                    break;
                }
                page.add(new Document(entry.getKey(), entry.getValue().json()));
            }
            return page.stream();
        }

        @Override
        public synchronized boolean delete(String id) {
            guard.check();
            requireId(id);
            boolean existed = byId.remove(id) != null;
            if (existed) {
                documents.decrementAndGet();
            }
            return existed;
        }

        @Override
        public synchronized void clear() {
            guard.check();
            documents.addAndGet(-byId.size());
            byId.clear();
        }

        /**
         * Empties the collection without the guard check; the TCK's cleanup
         * at the end of a run and when the caches are cleared. A cleared
         * cache frees its name (the host removes the collection); the
         * collection of a run store remains, as in the host.
         */
        synchronized int drop() {
            int size = byId.size();
            documents.addAndGet(-size);
            byId.clear();
            if (kind == Kind.CACHE) {
                Set<String> known = names.get(kind);
                if (known != null) {
                    known.remove(name);
                }
            }
            return size;
        }

        synchronized int size() {
            return byId.size();
        }

        @Override
        public String toString() {
            return "DocumentCollection[" + kind.name().toLowerCase(java.util.Locale.ROOT) + " " + name + "]";
        }
    }

    /** Empties a collection of these stores without the guard check; returns how many documents it held. */
    static int drop(DocumentCollection collection) {
        return ((Collection) collection).drop();
    }

    /** Number of documents in a collection of these stores, without the guard check. */
    static int size(DocumentCollection collection) {
        return ((Collection) collection).size();
    }

    private static void requireId(String id) {
        if (id == null || id.isEmpty() || id.length() > DocumentCollection.MAX_ID_LENGTH) {
            throw new IllegalArgumentException("document id must have 1 to " + DocumentCollection.MAX_ID_LENGTH
                    + " characters");
        }
    }

    /** A document: the text as MongoDB would return it, and its top-level fields for {@code find}. */
    private record Stored(String json, Map<String, Object> fields) {
        @Override
        public String toString() {
            return "Stored[" + json.length() + " chars]";
        }
    }
}
