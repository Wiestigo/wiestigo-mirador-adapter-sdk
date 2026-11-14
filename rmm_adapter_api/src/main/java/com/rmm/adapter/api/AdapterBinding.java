package com.rmm.adapter.api;

/**
 * The binding for which the host hands out an {@link AdapterContext}. The
 * adapter learns only its opaque identifier: not the freely chosen name of the
 * connection, no tenant, no operating mode.
 *
 * <p>The {@code bindingId} is suitable for logging and for partitioning (for
 * example in a remote service). On its own it authorizes nothing: a remote
 * service accepts the header {@code X-RMM-Binding} only if it names the binding
 * for which the client certificate of the connection was issued
 * ({@code URI:urn:rmm:binding:<bindingId>}). The certificate authorizes; the
 * identifier only assigns. Its structure is not part of the contract; the
 * adapter must not parse it.
 *
 * <p>Stability: the identifier is stable per connection, also across rebinds,
 * restarts, relocation and restore; it is new only for a new connection. An
 * adapter may therefore partition by it permanently.
 *
 * @param bindingId opaque, non-empty identifier of the binding.
 */
public record AdapterBinding(String bindingId) {

    public AdapterBinding {
        if (bindingId == null || bindingId.isBlank()) {
            throw new IllegalArgumentException("bindingId must not be empty");
        }
    }
}
