package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.reference.InMemoryToolAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    private static class Suite extends AdapterContractTest {

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

    /**
     * Every read of a phase in the cases that write goes through the host's paging: the host's read-ahead
     * is then under test there too. Counted on both sides: the phases the paging hands out and the first
     * pages the reader is asked for.
     */
    @Test
    public void theCasesThatWriteReadEveryPhaseThroughThePagingOfTheHost() throws Throwable {
        AtomicInteger pages = new AtomicInteger();
        AtomicInteger firstPages = new AtomicInteger();
        ContractHost host = hostWith((context, method, args) -> {
            if (method.equals("hosted")) {
                return countingFirstPages((AdapterInstance) args[0], firstPages);
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
        });
        Suite suite = new Suite(host) {
            @Override
            protected Optional<ElementArtifact> roundTripArtifact() {
                ElementArtifact artifact = ElementArtifact.ofNameAndLabel(
                        new ElementName("Requirement"), new ElementLabel("Requirement"));
                artifact.addField(new ElementFieldString(new ElementName("title"), new ElementLabel("Title"),
                        "Brake"));
                artifact.addField(new ElementFieldString(new ElementName("status"), new ElementLabel("Status"),
                        "Draft"));
                return Optional.of(artifact);
            }

            @Override
            protected Optional<String> roundTripRelationshipType() {
                return Optional.of("relates");
            }
        };
        Map<String, Executable> cases = new LinkedHashMap<>();
        cases.put("roundTripCreatesFindsAndDeletesAnArtifact", suite::roundTripCreatesFindsAndDeletesAnArtifact);
        cases.put("aDeclaredPartialUpdateLeavesTheOtherFieldsUntouched",
                suite::aDeclaredPartialUpdateLeavesTheOtherFieldsUntouched);
        cases.put("updateAndDeleteAreIdempotent", suite::updateAndDeleteAreIdempotent);
        cases.put("parallelUpdatesOfOneElementAreSafe", suite::parallelUpdatesOfOneElementAreSafe);
        cases.put("relationshipChangesFollowTheDeclaredUpdatePath",
                suite::relationshipChangesFollowTheDeclaredUpdatePath);
        cases.put("declaredArtifactBulkHooksAnswerOneOutcomePerElementInOrder",
                suite::declaredArtifactBulkHooksAnswerOneOutcomePerElementInOrder);
        cases.put("variabilityRoundTripMirrorsTheSelectionOfAnElement",
                suite::variabilityRoundTripMirrorsTheSelectionOfAnElement);

        List<String> ran = new ArrayList<>();
        for (Map.Entry<String, Executable> contractCase : cases.entrySet()) {
            pages.set(0);
            firstPages.set(0);
            try {
                contractCase.getValue().execute();
            } catch (TestAbortedException notDeclared) {
                continue;
            }
            ran.add(contractCase.getKey());
            assertTrue(firstPages.get() >= 1, contractCase.getKey() + " reads");
            assertEquals(firstPages.get(), pages.get(), contractCase.getKey()
                    + ": every phase the reader starts was handed out by the paging of the host");
        }
        assertTrue(ran.size() >= 3, "the reference adapter runs cases that write: " + ran);
    }

    /** The instance with a reader that counts the first page of every phase it is asked for. */
    private static AdapterInstance countingFirstPages(AdapterInstance instance, AtomicInteger firstPages) {
        return (AdapterInstance) Proxy.newProxyInstance(AdapterInstance.class.getClassLoader(),
                new Class<?>[] {AdapterInstance.class}, (proxy, method, args) -> {
                    Object result = invoke(method, instance, args);
                    if (!method.getName().equals("reader") || ((Optional<?>) result).isEmpty()) {
                        return result;
                    }
                    SourceReader reader = (SourceReader) ((Optional<?>) result).get();
                    return Optional.of(Proxy.newProxyInstance(SourceReader.class.getClassLoader(),
                            new Class<?>[] {SourceReader.class}, (readerProxy, read, readArgs) -> {
                                Class<?>[] parameters = read.getParameterTypes();
                                if (parameters.length > 0 && parameters[parameters.length - 1] == PageCursor.class
                                        && readArgs[readArgs.length - 1] == null) {
                                    firstPages.incrementAndGet();
                                }
                                return invoke(read, reader, readArgs);
                            }));
                });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failed) {
            throw failed.getCause();
        }
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
