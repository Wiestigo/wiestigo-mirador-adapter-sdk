package com.rmm.adapter.api;

/**
 * Failure of an adapter call. Deliberately a checked exception: every hook call
 * against an external tool can fail, and the sync core handles that in a
 * defined way (the element gets no receipt and is retried in the next run). The
 * compiler makes adapter authors decide instead of letting errors slip through.
 *
 * <p>The message should be understandable for administrators; it appears in the
 * run result and in the administration.
 */
public class AdapterException extends Exception {

    /** No HTTP status known. */
    private static final int NO_STATUS = -1;

    private final boolean retryable;
    private final int httpStatus;

    public AdapterException(String message) {
        this(message, null, false);
    }

    public AdapterException(String message, Throwable cause) {
        this(message, cause, false);
    }

    /**
     * @param retryable true for a transient error of the tool (throttling 429,
     *                  gateway or availability errors 502 to 504, timeout): the
     *                  host repeats the call up to
     *                  {@link RateLimit#maxRetries()} times with growing delay
     *                  before reporting it as failed, but only for idempotent
     *                  hooks (read, update, delete). A create is never
     *                  repeated, because after a timeout the tool may already
     *                  have executed it. Everything else (validation,
     *                  permissions, not found) is final and reported at once.
     */
    public AdapterException(String message, Throwable cause, boolean retryable) {
        this(message, cause, retryable, NO_STATUS);
    }

    /**
     * Like the three-argument constructor, plus the HTTP status of the response
     * that caused the error: the tool's or, for a remote adapter, the remote
     * service's. The host classifies by this status (for example in the
     * reachability check), never by the message text, which may contain the
     * server's body and arbitrary numbers in it. When the host classifies the
     * state of the connection, a status takes precedence over
     * {@code retryable}: 429, 502, 503 and 504 are transient, every other
     * status is permanent. Whether a run repeats the call is decided by
     * {@code retryable} alone (see the three-argument constructor); set both
     * consistently.
     *
     * @param httpStatus the status (100 to 599); any other value means: none known
     */
    public AdapterException(String message, Throwable cause, boolean retryable, int httpStatus) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus >= 100 && httpStatus <= 599 ? httpStatus : NO_STATUS;
    }

    /** Whether the call may be repeated after a short pause; see the three-argument constructor. */
    public boolean isRetryable() {
        return retryable;
    }

    /** The HTTP status of the response (tool or remote service); empty if none is known (for example unreachable). */
    public java.util.OptionalInt httpStatus() {
        return httpStatus == NO_STATUS ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(httpStatus);
    }
}
