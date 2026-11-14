package com.rmm.adapter.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Run context the sync core passes to the adapter hooks. Deliberately narrow
 * and serializable: the adapter needs the identifier of the run (for logs and
 * intermediate state), the project of the tool it works in, and the deadline of
 * the run.
 *
 * @param runId             identifier of the synchronization run.
 * @param externalProjectId ID of the project in the external tool: the external
 *                          reference of the sync point artifact.
 * @param deadline          deadline of the run as an instant; {@code null} = no
 *                          deadline. It counts only active time, that is time
 *                          holding a work permit and not waiting at the rate
 *                          limit; waiting for a response of the tool counts.
 *                          The host shifts it by pauses and rate limit waits
 *                          and passes a new {@code SyncRunContext} per page or
 *                          call (instant = now + remainder). The adapter
 *                          compares it with the clock
 *                          ({@link #expired(Instant)}) and stops working once
 *                          it has passed; the host enforces it in any case.
 */
public record SyncRunContext(UUID runId, String externalProjectId, Instant deadline) {

    public SyncRunContext {
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }
        if (externalProjectId == null || externalProjectId.isBlank()) {
            throw new IllegalArgumentException("externalProjectId must not be empty");
        }
    }

    /** A run without a deadline. */
    public SyncRunContext(UUID runId, String externalProjectId) {
        this(runId, externalProjectId, null);
    }

    /** Whether the deadline has passed at {@code now}; never without a deadline. */
    public boolean expired(Instant now) {
        return deadline != null && !now.isBefore(deadline);
    }
}
