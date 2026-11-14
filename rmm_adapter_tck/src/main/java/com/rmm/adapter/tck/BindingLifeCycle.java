package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterInitialisationException;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ToolAdapter;

import java.net.URI;
import java.util.List;

/**
 * The life cycle of a binding as the host drives it: bound through the
 * context of its connection, closed in the host's order, and released with
 * the checks on what a closed binding must not leave behind.
 */
final class BindingLifeCycle {

    /** Where {@link #requireClosedNetworkPath} sends: a port on which nothing listens. */
    private static final URI RELEASED_TARGET = URI.create("http://127.0.0.1:9/tck-released");

    private BindingLifeCycle() {
    }

    /**
     * Binds as the host does: returns the instance with its ports wrapped the
     * way the context calls them ({@link ContractContext#hosted}). Tests that
     * check the instance itself call {@code toolAdapter.bind} directly.
     */
    static AdapterInstance bind(ToolAdapter toolAdapter, ContractContext context)
            throws AdapterInitialisationException {
        AdapterInstance instance = toolAdapter.bind(context);
        if (instance == null) {
            return null;
        }
        declareTheKeysOfTheBinding(context, instance);
        return context.hosted(instance);
    }

    /**
     * As the host does after binding: declares the keys the instance adds to
     * those of its type, so that {@code config()} reads them
     * ({@link TckAdapterContext#declareConnectionKeys}). Only for the context
     * of the TCK; the context of a host declares them where the host binds.
     * Whether the instance may declare them is checked by
     * {@link AdapterContractTest#theBindingDeclarationOnlyNarrowsItsType()}.
     */
    static void declareTheKeysOfTheBinding(ContractContext context, AdapterInstance instance) {
        List<ConfigKey> keys = instance.connectionKeys();
        if (keys != null && context instanceof TckAdapterContext tck) {
            tck.declareConnectionKeys(keys);
        }
    }

    /**
     * Closes the binding in the host's order and checks nothing: the tasks,
     * the instance, then the context, which is released even if
     * {@code close()} fails.
     */
    static void close(AdapterInstance instance, ContractContext context) {
        try {
            context.releaseTasks();
            instance.close();
        } finally {
            context.release();
        }
    }

    /**
     * Closes the binding and releases what the host releases afterwards
     * ({@link ContractContext#release()}). Fails if a stream of
     * {@code http().open(...)} is still open: in the host it holds a
     * connection until the deadline of the run, a leak without a thread of
     * its own that case 4 would otherwise miss. Also checks the context:
     * after {@code release()}, an {@code http()} that the adapter got before
     * sends nothing ({@link IllegalStateException}); otherwise every released
     * binding would keep its connection pool, and an adapter that keeps its
     * {@code http()} from {@code bind} would go on sending through a closed
     * binding.
     */
    static void closeAndRelease(AdapterInstance instance, ContractContext context) {
        int open;
        AdapterHttp http;
        try {
            context.releaseTasks();
            instance.close();
        } finally {
            open = context.openStreams();
            http = context.http();
            context.release();
        }
        if (open > 0) {
            throw new TckViolation("close() left " + open + " response stream(s) of http().open(...) open — close"
                    + " every StreamResponse (try-with-resources); in the host an open stream holds a connection of"
                    + " the binding until the deadline of the run");
        }
        requireClosedNetworkPath(http, context);
    }

    /** An {@code http()} from before {@code release()} sends nothing afterwards; otherwise the context is at fault. */
    private static void requireClosedNetworkPath(AdapterHttp http, ContractContext context) {
        String outcome;
        try {
            outcome = "answered " + http.send(AdapterRequest.get(RELEASED_TARGET)).status();
        } catch (IllegalStateException closed) {
            return;
        } catch (AdapterException | RuntimeException sent) {
            outcome = "failed with " + sent.getClass().getSimpleName() + " (not IllegalStateException)";
        }
        throw new TckViolation("After release() a send on the http() of the released binding " + outcome
                + " — the context under test (" + context + ") did not close the network path on release; a defect"
                + " of the context, not of the adapter: every released binding would keep its connection pool");
    }
}
