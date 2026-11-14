package com.rmm.adapter.api;

/**
 * The environment the host hands to the adapter at bind time, and the adapter's
 * only way to reach host services. The adapter obtains nothing on its own (no
 * Spring, no database access, no host internals); this keeps adapter code free
 * of infrastructure knowledge and makes the plugin boundary enforceable.
 *
 * <p>A context belongs to exactly one binding ({@link #binding()}); nothing in
 * it is process-wide.
 *
 * <p>{@link #http()} is the only permitted network path (rate limit, retries,
 * deadlines, address protection). {@link #http()}, {@link #caches()},
 * {@link #runStore()} and {@link #executor()} are default methods that throw
 * {@link UnsupportedOperationException} in a context that does not provide
 * them; the host provides all of them.
 */
public interface AdapterContext {

    /** The binding this context belongs to: only its opaque identifier, without tenant or operating mode. */
    AdapterBinding binding();

    /** The declared configuration keys, resolved and typed. */
    AdapterConfig config();

    /** Writes to the host log, tagged with the adapter's system key; the host decides on format and sink. */
    AdapterLogger logger();

    /**
     * Durable synchronization state of the adapter (markers, the cursor of a
     * delta feed) in its own namespace, never in the core data. Clearing the
     * caches does not clear it. What can be reloaded from the tool belongs in
     * {@link #caches()}, intermediate state of a run in {@link #runStore()}.
     */
    AdapterStore store();

    /** Counters for operational metrics of tool access. */
    AdapterMetrics metrics();

    /**
     * The HTTP path of this binding. All network traffic goes through it; the
     * host handles address protection, TLS, rate limit, retries and deadlines.
     * The same context always returns the same object.
     *
     * @throws UnsupportedOperationException if this context does not provide an HTTP path
     */
    default AdapterHttp http() {
        throw unsupported("http()");
    }

    /**
     * Caches of this binding, in memory and as documents; the administrator can
     * clear them.
     *
     * @throws UnsupportedOperationException if this context does not provide them
     */
    default AdapterCaches caches() {
        throw unsupported("caches()");
    }

    /**
     * The run store of the run the calling thread is bound to (a hook call or a
     * task from {@link AdapterExecutor#submit}). Outside a run it throws
     * {@link IllegalStateException}.
     *
     * @throws UnsupportedOperationException if this context does not provide it
     */
    default AdapterRunStore runStore() {
        throw unsupported("runStore()");
    }

    /**
     * The run store of an explicitly named run, for threads the host does not
     * associate with a run. The host uses only {@link SyncRunContext#runId()}
     * and looks it up among the <b>active</b> runs of this binding; a run of
     * another binding, a finished run or an invented one throws
     * {@link IllegalStateException}. Every later access through the returned
     * store fails the same way once the run ends, so nothing lands in it after
     * the cleanup at the end of the run.
     *
     * @throws UnsupportedOperationException if this context does not provide it
     */
    default AdapterRunStore runStore(SyncRunContext run) {
        throw unsupported("runStore(SyncRunContext)");
    }

    /**
     * Concurrency and scheduled tasks of this binding. An adapter starts its
     * own threads only through this executor.
     *
     * @throws UnsupportedOperationException if this context does not provide it
     */
    default AdapterExecutor executor() {
        throw unsupported("executor()");
    }

    private static UnsupportedOperationException unsupported(String method) {
        return new UnsupportedOperationException("AdapterContext." + method + " is not provided by this host");
    }
}
