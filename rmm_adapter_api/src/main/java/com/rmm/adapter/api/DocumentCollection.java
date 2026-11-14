package com.rmm.adapter.api;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * A named collection of JSON documents in one of the adapter's stores:
 * {@link AdapterStore#collection}, {@link AdapterCaches#collection} or
 * {@link AdapterRunStore#collection}. The host keeps it in the namespace of the
 * connection; collections of other connections and the core data are
 * unreachable.
 *
 * <ul>
 *   <li><b>ID:</b> not empty, at most {@link #MAX_ID_LENGTH} characters.</li>
 *   <li><b>Document:</b> a JSON object; {@link #put} rejects anything else
 *       (array, string, number, invalid JSON) with
 *       {@link IllegalArgumentException}. A document read back is equal in
 *       content, not character by character (whitespace, number format and
 *       field order may differ).</li>
 *   <li><b>Reserved names:</b> {@code _id} at the top level (where the host
 *       stores the ID) and, at any depth, names that start with {@code $} or
 *       contain a dot; {@link #put} rejects them.</li>
 *   <li><b>Query</b> ({@link #find}): only by top-level fields declared with
 *       {@link #index(String)}; an undeclared field is a programming error
 *       ({@link IllegalArgumentException}). Equality is defined for three
 *       kinds of values only, each matching only its own kind:
 *       <ul>
 *         <li>string: a JSON string with exactly the same characters (case
 *             sensitive);</li>
 *         <li>number: a JSON number with the same numeric value; {@code 5}
 *             matches {@code 5} and {@code 5.0}, never the string
 *             {@code "5"};</li>
 *         <li>boolean: {@code true} or {@code false}.</li>
 *       </ul>
 *       A missing field, {@code null}, a JSON array or an object never
 *       matches, not even if the array contains the value.</li>
 *   <li><b>Read all</b> ({@link #all}): page by page in ascending order of the
 *       ID (compared byte by byte in UTF-8), at most {@link #MAX_PAGE} per
 *       call; the next page starts after the last ID returned.</li>
 *   <li><b>Limits:</b> the size per document and the number of documents per
 *       connection and tenant are platform limits. At the limit,
 *       {@code store()} and {@code runStore()} reject with
 *       {@link AdapterStoreLimitException}; {@code caches()} just stops
 *       storing and does not throw.</li>
 * </ul>
 *
 * <p>Streams hold a cursor and must be closed:
 * {@code try (Stream<Document> found = collection.find(...))}. All methods are
 * safe for concurrent calls from several threads.
 */
public interface DocumentCollection {

    /** Maximum number of documents per call of {@link #all}. */
    int MAX_PAGE = 1000;

    /** Maximum length of an ID in characters. */
    int MAX_ID_LENGTH = 512;

    /**
     * A document read from the collection, with its ID.
     *
     * @param id   the ID passed to {@link DocumentCollection#put}
     * @param json the JSON object, equal in content to the one written
     */
    record Document(String id, String json) {

        public Document {
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("document id must not be empty");
            }
            if (json == null) {
                throw new IllegalArgumentException("document json must not be null");
            }
        }

        /** Without the content: it may carry data of the tool. */
        @Override
        public String toString() {
            return "Document[" + id + ", " + json.length() + " chars]";
        }
    }

    /**
     * Declares a top-level field as queryable and creates an index if needed.
     * May be called repeatedly. The name matches
     * {@code [A-Za-z][A-Za-z0-9_]{0,63}}.
     */
    void index(String field);

    /** Creates or replaces the document; {@code json} is a JSON object. */
    void put(String id, String json);

    Optional<String> get(String id);

    /** All documents whose declared field holds this string. */
    Stream<Document> find(String field, String value);

    /** All documents whose declared field holds a number with this value. */
    Stream<Document> find(String field, long value);

    /** All documents whose declared field holds this boolean. */
    Stream<Document> find(String field, boolean value);

    /**
     * One page of all documents in ascending order of the ID.
     *
     * @param afterId the last ID of the previous page; {@code null} = from the
     *                start
     * @param limit   1 to {@link #MAX_PAGE}; fewer documents than
     *                {@code limit} means: end reached
     */
    Stream<Document> all(String afterId, int limit);

    /** @return whether the document existed */
    boolean delete(String id);

    /** Empties the collection. */
    void clear();
}
