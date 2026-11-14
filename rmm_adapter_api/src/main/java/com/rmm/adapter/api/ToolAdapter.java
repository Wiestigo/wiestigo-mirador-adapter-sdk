package com.rmm.adapter.api;

/**
 * An adapter for exactly one external tool: the entry point of the adapter
 * contract.
 *
 * <p>Discovery: the adapter artifact declares its implementation(s) in
 * {@code META-INF/services/com.rmm.adapter.api.ToolAdapter}; the host finds
 * them at startup through {@link java.util.ServiceLoader} and registers them
 * under their {@link AdapterDescriptor#systemKey()}.
 *
 * <p>Life cycle: a {@code ToolAdapter} is the type. It is loaded once per
 * process and plugin unit and is tenant-neutral. {@link #descriptor()} must be
 * answerable without configuration and without side effects (the host reads it
 * before binding to check version, uniqueness and required configuration).
 * Only {@link #bind(AdapterContext)} may read configuration, build clients and
 * check the connection, and it does so per binding: the host may bind the same
 * type several times, and every binding gets its own context and returns its
 * own {@link AdapterInstance}. State across bindings (static clients,
 * process-wide caches) is therefore not allowed.
 */
public interface ToolAdapter {

    /**
     * Identity, version and capabilities of this adapter. Must be constant and
     * must not require configuration.
     */
    AdapterDescriptor descriptor();

    /**
     * Binds the adapter to a connection: read the configuration from the
     * context, build clients, check the connection. Every call returns an
     * instance of its own; everything it needs comes from {@code context}.
     *
     * <p>A failure does not prevent the host from starting; the binding is
     * listed as defective with its cause. The exception should therefore carry
     * a cause an administrator understands (wrong URL, missing token, ...), not
     * raw stack trace prose.
     */
    AdapterInstance bind(AdapterContext context) throws AdapterInitialisationException;
}
