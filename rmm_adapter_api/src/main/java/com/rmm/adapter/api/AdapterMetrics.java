package com.rmm.adapter.api;

/**
 * Counters for operational metrics of tool access. An adapter that sends
 * through the host's HTTP path ({@link AdapterContext#http()}) does not have to
 * call anything here, because the host counts those requests itself; code that
 * reaches the tool by other means reports through this facade.
 */
public interface AdapterMetrics {

    /** One call to the tool (every attempt counts). */
    void recordToolRequest();

    /**
     * One call to the tool with its duration: the variant a report needs to
     * separate tool time from database time. Callers that only count use the
     * variant without duration; the default delegates to it.
     */
    default void recordToolRequest(long durationMillis) {
        recordToolRequest();
    }

    /** One retry after a retryable error (429/5xx). */
    void recordToolRetry();

    /** Time waited before a call because of the rate limit. */
    void recordThrottleWait(long milliseconds);
}
