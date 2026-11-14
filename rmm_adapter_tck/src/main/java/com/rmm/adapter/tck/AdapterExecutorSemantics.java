package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The semantics of {@link AdapterExecutor} as an adapter observes them. The
 * same abstract test runs against the {@code executor()} of the TCK
 * ({@link TckAdapterContext}) and against the host's, so an adapter that
 * passes in the TCK meets the same rules in the host:
 * <ul>
 *   <li>A task that waits without a time limit ({@code get()}) for a task of
 *       its run that has not started runs that task itself. The bounded pool
 *       therefore never deadlocks, even with twice as many tasks as threads
 *       each waiting on a subtask, or with subtasks of subtasks.</li>
 *   <li>With a time limit ({@code get(t, unit)}) the task only waits, at most
 *       that long, and then gets the {@link TimeoutException}.</li>
 *   <li>{@code cancel(true)} on a subtask that its waiter runs itself
 *       interrupts the subtask's code; the waiter gets the
 *       {@link CancellationException} and is not left interrupted (as with a
 *       {@link java.util.concurrent.FutureTask} on its own thread).</li>
 *   <li>At the end of the run, waiting tasks are cancelled; running tasks
 *       finish and deliver their result.</li>
 *   <li>Outside a run, {@code submit} throws
 *       {@link IllegalStateException}.</li>
 * </ul>
 *
 * <p>An implementation supplies the {@code executor()} of a binding with
 * {@code parallelism} threads ({@link #subject}) and runs work in an active
 * run of that binding ({@link Subject#inRun}).</p>
 */
public abstract class AdapterExecutorSemantics {

    private static final long GUARD_SECONDS = 30;
    /** How long a subtask waits for its interrupt; shorter than the guard, so that its waiter still answers. */
    private static final long INTERRUPT_SECONDS = 10;

    /** The {@code executor()} of a binding under test. */
    protected interface Subject extends AutoCloseable {

        /** The executor as the adapter gets it from {@code context.executor()}. */
        AdapterExecutor executor();

        /**
         * Runs {@code work} in a new active run of this binding, with the
         * calling thread bound to it (as for a hook call); the run then ends
         * as it does in the host.
         */
        <T> T inRun(Callable<T> work) throws Exception;

        /** Releases the binding. */
        @Override
        void close();
    }

    /** A binding whose {@code executor()} runs at most {@code parallelism} tasks at once. */
    protected abstract Subject subject(int parallelism) throws Exception;

    private final List<Subject> opened = new ArrayList<>();

    private Subject open(int parallelism) throws Exception {
        Subject subject = subject(parallelism);
        opened.add(subject);
        return subject;
    }

    @AfterEach
    public void closeSubjects() {
        opened.forEach(Subject::close);
        opened.clear();
    }

    private static boolean await(CountDownLatch latch) throws InterruptedException {
        return latch.await(GUARD_SECONDS, TimeUnit.SECONDS);
    }

    /** Sixteen tasks on eight threads, each waiting with {@code get()} for its subtask. */
    @Test
    public void sixteenTasksEachWaitingOnASubtaskNeverDeadlockEightThreads() throws Exception {
        Subject subject = open(8);
        AdapterExecutor executor = subject.executor();
        int sum = subject.inRun(() -> {
            List<Future<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(executor.submit(() -> executor.submit(() -> 1).get() + 1));
            }
            int total = 0;
            for (Future<Integer> task : tasks) {
                total += task.get(GUARD_SECONDS, TimeUnit.SECONDS);
            }
            return total;
        });
        assertEquals(32, sum, "every task ran its subtask, nothing deadlocked");
    }

    @Test
    public void subtasksOfSubtasksNeverDeadlockASingleThread() throws Exception {
        Subject subject = open(1);
        AdapterExecutor executor = subject.executor();
        int result = subject.inRun(() -> executor.submit(() -> executor.submit(() -> executor.submit(() -> 1).get()
                + 1).get() + 1).get(GUARD_SECONDS, TimeUnit.SECONDS));
        assertEquals(3, result);
    }

    @Test
    public void aTimedGetOnlyWaitsAndHonoursItsTimeout() throws Exception {
        Subject subject = open(1);
        AdapterExecutor executor = subject.executor();
        AtomicReference<String> subtaskThread = new AtomicReference<>();
        CountDownLatch subtaskRan = new CountDownLatch(1);
        AtomicBoolean ranInTheRun = new AtomicBoolean();
        String outcome = subject.inRun(() -> {
            String timed = executor.submit(() -> {
                Future<Integer> subtask = executor.submit(() -> {
                    subtaskThread.set(Thread.currentThread().getName());
                    subtaskRan.countDown();
                    return 1;
                });
                try {
                    subtask.get(10, TimeUnit.MILLISECONDS);
                    return "returned";
                } catch (TimeoutException expected) {
                    return "timed out, run by " + subtaskThread.get();
                }
            }).get(GUARD_SECONDS, TimeUnit.SECONDS);
            //Still inside the run: at its end the host cancels waiting tasks, and the subtask would never run
            ranInTheRun.set(await(subtaskRan));
            return timed;
        });
        assertEquals("timed out, run by null", outcome, "the only thread waited, nobody ran the subtask");
        assertTrue(ranInTheRun.get(), "the pool runs it afterwards, while the run lasts");
    }

    @Test
    public void cancellingAnInlinedSubtaskInterruptsItsCodeButNotItsWaiter() throws Exception {
        Subject subject = open(1);
        AdapterExecutor executor = subject.executor();
        AtomicReference<Future<String>> subtask = new AtomicReference<>();
        CountDownLatch subtaskWorks = new CountDownLatch(1);
        AtomicReference<Thread> subtaskThread = new AtomicReference<>();
        AtomicBoolean subtaskInterrupted = new AtomicBoolean();
        AtomicBoolean subtaskGaveUp = new AtomicBoolean();
        String outcome = subject.inRun(() -> {
            Future<String> task = executor.submit(() -> {
                Future<String> child = executor.submit(() -> {
                    subtaskThread.set(Thread.currentThread());
                    subtaskWorks.countDown();
                    long giveUpAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(INTERRUPT_SECONDS);
                    while (!Thread.currentThread().isInterrupted()) {
                        if (System.nanoTime() - giveUpAt >= 0) {
                            //Never interrupted: end instead of spinning forever; the test fails below
                            subtaskGaveUp.set(true);
                            return "never interrupted";
                        }
                        Thread.onSpinWait();
                    }
                    subtaskInterrupted.set(true);
                    return "never seen";
                });
                subtask.set(child);
                String seen;
                try {
                    seen = child.get();
                } catch (CancellationException expected) {
                    seen = "cancelled";
                }
                return seen + ", waiter interrupted " + Thread.currentThread().isInterrupted() + ", same thread "
                        + (subtaskThread.get() == Thread.currentThread());
            });
            assertTrue(await(subtaskWorks));
            assertTrue(subtask.get().cancel(true));
            return task.get(GUARD_SECONDS, TimeUnit.SECONDS);
        });
        assertFalse(subtaskGaveUp.get(), "cancel(true) did not interrupt the code of the subtask within "
                + INTERRUPT_SECONDS + " seconds");
        assertEquals("cancelled, waiter interrupted false, same thread true", outcome);
        assertTrue(subtaskInterrupted.get(), "the subtask's code was interrupted");
    }

    @Test
    public void atTheEndOfTheRunWaitingTasksAreCancelledAndRunningOnesFinishWithTheirResult() throws Exception {
        Subject subject = open(1);
        AdapterExecutor executor = subject.executor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        List<Future<String>> futures = subject.inRun(() -> {
            Future<String> running = executor.submit(() -> {
                started.countDown();
                proceed.await();
                return "finished";
            });
            Future<String> queued = executor.submit(() -> {
                queuedRan.set(true);
                return "queued";
            });
            assertTrue(await(started));
            return List.of(running, queued);
        });

        assertTrue(futures.get(1).isCancelled(), "a waiting task is cancelled at the end of its run");
        proceed.countDown();
        assertEquals("finished", futures.get(0).get(GUARD_SECONDS, TimeUnit.SECONDS),
                "a running task finishes and delivers its result");
        assertFalse(queuedRan.get());
    }

    @Test
    public void submitOutsideARunIsRefused() throws Exception {
        Subject subject = open(1);
        assertThrows(IllegalStateException.class, () -> subject.executor().submit(() -> 1));
    }
}
