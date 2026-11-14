package com.rmm.adapter.api;

/**
 * A store of the adapter has reached a platform limit. {@code store()} and
 * {@code runStore()} throw it; the run ends {@code FAILED} with "adapter store
 * limit reached". {@code caches()} never throws it.
 *
 * <p>Unchecked like the other store errors: the adapter should not catch it
 * but let it pass; the host ends the run even if the adapter does catch it.
 */
public class AdapterStoreLimitException extends RuntimeException {

    /** Which limit was reached. */
    public enum Limit {
        /** Size of one document. */
        DOCUMENT_SIZE,
        /** Number of documents of the connection across all stores. */
        CONNECTION_DOCUMENTS,
        /** Number of documents of the tenant. */
        TENANT_DOCUMENTS
    }

    /** Start of every message; this is how the reason appears on the run. */
    public static final String MESSAGE = "adapter store limit reached";

    private final Limit limit;

    /**
     * @param detail addition to the message (such as the limit); must not
     *               contain data of the document
     */
    public AdapterStoreLimitException(Limit limit, String detail) {
        super(detail == null || detail.isBlank() ? MESSAGE : MESSAGE + " (" + detail + ")");
        if (limit == null) {
            throw new IllegalArgumentException("limit must not be null");
        }
        this.limit = limit;
    }

    public Limit limit() {
        return limit;
    }
}
