package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.SyncRunContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * A connection as the TCK cases need it: the {@link AdapterContext} the
 * adapter gets when it is bound, what the host does around it (start and end
 * runs, release after the last lease, clear the caches), and what the cases
 * observe (log, store, counters, the stamp of the HTTP path).
 *
 * <p>The default is {@link TckAdapterContext}, entirely in memory. A host
 * supplies its own implementation through {@link ContractHost}; the same
 * cases then run against its real context, so an adapter that passes in the
 * TCK must pass there in the same way.</p>
 *
 * <p>A context stands for the connection, not for one binding: after
 * {@link #release()}, binding the same connection again gets new resources
 * (HTTP path, threads, in-memory caches), while the stores remain.</p>
 */
public interface ContractContext extends AdapterContext {

    /**
     * A run of this connection ({@link #startRun}). Closing ends it as the
     * host does: waiting tasks from {@code submit} are cancelled, running
     * tasks finish, the run store is emptied.
     */
    interface Run extends AutoCloseable {

        /** The run as the hooks receive it. */
        SyncRunContext context();

        /** Whether the run has ended. */
        boolean ended();

        /**
         * Runs {@code work} on the calling thread inside this run, the way
         * the host binds the parallel tasks of a run to it.
         *
         * @throws IllegalStateException if the run has ended
         */
        <T> T within(Callable<T> work) throws Exception;

        /** Number of documents in the run store of this run; 0 once the run has ended. */
        int documents();

        /** Ends the run. Idempotent. */
        @Override
        void close();
    }

    /**
     * Starts a run of this connection and binds the calling thread to it, as
     * the host does for a hook call. Its deadline is
     * {@link SyncRunContext#deadline()}.
     *
     * @throws IllegalStateException if this run is already active
     */
    Run startRun(SyncRunContext run);

    /** Runs {@code work} in a run of its own ({@link #startRun}) and ends that run afterwards. */
    default <T> T inRun(SyncRunContext run, Callable<T> work) throws Exception {
        try (Run ignored = startRun(run)) {
            return work.call();
        }
    }

    /**
     * The bound instance as the host's sync core calls its ports: the cases
     * call every read and write hook and the metadata through it. A host
     * wraps the ports here the way it does in production (cancellation
     * point, timed call, run registration, masking of errors), so the cases
     * take its real call path. Default: {@code instance} itself.
     */
    default AdapterInstance hosted(AdapterInstance instance) {
        return instance;
    }

    /**
     * How the host fetches the pages of a read phase ({@code ReadContract.readAll(paging(), ...)}). A host
     * reads as it does in production, for example the next page ahead on a thread of its own. Default:
     * one page after the other on the calling thread.
     */
    default ReadContract.Paging paging() {
        return ReadContract.Paging.sequential();
    }

    /**
     * Runs {@code work} on the calling thread the way the host works for this
     * connection outside a run (bind, schedule, release): a multi-tenant host
     * sets the connection's tenant for the call and restores the previous one
     * afterwards. Test code that obtained its {@link ContractHost} through
     * {@code ServiceLoader} binds this way without knowing the host. Default:
     * {@code work} unchanged, because the TCK has no tenants.
     */
    default <T> T inTenant(Callable<T> work) throws Exception {
        return work.call();
    }

    /**
     * Runs the scheduled task {@code name} of the current binding once and
     * returns when it has ended; an exception of the task is thrown here.
     * {@link TckAdapterContext} runs the task on the calling thread, a host
     * through its scheduler.
     *
     * @throws IllegalStateException         if no task of this name is scheduled
     * @throws UnsupportedOperationException if the context runs no scheduled tasks (the default)
     */
    default void runScheduled(String name) throws Exception {
        throw new UnsupportedOperationException("This context runs no scheduled tasks (" + name + "): " + this);
    }

    /**
     * What the host's own metrics have counted since this context was built:
     * process-wide, not per connection, and only inside the host's measurement
     * windows. Where the host forwards the reports of {@code metrics()} to its
     * own metrics, {@link #toolRequests()}, {@link #toolRetries()} and
     * {@link #throttleWaitMillis()} are already included: do not add them.
     * Default: empty.
     */
    default Optional<HostMetrics> hostMetrics() {
        return Optional.empty();
    }

    /**
     * A snapshot of the host's metrics ({@link #hostMetrics()}).
     *
     * @param sourceRequests          requests to the tool
     * @param sourceRetries           their retries
     * @param sourceThrottleWaitNanos time waited at the rate limit, in nanoseconds
     * @param databaseCommands        commands sent to the host's database
     */
    record HostMetrics(long sourceRequests, long sourceRetries, long sourceThrottleWaitNanos,
                       long databaseCommands) {
    }

    /**
     * Releases what the host releases after the last lease of a binding:
     * {@code executor()} first (unless {@link #releaseTasks()} already did),
     * then the HTTP path and the in-memory caches. Idempotent.
     */
    void release();

    /**
     * Releases only the tasks of the current binding ({@code executor()}: no
     * new task and no schedule, waiting tasks cancelled, running tasks get up
     * to 10 s and are then interrupted). The host does this after the last
     * lease and before {@code instance.close()}, so that the tasks can use the
     * HTTP path and the adapter's clients until then. Idempotent. Default:
     * nothing; {@link #release()} then releases them.
     */
    default void releaseTasks() {
    }

    /**
     * Closes a bound instance of this connection in the host's order:
     * {@link #releaseTasks()}, {@code instance.close()}, then
     * {@link #release()}, even if {@code close()} fails.
     */
    default void closeBinding(AdapterInstance instance) {
        try {
            releaseTasks();
            instance.close();
        } finally {
            release();
        }
    }

    /**
     * Clears the caches as an administrator does: the collections from
     * {@code caches().collection} and the in-memory caches of the current
     * binding; {@code store()} remains.
     */
    void clearCaches();

    /** Number of documents in the collections from {@code caches().collection} of this connection. */
    long cacheDocuments();

    /** Number of documents the connection holds across all three stores. */
    long connectionDocuments();

    /** Number of streams from {@code http().open(...)} of the current binding that are still open. */
    int openStreams();

    /** What the adapter wrote through {@code logger()}, in order (masked as in the host). */
    List<String> logLines();

    /** The current content of the key-value methods of {@code store()}. */
    Map<String, String> storeEntries();

    /** Tool requests counted through {@code metrics()}; 0 where the context does not count per connection. */
    long toolRequests();

    /** Retries counted through {@code metrics()}; 0 where the context does not count per connection. */
    long toolRetries();

    /** Total reported rate-limit wait in milliseconds; 0 where the context does not count per connection. */
    long throttleWaitMillis();

    /** Number of attempts sent through {@code http()}, across all bindings of this connection. */
    long httpRequests();

    /**
     * The stamp that {@code http()} of the current binding puts on every
     * request ({@link TckAdapterHttp#BINDING_HEADER}); new for each binding.
     */
    String networkStamp();

    /** The secrets from this connection's own {@code config()}. */
    Set<String> configSecrets();

    /**
     * The secrets this binding has handed out or registered: those from
     * {@code config()} and those registered with its HTTP path.
     */
    Set<String> revealedSecrets();
}
