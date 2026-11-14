package com.rmm.adapter.api;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Concurrency and scheduled tasks of a binding. An adapter starts its own
 * threads only through this executor.
 *
 * <ul>
 *   <li><b>{@link #submit}</b>: side tasks of a run (fetching comments or
 *       attachments in parallel). They work under the work permit of their
 *       run, at most {@link #parallelism()} at a time per binding, and inherit
 *       tenant, run and logging context from the caller. A stop, the deadline
 *       and revocation of the binding interrupt them. Wait for a task only
 *       through the returned {@link Future}: then the host knows that the run
 *       is waiting.</li>
 *   <li><b>{@link #schedule}</b>: recurring tasks of the binding (such as a
 *       daily reload of the metadata). The host spreads the start times,
 *       acquires a separate work permit on behalf of the tenant for each
 *       execution and sets a platform deadline ({@link TaskContext}). If no
 *       permit is available before the next scheduled time, the execution is
 *       skipped.</li>
 * </ul>
 *
 * <p>The tasks of a binding end with the binding.
 */
public interface AdapterExecutor {

    /** A recurring task; the host logs errors and keeps the next scheduled time. */
    @FunctionalInterface
    interface ScheduledTask {
        void run(TaskContext context) throws Exception;
    }

    /**
     * What one execution of a scheduled task knows about itself. The host
     * enforces the deadline (by interrupt); the task checks
     * {@link #stopRequested()} between its steps and then stops.
     */
    interface TaskContext {

        /** The name passed to {@link AdapterExecutor#schedule}. */
        String name();

        /** The deadline of this execution (a platform limit), never {@code null}. */
        Instant deadline();

        /**
         * Whether the execution should stop: deadline passed, task cancelled,
         * binding ended or revoked, tenant paused, or host instance shutting
         * down.
         */
        boolean stopRequested();
    }

    /** A scheduled task; {@link #close()} cancels it. */
    interface Scheduled extends AutoCloseable {

        String name();

        /**
         * Cancels the task: no further execution is scheduled; a running
         * execution sees {@link TaskContext#stopRequested()} and ends on its
         * own. Afterwards the name is free again. May be called repeatedly.
         */
        @Override
        void close();
    }

    /**
     * Runs the task concurrently within the caller's run.
     *
     * <p>Allowed within a run (a hook call or another task from {@code submit})
     * and within an execution of a scheduled task, where it works under that
     * execution's permit and deadline. Anywhere else, for example from
     * {@code bind()} or a thread of the adapter's own, it throws
     * {@link IllegalStateException}.
     */
    <T> Future<T> submit(Callable<T> task);

    /** How many tasks of this binding may work at the same time. */
    int parallelism();

    /**
     * Schedules a task every {@code interval}. The name is unique per binding
     * ({@code [a-z0-9_]{1,40}}): if it is already scheduled and not cancelled,
     * the call throws {@link IllegalStateException}; an invalid name throws
     * {@link IllegalArgumentException}.
     *
     * @param interval greater than 0; the host may raise it to its minimum
     * @return the registration; closing it cancels the task
     */
    Scheduled schedule(String name, Duration interval, ScheduledTask task);
}
