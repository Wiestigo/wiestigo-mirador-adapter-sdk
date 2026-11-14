package com.rmm.adapter.tck;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Counts the threads a binding leaves behind (case 4): every living thread
 * except those of the JVM, of the TCK, and those the test exempts as
 * belonging to the context or to its fake tool.
 */
final class LeftoverThreads {

    /** Threads that the JVM itself starts on demand; they belong to no binding. */
    private static final List<String> JVM_THREADS = List.of("ForkJoinPool.commonPool", "Common-Cleaner",
            "Reference Handler", "Finalizer", "Signal Dispatcher", "process reaper", "Attach Listener",
            "Notification Thread", "InnocuousThread", "Service Thread", "Sweeper thread", "C1 CompilerThread",
            "C2 CompilerThread", "Monitor Ctrl-Break", "Keep-Alive-Timer", "JFR ");

    /**
     * Threads of the TCK itself: the {@link FakeHttpTool}.
     * {@link ContractHost#ownsThread} says what belongs to the context and to
     * no binding: in the TCK the shared client of the HTTP path with its
     * {@code SelectorManager} (only that one, not the one of a client the
     * adapter builds itself), in the host the event loops of Reactor Netty.
     * The threads of {@code executor()} do count: they belong to the binding,
     * and {@link ContractContext#release()} ends them.
     */
    private static final List<String> TCK_THREADS = List.of(FakeHttpTool.THREAD_PREFIX);

    private final Predicate<Thread> exempt;

    /** @param exempt whether a thread belongs to the context or to the fake tool of the test, and to no binding */
    LeftoverThreads(Predicate<Thread> exempt) {
        this.exempt = exempt;
    }

    /** The threads of {@code alive} that count against a binding: all but those of JVM, TCK, host and fake tool. */
    List<Thread> newThreads(Set<Thread> alive) {
        return newThreads(alive, false);
    }

    /** As {@link #newThreads(Set)}; with {@code report}, the output lists the threads that are not counted. */
    List<Thread> newThreads(Set<Thread> alive, boolean report) {
        List<Thread> counted = new ArrayList<>();
        List<String> notCounted = new ArrayList<>();
        for (Thread thread : alive) {
            if (JVM_THREADS.stream().anyMatch(thread.getName()::startsWith)
                    || TCK_THREADS.stream().anyMatch(thread.getName()::startsWith)
                    || exempt.test(thread)) {
                notCounted.add(thread.getName());
            } else {
                counted.add(thread);
            }
        }
        if (report && !notCounted.isEmpty()) {
            System.out.println("[TCK] Case 4: not counted (JVM, TCK, host or fake tool): " + notCounted);
        }
        return counted;
    }

    /**
     * Waits until at most {@code expected} new threads besides
     * {@code baseline} are alive ({@code System.gc()} on every tick), for at
     * most two seconds, and returns the new threads alive then. With an empty
     * {@code baseline} and {@code expected = -1}: all living threads after a
     * short settling time, the baseline of case 4.
     */
    Set<Thread> settledThreads(Set<Thread> baseline, int expected) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(expected < 0 ? 0 : 2);
        Set<Thread> alive;
        do {
            System.gc();
            Thread.sleep(expected < 0 ? 100 : 20);
            alive = new LinkedHashSet<>();
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.isAlive() && !baseline.contains(thread)) {
                    alive.add(thread);
                }
            }
            if (expected >= 0 && newThreads(alive).size() <= expected) {
                return alive;
            }
        } while (System.nanoTime() < until);
        return alive;
    }
}
