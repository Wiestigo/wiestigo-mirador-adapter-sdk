package com.rmm.adapter.tck;

import java.util.concurrent.Callable;

/**
 * What each thread of a connection currently works for: a run, one execution of a scheduled task, or nothing.
 * The host binds hook calls and submitted tasks to their run in the same way.
 */
final class TckThreadOwner {

    private final ThreadLocal<Object> owner = new ThreadLocal<>();

    /** What the calling thread works for; {@code null} if it is bound to nothing. */
    Object current() {
        return owner.get();
    }

    /** Binds the calling thread to {@code next}; {@code null} releases it. */
    void bind(Object next) {
        if (next == null) {
            owner.remove();
        } else {
            owner.set(next);
        }
    }

    /** Runs {@code work} on the calling thread, bound to {@code next}, and restores what it was bound to before. */
    <T> T call(Object next, Callable<T> work) throws Exception {
        Object before = owner.get();
        owner.set(next);
        try {
            return work.call();
        } finally {
            bind(before);
        }
    }
}
