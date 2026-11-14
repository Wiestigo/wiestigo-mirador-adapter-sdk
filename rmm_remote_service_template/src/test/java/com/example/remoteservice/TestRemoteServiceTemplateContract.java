package com.example.remoteservice;

import com.rmm.remote.tck.RemoteServiceContractTest;
import com.rmm.remote.tck.RemoteServiceIdentities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The template against the TCK for remote services: the service as an
 * operator starts it, with its key store and a directory holding the two
 * certificates the TCK presents as connections
 * ({@link RemoteServiceIdentities#pinned()}, one PEM file each). All five
 * cases pass. Variants that violate the protocol and each fail exactly their
 * case: {@link TestRemoteServiceTemplateViolations}.
 *
 * <p>For your own service: keep this class and let {@link #startService()}
 * start your service.</p>
 */
public class TestRemoteServiceTemplateContract extends RemoteServiceContractTest {

    private Path pins;
    private RemoteService service;

    @BeforeEach
    public void startService() throws IOException {
        pins = ServiceFixture.pinDirectory(List.of("connection-a", "connection-b"),
                RemoteServiceIdentities.pinned());
        service = newService(ServiceFixture.settings(pinStore(pins))).start();
    }

    @AfterEach
    public void stopService() {
        if (service != null) {
            service.close();
        }
        ServiceFixture.removeDirectory(pins);
    }

    /** The pin store read from the directory; a variant overrides this. */
    protected PinStore pinStore(Path directory) {
        return PinStore.ofDirectory(directory);
    }

    /** The service of the case: the template; a variant overrides this. */
    protected RemoteService newService(ServiceSettings settings) {
        return new RemoteService(settings);
    }

    @Override
    protected String serviceUrl() {
        return service.baseUrl();
    }

    @Override
    protected String serviceAnchor() {
        return ServiceFixture.certificatePem();
    }
}
