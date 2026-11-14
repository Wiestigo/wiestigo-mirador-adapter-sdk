package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * {@code executor()} of a binding: a pool of at most {@link #parallelism()} threads
 * ({@value #THREAD_PREFIX}-...) that stay until {@link #close()}, and scheduled tasks by name. As in the host, a
 * task that waits without a time limit ({@code get()}) for a task of the same run (or the same execution) that
 * has not started yet executes it itself, so that the bounded pool never deadlocks; {@code get(t, unit)} only
 * waits, and {@code cancel(true)} on a task executed this way interrupts its code, not the waiting caller. At
 * most {@value TckAdapterContext#MAX_QUEUED_TASKS} tasks wait to start; beyond that {@code submit} throws
 * {@link RejectedExecutionException}. At most {@value TckAdapterContext#MAX_SCHEDULED} tasks are scheduled;
 * beyond that {@code schedule} throws {@link IllegalStateException} (the host's default).
 */
final class TckAdapterExecutor implements AdapterExecutor {

    /** The prefix of the thread names of {@code executor()}; case 4 counts them as threads of the binding. */
    static final String THREAD_PREFIX = "rmm-tck-executor";

    private static final Pattern TASK_NAME = Pattern.compile("[a-z0-9_]{1,40}");

    private final String bindingId;
    private final int parallelism;
    /** What the threads of the connection work for; a task runs for the run or the execution of its submitter. */
    private final TckThreadOwner owner;
    private final AtomicInteger threads = new AtomicInteger();
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final Map<String, Registered> scheduled = new ConcurrentHashMap<>();
    /** The tasks that have not finished yet. */
    private final Set<Task<?>> live = ConcurrentHashMap.newKeySet();
    /** The task this thread is currently executing, so that it can execute a nested one while waiting. */
    private final ThreadLocal<Task<?>> current = new ThreadLocal<>();
    private ThreadPoolExecutor pool;
    private volatile boolean closed;

    TckAdapterExecutor(String bindingId, int parallelism, TckThreadOwner owner) {
        this.bindingId = bindingId;
        this.parallelism = parallelism;
        this.owner = owner;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task");
        requireOpen();
        Object caller = owner.current();
        boolean inRun = caller instanceof TckAdapterContext.Run run && !run.ended();
        boolean inScheduled = caller instanceof Execution execution && !execution.stopRequested();
        if (!inRun && !inScheduled) {
            throw new IllegalStateException("executor().submit(...) is allowed only inside a run (a hook call or"
                    + " another submitted task) or a scheduled task — not from bind() or an own thread");
        }
        ThreadPoolExecutor target = pool();
        if (queuedTasks.incrementAndGet() > TckAdapterContext.MAX_QUEUED_TASKS) {
            queuedTasks.decrementAndGet();
            throw new RejectedExecutionException("executor().submit(...): "
                    + TckAdapterContext.MAX_QUEUED_TASKS + " tasks of connection <" + bindingId + "> already wait to"
                    + " start — wait for some of them first");
        }
        Task<T> hosted = new Task<>(caller, () -> owner.call(caller, task));
        live.add(hosted);
        if (caller instanceof TckAdapterContext.Run run) {
            run.submitted(hosted);
        }
        try {
            target.execute(hosted::runClaimed);
        } catch (RejectedExecutionException released) {
            hosted.cancel(false);
        }
        return hosted;
    }

    @Override
    public int parallelism() {
        return parallelism;
    }

    @Override
    public Scheduled schedule(String name, Duration interval, ScheduledTask task) {
        if (name == null || !TASK_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("scheduled task name <" + name + "> must match [a-z0-9_]{1,40}");
        }
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive: " + interval);
        }
        Objects.requireNonNull(task, "task");
        requireOpen();
        Registered registered = new Registered(name, task);
        synchronized (this) {
            if (!scheduled.containsKey(name) && scheduled.size() >= TckAdapterContext.MAX_SCHEDULED) {
                throw new IllegalStateException("At most " + TckAdapterContext.MAX_SCHEDULED + " tasks may be"
                        + " scheduled for connection <" + bindingId + "> at once — close one of them first");
            }
            if (scheduled.putIfAbsent(name, registered) != null) {
                throw new IllegalStateException("A task named <" + name + "> is already scheduled for connection <"
                        + bindingId + "> — close its Scheduled first");
            }
        }
        return registered;
    }

    /** The names of the scheduled tasks that have not been closed. */
    Set<String> scheduledNames() {
        return Set.copyOf(scheduled.keySet());
    }

    /**
     * Runs the scheduled task {@code name} once on the calling thread, with the host's deadline for an execution
     * ({@link TckAdapterContext#SCHEDULE_DEADLINE}).
     *
     * @throws IllegalStateException if no task of this name is scheduled
     */
    void runNow(String name) throws Exception {
        Registered registered = scheduled.get(name);
        if (registered == null || closed) {
            throw new IllegalStateException("No task named <" + name + "> is scheduled for connection <"
                    + bindingId + ">");
        }
        Execution execution = new Execution(registered, Instant.now().plus(TckAdapterContext.SCHEDULE_DEADLINE));
        owner.call(execution, () -> {
            registered.task.run(execution);
            return null;
        });
    }

    private synchronized ThreadPoolExecutor pool() {
        requireOpen();
        if (pool == null) {
            String prefix = THREAD_PREFIX + "-" + bindingId.substring(0, Math.min(8, bindingId.length())) + "-";
            pool = new ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, prefix + threads.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        return pool;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The executor of connection <" + bindingId
                    + "> is closed — the binding has ended");
        }
    }

    /**
     * The tasks of a binding end with it, as in the host: scheduled tasks are unregistered, waiting ones
     * cancelled, running ones have up to {@value TckAdapterContext#RELEASE_WAIT_SECONDS} s, then they are
     * interrupted and the threads end. A further call does nothing.
     */
    void close() {
        ThreadPoolExecutor stopped;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stopped = pool;
            pool = null;
        }
        scheduled.values().forEach(task -> task.cancelled = true);
        scheduled.clear();
        List.copyOf(live).forEach(Task::cancelIfWaiting);
        if (stopped != null) {
            stopped.shutdown();
            try {
                if (!stopped.awaitTermination(TckAdapterContext.RELEASE_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    stopped.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                stopped.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public String toString() {
        return "AdapterExecutor[connection " + bindingId + ", parallelism " + parallelism
                + (closed ? ", closed" : "") + "]";
    }

    /**
     * A task from {@code submit}: the adapter's {@link Future}, with the host's rule for waiting without a time
     * limit (see {@link TckAdapterExecutor}).
     */
    final class Task<T> extends FutureTask<T> {

        /** The run or the execution of the submitter. */
        private final Object submitter;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicBoolean queued = new AtomicBoolean(true);

        private Task(Object submitter, Callable<T> callable) {
            super(callable);
            this.submitter = submitter;
        }

        /** Cancels the task if nobody has taken it yet; a running one finishes and delivers its result. */
        void cancelIfWaiting() {
            if (!claimed.get()) {
                cancel(false);
            }
        }

        /** Runs it if nobody has taken it yet (the pool or a waiting task). */
        private void runClaimed() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            leftQueue();
            Task<?> outer = current.get();
            current.set(this);
            try {
                run();
            } finally {
                if (outer == null) {
                    current.remove();
                } else {
                    current.set(outer);
                }
                live.remove(this);
            }
        }

        private void leftQueue() {
            if (queued.compareAndSet(true, false)) {
                queuedTasks.decrementAndGet();
            }
        }

        @Override
        protected void done() {
            if (!claimed.get()) {
                leftQueue();
                live.remove(this);
            }
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            Task<?> caller = current.get();
            if (caller != null && caller.submitter == submitter && !isDone() && !claimed.get()) {
                //A task of the same run is waiting: it executes the not yet started task itself (as in the host)
                runClaimed();
                if (isCancelled() && !caller.isCancelled()) {
                    //cancel(true) interrupted its code on THIS thread; on a thread of its own it would not have
                    //hit the waiting caller, so the caller does not stay interrupted
                    Thread.interrupted();
                }
            }
            return super.get();
        }

        @Override
        public String toString() {
            return "AdapterTask[" + submitter + (isCancelled() ? ", cancelled" : isDone() ? ", done" : "") + "]";
        }
    }

    /** A scheduled task; {@link #close()} unregisters it. */
    private final class Registered implements Scheduled {

        private final String name;
        private final ScheduledTask task;
        private volatile boolean cancelled;

        private Registered(String name, ScheduledTask task) {
            this.name = name;
            this.task = task;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void close() {
            cancelled = true;
            scheduled.remove(name, this);
        }

        @Override
        public String toString() {
            return "Scheduled[" + name + (cancelled ? ", closed" : "") + "]";
        }
    }

    /** One execution of a scheduled task. */
    private final class Execution implements TaskContext {

        private final Registered registered;
        private final Instant deadline;

        private Execution(Registered registered, Instant deadline) {
            this.registered = registered;
            this.deadline = deadline;
        }

        @Override
        public String name() {
            return registered.name;
        }

        @Override
        public Instant deadline() {
            return deadline;
        }

        @Override
        public boolean stopRequested() {
            return registered.cancelled || closed || !Instant.now().isBefore(deadline);
        }
    }
}
