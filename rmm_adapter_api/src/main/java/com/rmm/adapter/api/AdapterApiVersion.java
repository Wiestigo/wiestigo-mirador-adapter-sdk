package com.rmm.adapter.api;

/**
 * Version of this contract. An adapter states the major version it was built
 * against in {@link AdapterDescriptor#apiMajorVersion()}; the host rejects an
 * incompatible adapter at load time with a clear message instead of failing
 * later with {@code AbstractMethodError}.
 *
 * <p>Within a major version the contract changes only in backward-compatible
 * ways (new {@code default} methods, new optional capabilities). Major 1 is the
 * contract with bindings ({@link ToolAdapter#bind(AdapterContext)}); adapters
 * built for major 0 are not loaded.
 *
 * <p><b>Major 1 is frozen.</b> Removing or changing a public member breaks
 * major 1; a new member arrives only with a new minor version ({@link #MINOR}
 * + 1). An adapter states the minor version it was built against in
 * {@link AdapterDescriptor#apiMinorVersion()} and in its manifest
 * ({@code apiMinor}); a host with a lower minor version rejects it at load time
 * with a message.
 */
public final class AdapterApiVersion {

    /** Current major version of the contract. */
    public static final int MAJOR = 1;

    /**
     * Current minor version of the contract. Minor 1 added image reference
     * forms, the client identity, reasons for a failed bind, identifier kinds
     * per binding, the connect timeout key and the minor version itself.
     */
    public static final int MINOR = 1;

    private AdapterApiVersion() {
    }
}
