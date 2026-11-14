package com.rmm.adapter.http.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The threads below a connector: idle threads first, at most eight, behind
 * them a bounded backlog, and no thread without work. Checked on the
 * executor itself and without timing assumptions: tasks hold on latches, and
 * "one after the other" means that the executor has completed the previous
 * task ({@code getCompletedTaskCount}); the idle time is short and is waited
 * for, not estimated.
 */
class TestConnectorExecutor {

    private static final int THREADS = AdapterHttpConnector.DEFAULT_THREADS;
    private static final long WAIT_SECONDS = 10;

    private final List<ThreadPoolExecutor> executors = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void close() {
        release.countDown();
        executors.forEach(ThreadPoolExecutor::shutdownNow);
    }

    @Test
    void tasksOneAfterTheOtherUseOneThread() throws Exception {
        ThreadPoolExecutor executor = executor(60_000);
        Set<Thread> ranOn = ConcurrentHashMap.newKeySet();

        for (int task = 1; task <= 20; task++) {
            executor.submit(() -> ranOn.add(Thread.currentThread())).get(WAIT_SECONDS, TimeUnit.SECONDS);
            awaitCompleted(executor, task);
        }

        assertEquals(1, ranOn.size(), "20 tasks one after the other: " + ranOn);
        assertEquals(1, executor.getLargestPoolSize(), "never a second thread");
    }

    @Test
    void eightTasksAtOnceUseEightThreadsAndTheNinthWaits() throws Exception {
        ThreadPoolExecutor executor = executor(60_000);
        Set<Thread> ranOn = ConcurrentHashMap.newKeySet();
        CountDownLatch started = new CountDownLatch(THREADS);
        for (int task = 0; task < THREADS; task++) {
            executor.execute(held(started, ranOn));
        }
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS), "eight tasks run at once");
        assertEquals(THREADS, ranOn.size());
        assertEquals(THREADS, executor.getPoolSize());

        AtomicBoolean ninthRan = new AtomicBoolean();
        CountDownLatch ninthDone = new CountDownLatch(1);
        executor.execute(() -> {
            ranOn.add(Thread.currentThread());
            ninthRan.set(true);
            ninthDone.countDown();
        });
        assertEquals(1, executor.getQueue().size(), "the ninth waits in the backlog, it is not refused");
        assertFalse(ninthRan.get(), "and does not run while eight are busy");
        assertEquals(THREADS, executor.getPoolSize(), "no ninth thread");

        release.countDown();
        assertTrue(ninthDone.await(WAIT_SECONDS, TimeUnit.SECONDS), "it runs once a thread is free");
        assertEquals(THREADS, ranOn.size(), "on one of the eight");
        assertEquals(THREADS, executor.getLargestPoolSize());
    }

    @Test
    void idleThreadsAreTakenBeforeANewOneStarts() throws Exception {
        ThreadPoolExecutor executor = executor(60_000);
        Set<Thread> first = ConcurrentHashMap.newKeySet();
        CountDownLatch threeStarted = new CountDownLatch(3);
        CountDownLatch releaseThree = new CountDownLatch(1);
        for (int task = 0; task < 3; task++) {
            executor.execute(() -> {
                first.add(Thread.currentThread());
                threeStarted.countDown();
                await(releaseThree);
            });
        }
        assertTrue(threeStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
        releaseThree.countDown();
        awaitCompleted(executor, 3);
        assertEquals(3, executor.getPoolSize(), "three idle threads");

        Set<Thread> second = ConcurrentHashMap.newKeySet();
        CountDownLatch fiveStarted = new CountDownLatch(5);
        for (int task = 0; task < 5; task++) {
            executor.execute(held(fiveStarted, second));
        }
        assertTrue(fiveStarted.await(WAIT_SECONDS, TimeUnit.SECONDS), "five run at once");
        assertEquals(5, second.size());
        assertTrue(second.containsAll(first), "the three idle threads took three of them: " + second);
        assertEquals(5, executor.getPoolSize(), "and only two more started");
    }

    @Test
    void theBacklogIsBoundedAndARefusedTaskNeverRuns() throws Exception {
        ThreadPoolExecutor executor = executor(60_000);
        CountDownLatch started = new CountDownLatch(THREADS);
        Set<Thread> ranOn = ConcurrentHashMap.newKeySet();
        for (int task = 0; task < THREADS; task++) {
            executor.execute(held(started, ranOn));
        }
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        AtomicInteger queuedRan = new AtomicInteger();
        for (int task = 0; task < AdapterHttpConnector.QUEUED_TASKS; task++) {
            executor.execute(queuedRan::incrementAndGet);
        }
        assertEquals(AdapterHttpConnector.QUEUED_TASKS, executor.getQueue().size());

        AtomicBoolean refusedRan = new AtomicBoolean();
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> refusedRan.set(true)),
                "one more than the backlog holds is refused");

        release.countDown();
        awaitCompleted(executor, THREADS + AdapterHttpConnector.QUEUED_TASKS);
        assertEquals(AdapterHttpConnector.QUEUED_TASKS, queuedRan.get(), "every waiting task ran");
        assertFalse(refusedRan.get(), "the refused one never");
        assertEquals(THREADS, executor.getLargestPoolSize());

        //After that the limit applies to new tasks again, and the freed threads take them
        executor.submit(() -> { }).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(THREADS, executor.getLargestPoolSize());
    }

    /**
     * A refused task is not counted any further. After an overload (the
     * 1025th waiting task is refused), once everything is worked off and the
     * threads have ended, work one after the other needs exactly one thread
     * again; a counter that kept the refusal would make every further task
     * start a new thread. The same holds after closing.
     */
    @Test
    void aRefusedTaskLeavesNoCountBehindAndWorkOneAfterTheOtherUsesOneThreadAgain() throws Exception {
        ThreadPoolExecutor executor = executor(300);
        CountDownLatch started = new CountDownLatch(THREADS);
        Set<Thread> busy = ConcurrentHashMap.newKeySet();
        for (int task = 0; task < THREADS; task++) {
            executor.execute(held(started, busy));
        }
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        for (int task = 0; task < AdapterHttpConnector.QUEUED_TASKS; task++) {
            executor.execute(() -> { });
        }
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }), "the overload");
        release.countDown();
        awaitCompleted(executor, THREADS + AdapterHttpConnector.QUEUED_TASKS);
        assertEquals(0, unfinished(executor), "nothing counted after the overload");
        for (Thread thread : busy) {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        }
        awaitPoolSize(executor, 0);

        Set<Thread> ranOn = ConcurrentHashMap.newKeySet();
        long completed = executor.getCompletedTaskCount();
        for (int task = 1; task <= 20; task++) {
            executor.submit(() -> ranOn.add(Thread.currentThread())).get(WAIT_SECONDS, TimeUnit.SECONDS);
            awaitCompleted(executor, completed + task);
        }
        assertEquals(1, ranOn.size(), "one after the other on one thread again: " + ranOn);

        executor.shutdownNow();
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }));
        assertEquals(0, unfinished(executor), "a task refused by a closed executor counts neither");
    }

    /**
     * If the wait of a thread ends without a task while another thread is
     * busy, and a task arrives at that moment, the backlog accepts it (the
     * thread still counts) although the thread retires. Its end then starts a
     * new thread for the task, which does not wait until the busy one is
     * free. Deterministic: a hook holds the thread exactly between the end of
     * its wait and its exit.
     */
    @Test
    void aTaskArrivingWhileAnIdleThreadRetiresDoesNotWaitBehindABusyOne() throws Exception {
        ThreadPoolExecutor executor = executor(50);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch releaseIdle = new CountDownLatch(1);
        Set<Thread> ranOn = ConcurrentHashMap.newKeySet();
        executor.execute(held(bothStarted, ranOn));
        executor.execute(() -> {
            ranOn.add(Thread.currentThread());
            bothStarted.countDown();
            await(releaseIdle);
        });
        assertTrue(bothStarted.await(WAIT_SECONDS, TimeUnit.SECONDS), "one busy thread, one soon idle");
        assertEquals(2, executor.getPoolSize());

        CountDownLatch timedOut = new CountDownLatch(1);
        CountDownLatch letItGo = new CountDownLatch(1);
        AtomicBoolean once = new AtomicBoolean();
        backlog(executor).afterIdleTimeout = () -> {
            if (once.compareAndSet(false, true)) {
                timedOut.countDown();
                await(letItGo);
            }
        };
        releaseIdle.countDown();
        assertTrue(timedOut.await(WAIT_SECONDS, TimeUnit.SECONDS), "the idle thread's wait ended without a task");

        CountDownLatch arrived = new CountDownLatch(1);
        executor.execute(arrived::countDown);
        assertEquals(1, executor.getQueue().size(), "taken in: the retiring thread still counts");
        letItGo.countDown();

        assertTrue(arrived.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "the task ran while the other thread was still busy (not stranded behind it)");
        assertEquals(1, release.getCount(), "the busy one is still held");
    }

    @Test
    void theRefusalPutsATaskIntoTheBacklogWhenNoThreadMayStartAndRefusesWhenItIsFullOrClosed() throws Exception {
        //The refusal path, called directly: offer declined (no thread free) and the new thread did not start
        ThreadPoolExecutor executor = executor(60_000);
        CountDownLatch started = new CountDownLatch(THREADS);
        for (int task = 0; task < THREADS; task++) {
            executor.execute(held(started, ConcurrentHashMap.newKeySet()));
        }
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        AtomicBoolean forcedRan = new AtomicBoolean();
        Runnable forced = () -> forcedRan.set(true);
        executor.getRejectedExecutionHandler().rejectedExecution(forced, executor);
        assertTrue(executor.getQueue().contains(forced), "it waits instead of being refused");

        for (int task = 1; task < AdapterHttpConnector.QUEUED_TASKS; task++) {
            executor.getQueue().offer(() -> { });
        }
        assertThrows(RejectedExecutionException.class,
                () -> executor.getRejectedExecutionHandler().rejectedExecution(() -> { }, executor),
                "a full backlog refuses");
        release.countDown();
        awaitCompleted(executor, THREADS + 1);
        assertTrue(forcedRan.get());

        executor.shutdownNow();
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }),
                "a closed executor refuses");
        assertThrows(RejectedExecutionException.class,
                () -> executor.getRejectedExecutionHandler().rejectedExecution(() -> { }, executor));
    }

    @Test
    void theRefusalStartsAThreadWhenTheLastOneHasJustEnded() throws Exception {
        ThreadPoolExecutor executor = executor(60_000);
        assertEquals(0, executor.getPoolSize());
        CountDownLatch ran = new CountDownLatch(1);
        executor.getRejectedExecutionHandler().rejectedExecution(ran::countDown, executor);
        assertTrue(ran.await(WAIT_SECONDS, TimeUnit.SECONDS), "the task put into the backlog runs, not stranded");
    }

    @Test
    void idleThreadsEndAndNewWorkStartsOneAgain() throws Exception {
        ThreadPoolExecutor executor = executor(50);
        Set<Thread> ranOn = ConcurrentHashMap.newKeySet();
        CountDownLatch started = new CountDownLatch(3);
        for (int task = 0; task < 3; task++) {
            executor.execute(held(started, ranOn));
        }
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(3, executor.getPoolSize());
        release.countDown();

        for (Thread thread : ranOn) {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertFalse(thread.isAlive(), thread.getName() + " ended after the idle time");
        }
        assertEquals(0, executor.getPoolSize(), "idle, no thread");

        Set<Thread> again = ConcurrentHashMap.newKeySet();
        executor.submit(() -> again.add(Thread.currentThread())).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertEquals(1, again.size());
        assertFalse(ranOn.containsAll(again), "a new thread for new work");
    }

    @Test
    void cancellingInterruptsAndCloseEndsEveryThread() throws Exception {
        ThreadPoolExecutor executor = executor(60_000);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Future<?> running = executor.submit(() -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(60));
            } catch (InterruptedException expected) {
                interrupted.countDown();
            }
        });
        assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));
        running.cancel(true);
        assertTrue(interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS), "cancel interrupts the thread");

        Set<Thread> ranOn = ConcurrentHashMap.newKeySet();
        CountDownLatch busy = new CountDownLatch(2);
        executor.execute(held(busy, ranOn));
        executor.execute(held(busy, ranOn));
        assertTrue(busy.await(WAIT_SECONDS, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), "close ends every thread at once");
    }

    private ThreadPoolExecutor executor(long idleMillis) {
        ThreadPoolExecutor executor = AdapterHttpConnector.executor(THREADS, idleMillis, TimeUnit.MILLISECONDS);
        executors.add(executor);
        return executor;
    }

    /** A task that records its thread, reports its start and holds until {@link #release}. */
    private Runnable held(CountDownLatch started, Set<Thread> ranOn) {
        return () -> {
            ranOn.add(Thread.currentThread());
            started.countDown();
            await(release);
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static int unfinished(ThreadPoolExecutor executor) {
        return ((AdapterHttpConnector.ConnectorExecutor) executor).unfinished();
    }

    private static AdapterHttpConnector.Backlog backlog(ThreadPoolExecutor executor) {
        return (AdapterHttpConnector.Backlog) executor.getQueue();
    }

    /** Waits until the executor has exactly {@code size} threads (threads end after the idle time). */
    private static void awaitPoolSize(ThreadPoolExecutor executor, int size) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (executor.getPoolSize() != size) {
            assertTrue(System.nanoTime() < until, "pool size " + executor.getPoolSize() + ", expected " + size);
            Thread.sleep(5);
        }
    }

    /** Waits until the executor has fully completed {@code count} tasks (after {@code afterExecute}). */
    private static void awaitCompleted(ThreadPoolExecutor executor, long count) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (executor.getCompletedTaskCount() < count) {
            assertTrue(System.nanoTime() < until, "completed " + executor.getCompletedTaskCount() + " of " + count);
            Thread.sleep(1);
        }
    }
}
