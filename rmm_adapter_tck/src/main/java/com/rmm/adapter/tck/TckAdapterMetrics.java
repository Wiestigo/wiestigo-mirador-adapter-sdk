package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterMetrics;

import java.util.concurrent.atomic.AtomicLong;

/** {@code metrics()} of a connection: counters that the tests read back. */
final class TckAdapterMetrics implements AdapterMetrics {

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong throttled = new AtomicLong();

    @Override
    public void recordToolRequest() {
        requests.incrementAndGet();
    }

    @Override
    public void recordToolRetry() {
        retries.incrementAndGet();
    }

    @Override
    public void recordThrottleWait(long milliseconds) {
        throttled.addAndGet(milliseconds);
    }

    /** Counted tool calls. */
    long requests() {
        return requests.get();
    }

    /** Counted retries of tool calls. */
    long retries() {
        return retries.get();
    }

    /** Sum of the reported throttle wait time in milliseconds. */
    long throttleWaitMillis() {
        return throttled.get();
    }
}
