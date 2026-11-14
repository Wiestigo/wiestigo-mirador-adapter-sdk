package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterDescriptor;

import java.util.List;
import java.util.Map;

/**
 * Where the TCK cases get their contexts from ({@link AdapterContractTest#contractHost()}).
 * The default {@link #tck()} builds {@link TckAdapterContext}. A host
 * implementation can supply its real context instead, so that the same
 * contract cases run against it. It may register its {@code ContractHost}
 * through {@code ServiceLoader}, so that test code obtains it without
 * depending on the host's classes.
 *
 * <p>A host must keep what the cases expect of a context: required keys
 * without a value fail before binding, every request through {@code http()}
 * carries the binding's stamp ({@link ContractContext#networkStamp()}), a
 * run has the deadline of its {@code SyncRunContext}, the run store is empty
 * at the end of the run, and after {@link ContractContext#release()} an
 * {@code http()} obtained before sends nothing more. A broken rule shows up
 * in the cases with a message that names the context, not the adapter.</p>
 *
 * <p>The suite of an adapter always uses the default
 * ({@link #ACCEPTED_PACKAGES}); a run with another host reports itself as a
 * run of the host's test harness. A green TCK is the adapter vendor's own
 * statement; Mirador does not certify adapters.</p>
 */
public interface ContractHost {

    /**
     * The context of a new connection of this adapter.
     *
     * @param configuration the connection's values (relative keys)
     * @param platform      the deployment's values (relative keys, scope {@code PLATFORM})
     * @throws IllegalStateException    if declared required keys are missing
     * @throws IllegalArgumentException if the host would not store a value in this form
     */
    ContractContext context(AdapterDescriptor descriptor, Map<String, String> configuration,
                            Map<String, String> platform);

    /**
     * Like {@link #context(AdapterDescriptor, Map, Map)}, plus values of the
     * host's own run configuration under their full names (for example the
     * response timeout of its HTTP path, or the number of service instances
     * that share the rate limit). A host applies them to this connection.
     *
     * <p>The default accepts none and fails if any are given, rather than
     * silently using its own defaults: a case would otherwise check a timeout
     * that does not apply. The TCK's context gets its timeouts through
     * {@code TckAdapterContext.Builder#network}.</p>
     *
     * @param hostRunConfiguration values of the host's run configuration, full name to value
     * @throws UnsupportedOperationException if the host takes no such values and some are given
     */
    default ContractContext context(AdapterDescriptor descriptor, Map<String, String> configuration,
                                    Map<String, String> platform, Map<String, String> hostRunConfiguration) {
        if (!hostRunConfiguration.isEmpty()) {
            throw new UnsupportedOperationException("The contract host " + this + " takes no values of its own run"
                    + " configuration, but got " + new java.util.TreeSet<>(hostRunConfiguration.keySet())
                    + ": a case would check a setting that does not apply");
        }
        return context(descriptor, configuration, platform);
    }

    /**
     * Like {@link #context(AdapterDescriptor, Map, Map)}, for a connection with
     * a client identity: a PEM with the certificate and its unencrypted PKCS#8
     * private key, as the host creates it for an adapter type with a
     * {@code CLIENT_IDENTITY} key ({@link AdapterContractTest#clientIdentity()}).
     * If the certificate names the binding
     * ({@code URI:urn:rmm:binding:<bindingId>}), that is the binding's
     * identifier, as in the host.
     *
     * <p>The default accepts no identity and fails if one is given, rather than
     * silently dropping it: a case would otherwise check an HTTP path without
     * the identity the adapter connects with. The TCK's context takes it
     * through {@code TckAdapterContext.Builder#clientIdentity}.</p>
     *
     * @param clientIdentity the connection's identity; empty: none
     * @throws UnsupportedOperationException if the host takes no identity and one is given
     */
    default ContractContext context(AdapterDescriptor descriptor, Map<String, String> configuration,
                                    Map<String, String> platform, java.util.Optional<String> clientIdentity) {
        if (clientIdentity.isPresent()) {
            throw new UnsupportedOperationException("The contract host " + this + " takes no client identity of a"
                    + " connection, but the test gives one: a case would check a network path without the identity"
                    + " the adapter connects with");
        }
        return context(descriptor, configuration, platform);
    }

    /**
     * Whether a thread belongs to the host and not to a binding (shared event
     * loops, database driver threads), so that case 4 does not attribute it
     * to the binding. Threads from {@code executor()} belong to the binding.
     * The case output lists what is exempted.
     */
    default boolean ownsThread(Thread thread) {
        return false;
    }

    /**
     * The package prefixes a host other than {@link #tck()} may come from: the
     * Mirador host with its test harness, and the TCK itself. Both the host
     * class and the test class must come from these packages, otherwise every
     * case fails ({@link AdapterContractTest}). The suite of an adapter uses
     * the default, because a host of its own could hollow out case 4
     * ({@link #ownsThread}) or bend what the context reports.
     *
     * <p>This guards against mistakes, not against intent: only the package
     * prefix is compared, and whoever builds a run chooses its packages. To
     * rely on a TCK result, run the suite yourself and check the class of the
     * host, not only its package.</p>
     */
    List<String> ACCEPTED_PACKAGES = List.of("com.synchronisation.", "com.rmm.adapter.tck.");

    /** The TCK as host: {@link TckAdapterContext}; the shared client of its HTTP path belongs to no binding. */
    static ContractHost tck() {
        return TckContractHost.INSTANCE;
    }
}
