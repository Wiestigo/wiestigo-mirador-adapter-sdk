package com.rmm.adapter.api;

/**
 * Rate limit of a tool (for example: the tool allows 50 requests per second,
 * the adapter declares 40). The host sets up the shared rate limit and the
 * retries of HTTP calls from it.
 *
 * @param maxRequestsPerSecond upper limit of calls per second (> 0).
 * @param maxRetries           retries: on the host's HTTP path
 *                             ({@link AdapterHttp}) after 429, 503 and
 *                             transient transport errors, only for repeatable
 *                             requests and at most three; without the HTTP
 *                             path, the retries of hook calls after transient
 *                             errors; 0 = no retry.
 */
public record RateLimit(int maxRequestsPerSecond, int maxRetries) {

    public RateLimit {
        if (maxRequestsPerSecond <= 0) {
            throw new IllegalArgumentException("maxRequestsPerSecond must be > 0");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
    }
}
