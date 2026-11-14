package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.reference.InMemoryToolAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shows how the contract cases use a {@link ContractHost}: they call the hooks through
 * {@link ContractContext#hosted} and read pages through {@link ContractContext#paging()}, and a context
 * whose {@code release()} leaves the HTTP path open is reported as a defect of the context. The class
 * lives in the TCK's package because only the TCK and the host may supply another {@link ContractHost}.
 */
public class TestContractHostChecks {

    /** What a host changes on the TCK's context; a {@code null} result delegates the call to that context. */
    private interface Change {
        Object apply(ContractContext context, String method, Object[] args) throws Exception;
    }

    /** A host on top of the TCK that applies {@code change} to its contexts. */
    private static ContractHost hostWith(Change change) {
        return new ContractHost() {
            @Override
            public ContractContext context(AdapterDescriptor descriptor, Map<String, String> configuration,
                                           Map<String, String> platform) {
                ContractContext tck = ContractHost.tck().context(descriptor, configuration, platform);
                return (ContractContext) Proxy.newProxyInstance(ContractContext.class.getClassLoader(),
                        new Class<?>[] {ContractContext.class}, (proxy, method, args) -> {
                            Object changed = change.apply(tck, method.getName(), args);
                            if (changed != null) {
                                return changed;
                            }
                            try {
                                return method.invoke(tck, args);
                            } catch (InvocationTargetException failed) {
                                throw failed.getCause();
                            }
                        });
            }

            @Override
            public boolean ownsThread(Thread thread) {
                return ContractHost.tck().ownsThread(thread);
            }

            @Override
            public String toString() {
                return "ContractHost[changed]";
            }
        };
    }

    /** The in-memory reference adapter, run against a host on top of the TCK. */
    private static final class Suite extends AdapterContractTest {

        private final ContractHost host;

        private Suite(ContractHost host) {
            this.host = host;
        }

        @Override
        protected ToolAdapter adapter() {
            return new InMemoryToolAdapter();
        }

        @Override
        protected Optional<SyncRunContext> liveRunContext() {
            return Optional.of(new SyncRunContext(UUID.randomUUID(), "MEM-PRJ"));
        }

        @Override
        protected Optional<String> networkOptOut() {
            return Optional.of("the reference tool lives in memory");
        }

        @Override
        protected ContractHost contractHost() {
            return host;
        }
    }

    @Test
    public void aReleaseThatLeavesTheNetworkPathOpenIsADefectOfTheContext() {
        Suite suite = new Suite(hostWith((context, method, args) -> method.equals("release") ? "kept" : null));
        assertDoesNotThrow(suite::theCasesRunAgainstTheTckOrTheHostOfTheRmm, "the TCK's own package may");

        TckViolation violation = assertThrows(TckViolation.class,
                suite::closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults);
        assertTrue(violation.getMessage().contains("did not close the network path on release"),
                violation.getMessage());
    }

    @Test
    public void theCasesCallTheHooksThroughTheHostAndReadPagesThroughItsPaging() {
        AtomicInteger hosted = new AtomicInteger();
        AtomicInteger pages = new AtomicInteger();
        Suite suite = new Suite(hostWith((context, method, args) -> {
            if (method.equals("hosted")) {
                hosted.incrementAndGet();
                return args[0];
            }
            if (method.equals("paging")) {
                ReadContract.Paging sequential = ReadContract.Paging.sequential();
                return new ReadContract.Paging() {
                    @Override
                    public <T> ReadContract.Pages<T> pages(String phaseName, ReadContract.Phase<T> phase,
                                                           SyncRunContext run, ReadScope scope) {
                        pages.incrementAndGet();
                        return sequential.pages(phaseName, phase, run, scope);
                    }
                };
            }
            return null;
        }));

        assertDoesNotThrow(suite::everyReadPhaseTerminatesWithSaneCursors);
        assertEquals(1, hosted.get(), "one binding, its ports through the host");
        assertTrue(pages.get() >= 1, "the phases read their pages through the host");
    }

    @Test
    public void theFirstCaseChecksTheInstanceItself() {
        AtomicInteger hosted = new AtomicInteger();
        Suite suite = new Suite(hostWith((context, method, args) -> {
            if (method.equals("hosted")) {
                hosted.incrementAndGet();
                return (AdapterInstance) args[0];
            }
            return null;
        }));

        assertDoesNotThrow(suite::bindYieldsAnInstanceConsistentWithTheCapabilities);
        assertEquals(0, hosted.get(), "the declared phases are checked on the instance, before any hull");
    }
}
