package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterDescriptor;

import java.util.Map;
import java.util.Optional;

/** The default {@link ContractHost#tck()}: builds {@link TckAdapterContext}; a singleton. */
final class TckContractHost implements ContractHost {

    static final TckContractHost INSTANCE = new TckContractHost();

    private TckContractHost() {
    }

    @Override
    public ContractContext context(AdapterDescriptor descriptor, Map<String, String> configuration,
                                   Map<String, String> platform) {
        return TckAdapterContext.builder(descriptor).configuration(configuration).platform(platform).build();
    }

    /** With the connection's client identity: {@code TckAdapterContext.Builder#clientIdentity}. */
    @Override
    public ContractContext context(AdapterDescriptor descriptor, Map<String, String> configuration,
                                   Map<String, String> platform, Optional<String> clientIdentity) {
        TckAdapterContext.Builder builder = TckAdapterContext.builder(descriptor).configuration(configuration)
                .platform(platform);
        clientIdentity.ifPresent(builder::clientIdentity);
        return builder.build();
    }

    /**
     * The clients of the HTTP path and their {@code SelectorManager} threads
     * belong to no binding ({@link TckAdapterHttp#ownsThread}). This includes
     * the client of a binding with TLS material, which Java 17 cannot close.
     */
    @Override
    public boolean ownsThread(Thread thread) {
        return TckAdapterHttp.ownsThread(thread.getName());
    }

    @Override
    public String toString() {
        return "ContractHost[TCK]";
    }
}
