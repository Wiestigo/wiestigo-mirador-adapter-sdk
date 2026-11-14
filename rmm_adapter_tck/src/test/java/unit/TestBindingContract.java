package unit;

import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.ToolAdapter;
import com.rmm.adapter.tck.AdapterContractTest;
import com.rmm.adapter.tck.FakeHttpTool;
import com.rmm.adapter.tck.ProcessWideState;
import com.rmm.adapter.tck.TckViolation;
import com.rmm.adapter.tck.reference.InMemoryToolAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.opentest4j.TestAbortedException;
import unit.RowTool.Defect;
import unit.processstate.clean.CleanAdapterClasses;
import unit.processstate.mutable.MutableAdapterClasses;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the binding cases of the TCK: a faithful tool passes every case, and each case detects the defect it
 * exists for. Every check is covered by a defect of its own.
 */
public class TestBindingContract {

    /** The pools of all bindings, so that the test cleans up even after a "threads remain" failure. */
    private final List<ExecutorService> pools = new CopyOnWriteArrayList<>();
    /**
     * The clients of the "HttpClient per binding" fixture, held strongly: otherwise a garbage collection could
     * end their selector thread before the case counts the threads.
     */
    private final List<HttpClient> clients = new CopyOnWriteArrayList<>();

    /** The fake tools of the network cases. */
    private final List<FakeHttpTool> tools = new CopyOnWriteArrayList<>();

    /** What the fake tools of the network cases serve. */
    private final NetToolSpaces spaces = new NetToolSpaces();

    @AfterEach
    public void stopPools() {
        pools.forEach(ExecutorService::shutdownNow);
        clients.clear();
        tools.forEach(FakeHttpTool::close);
    }

    /** A row tool with {@code defect} whose pools and clients this test cleans up. */
    private RowTool rowTool(Defect defect) {
        return new RowTool(defect, pools, clients);
    }

    private AdapterContractTest tckFor(Defect defect) {
        return tckFor(rowTool(defect), Map.of("tool.label", "first-tool"),
                Map.of("tool.label", "second-tool"));
    }

    /** Like a Spring bean: {@code adapter()} always returns the same object. */
    private AdapterContractTest tckFor(RowTool tool, Map<String, String> first, Map<String, String> second) {
        return tckFor(() -> tool, first, second);
    }

    /** With a factory: {@code adapter()} returns a fresh object on every call (recommended for case 1). */
    private AdapterContractTest tckFor(Supplier<RowTool> factory, Map<String, String> first,
                                       Map<String, String> second) {
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return factory.get();
            }

            @Override
            protected Map<String, String> configuration() {
                return first;
            }

            @Override
            protected Optional<Map<String, String>> secondBindingConfiguration() {
                return Optional.of(second);
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "ROW-PRJ"));
            }

            @Override
            protected Optional<ElementArtifact> roundTripArtifact() {
                return Optional.of(ElementArtifact.ofNameAndLabel(new ElementName("Requirement"),
                        new ElementLabel("Requirement")));
            }

            @Override
            protected Set<Class<?>> adapterClasses() {
                return Set.of(RowTool.class, RowWriter.class);
            }
        };
    }

    private static String violationOf(Executable check) {
        return assertThrows(TckViolation.class, check).getMessage();
    }

    // ------------------------------------------------------------------
    // A faithful tool passes every case
    // ------------------------------------------------------------------

    @Test
    public void aFaithfulAdapterPassesEveryBindingCase() {
        AdapterContractTest tck = tckFor(Defect.NONE);

        assertDoesNotThrow(tck::twoBindingsWorkSideBySideWithoutCrossTalk);
        assertDoesNotThrow(tck::theAdapterClassesKeepNoProcessWideState);
        assertDoesNotThrow(tck::closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults);
        assertDoesNotThrow(tck::configurationComesOnlyFromTheContext);
        assertDoesNotThrow(tck::pathKeysAreDeclaredForThePlatformOnly);
        assertDoesNotThrow(tck::aHookEndsSoonAfterTheDeadlineOfItsRun);
        assertDoesNotThrow(tck::aNewBindingReloadsWhatTheClearedCachesHeld);
        assertDoesNotThrow(tck::theRunStoreIsEmptyAfterTheRunAndTheNextRunStartsClean);
        assertDoesNotThrow(tck::purgeChangesNoContentOfTheTool);
        assertDoesNotThrow(tck::everyReadPhaseTerminatesWithSaneCursors);
        assertDoesNotThrow(tck::roundTripCreatesFindsAndDeletesAnArtifact);
        //The in-memory tool has no state that could expire: the case is skipped, not passed
        TestAbortedException skipped = assertThrows(TestAbortedException.class,
                tck::aCursorSurvivesALongPauseBetweenTwoPages);
        assertTrue(skipped.getMessage().contains("no state of the tool that could expire"), skipped.getMessage());
    }

    // ------------------------------------------------------------------
    // Case 1: two bindings side by side
    // ------------------------------------------------------------------

    /** The defect is found even when the page sizes differ and no element carries a marker. */
    @Test
    public void aTypeThatRemembersTheFirstClientIsFoundWithAnyPageSize() {
        for (String pageSize : List.of("2", "3")) {
            String message = violationOf(() -> tckFor(rowTool(Defect.FIRST_CLIENT),
                    Map.of("tool.label", "first-tool", "page.size", "2"),
                    Map.of("tool.label", "second-tool", "page.size", pageSize))
                    .twoBindingsWorkSideBySideWithoutCrossTalk());

            assertTrue(message.contains("The second binding reads the first one's tool"), message);
        }
    }

    @Test
    public void aCursorWrittenIntoTheStoreOfAnotherBindingIsFoundWithoutMarker() {
        String message = violationOf(() -> tckFor(Defect.WRITES_PEER_STORE).twoBindingsWorkSideBySideWithoutCrossTalk());

        assertTrue(message.contains("Binding B changed what binding A holds"), message);
    }

    @Test
    public void theIdOfOneBindingInTheStoreOfAnotherIsCrossTalk() {
        String message = violationOf(() -> tckFor(Defect.WRITES_PEER_ID).twoBindingsWorkSideBySideWithoutCrossTalk());

        assertTrue(message.contains("binding A"), message);
    }

    @Test
    public void readingsThatDriftWhileAnotherBindingIsOpenAreFound() {
        String message = violationOf(() -> tckFor(Defect.DRIFTS_WHEN_OTHERS_ARE_OPEN)
                .twoBindingsWorkSideBySideWithoutCrossTalk());

        assertTrue(message.contains("delivered other results while another binding read in parallel"), message);
    }

    @Test
    public void aCreationVisibleInAnotherBindingIsFound() {
        String message = violationOf(() -> tckFor(Defect.SHARED_CREATIONS).twoBindingsWorkSideBySideWithoutCrossTalk());

        assertTrue(message.contains("changed what binding B reads"), message);
    }

    /** {@code page.size=1000} is no marker, even if B has an element {@code ...-1000-...}. */
    @Test
    public void numbersAndShortValuesAreNoMarkers() {
        assertDoesNotThrow(() -> tckFor(rowTool(Defect.NONE),
                Map.of("tool.label", "first-tool", "page.size", "1000"),
                Map.of("tool.label", "release-tool", "page.size", "1000"))
                .twoBindingsWorkSideBySideWithoutCrossTalk());
        assertDoesNotThrow(() -> tckFor(rowTool(Defect.NONE),
                Map.of("tool.label", "first-tool", "page.size", "1000"),
                Map.of("tool.label", "release-tool"))
                .twoBindingsWorkSideBySideWithoutCrossTalk());
    }

    @Test
    public void aSecondBindingThatReachesTheSameToolCannotProveAnything() {
        String message = violationOf(() -> tckFor(rowTool(Defect.NONE), Map.of("tool.label", "first-tool"),
                Map.of("tool.label", "first-tool", "page.size", "3")).twoBindingsWorkSideBySideWithoutCrossTalk());

        assertTrue(message.contains("secondBindingConfiguration()"), message);
    }

    /** A cache per project in the type is found with a fresh {@code adapter()}. */
    @Test
    public void aCachePerProjectInTheTypeIsFoundWithAFreshAdapter() {
        String message = violationOf(() -> tckFor(() -> rowTool(Defect.PROJECT_CACHE),
                Map.of("tool.label", "first-tool"), Map.of("tool.label", "second-tool"))
                .twoBindingsWorkSideBySideWithoutCrossTalk());

        assertTrue(message.contains("bound after binding A"), message);
    }

    /** One client per address is found when both bindings use the same address. */
    @Test
    public void aClientPerAddressIsFoundOnTheSameAddressWithAnotherAccount() {
        String message = violationOf(() -> tckFor(() -> rowTool(Defect.CLIENT_PER_ADDRESS),
                Map.of("tool.label", "first-tool"), Map.of("tool.label", "second-tool"))
                .twoBindingsWorkSideBySideWithoutCrossTalk());

        assertTrue(message.contains("The second binding reads the first one's tool"), message);
    }

    /** A read that writes into the store of the previous binding is found. */
    @Test
    public void aReadingThatWritesIntoAnotherBindingsStoreIsFound() {
        String message = violationOf(() -> tckFor(Defect.READ_WRITES_PEER_STORE)
                .twoBindingsWorkSideBySideWithoutCrossTalk());

        assertTrue(message.contains("Reading in binding B changed what binding A holds"), message);
    }

    /** The counters of the metrics belong to the binding. */
    @Test
    public void requestsCountedAtAnotherBindingsMetricsAreFound() {
        String message = violationOf(() -> tckFor(Defect.METRICS_OF_LAST_BINDING)
                .twoBindingsWorkSideBySideWithoutCrossTalk());

        assertTrue(message.contains("metrics: requests="), message);
    }

    /** A tool without a fixed order shows no cross-talk. */
    @Test
    public void anUnorderedToolPassesEveryBindingCase() {
        AdapterContractTest tck = tckFor(() -> rowTool(Defect.SHUFFLED), Map.of("tool.label", "first-tool"),
                Map.of("tool.label", "second-tool"));

        assertDoesNotThrow(tck::twoBindingsWorkSideBySideWithoutCrossTalk);
        assertDoesNotThrow(tck::closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults);
        assertDoesNotThrow(tck::configurationComesOnlyFromTheContext);
    }

    @Test
    public void aFaithfulAdapterPassesWithAFreshAdapterToo() {
        AdapterContractTest tck = tckFor(() -> rowTool(Defect.NONE), Map.of("tool.label", "first-tool"),
                Map.of("tool.label", "second-tool"));

        assertDoesNotThrow(tck::twoBindingsWorkSideBySideWithoutCrossTalk);
        assertDoesNotThrow(tck::closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults);
    }

    // ------------------------------------------------------------------
    // Case 3: no process-wide state
    // ------------------------------------------------------------------

    private static Set<Class<?>> classesIn(Class<?> member) {
        return ProcessWideState.classesOf(member).stream()
                .filter(type -> type.getPackageName().equals(member.getPackageName()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    public void constantsImmutableCollectionsStatelessFunctionsAndCompilerArtefactsAreNoProcessWideState() {
        Set<Class<?>> classes = classesIn(CleanAdapterClasses.class);

        assertTrue(classes.size() >= 4, "class, enum, inner, anonymous and switch class: " + classes);
        assertEquals(List.of(), ProcessWideState.violations(classes));
    }

    @Test
    public void everyMutableStaticFieldIsFoundAlsoBehindEnumsFunctionsAndNestedClasses() {
        List<String> violations = ProcessWideState.violations(classesIn(MutableAdapterClasses.class));

        List<String> expected = List.of(".binds ", ".CACHE ", ".LAST_IDS ", ".CLIENT ", ".LOOKUP ", ".COUNTER ",
                ".AUDIT ", "$Holder.SEEN ", "$Registry.entries ");
        assertEquals(expected.size(), violations.size(), String.join("\n", violations));
        for (String field : expected) {
            assertTrue(violations.stream().anyMatch(violation -> violation.contains(field)), field + " in " + violations);
        }
    }

    @Test
    public void staticStateFailsTheCaseWithTheFieldNamed() {
        AdapterContractTest tck = new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return rowTool(Defect.NONE);
            }

            @Override
            protected Set<Class<?>> adapterClasses() {
                return classesIn(MutableAdapterClasses.class);
            }
        };

        String message = violationOf(tck::theAdapterClassesKeepNoProcessWideState);
        assertTrue(message.contains("MutableAdapterClasses.CACHE"), message);
    }

    /** The default is the whole code source of the adapter, without the packages of the TCK and the contract. */
    @Test
    public void theDefaultClassesAreTheWholeCodeSourceWithoutTheTck() {
        Set<Class<?>> ofReference = ProcessWideState.classesOf(InMemoryToolAdapter.class);
        assertTrue(ofReference.contains(InMemoryToolAdapter.class), ofReference.toString());
        assertTrue(ofReference.stream().anyMatch(type -> type.getName().endsWith("$Tool")), ofReference.toString());
        assertTrue(ofReference.stream().noneMatch(type -> type.getPackageName().equals("com.rmm.adapter.tck")),
                ofReference.toString());

        //The test classes share one code source: a helper in another package belongs to it
        Set<Class<?>> ofTests = ProcessWideState.classesOf(CleanAdapterClasses.class);
        assertTrue(ofTests.contains(MutableAdapterClasses.class), "a sibling package of the adapter too");
        assertTrue(ofTests.contains(TestBindingContract.class), "the parent package too");
    }

    // ------------------------------------------------------------------
    // Case 4: close() releases, binding again works
    // ------------------------------------------------------------------

    @Test
    public void threadsThatOutliveCloseAreFoundDaemonsAndHttpClientsIncluded() {
        for (Defect defect : List.of(Defect.KEEPS_THREADS, Defect.KEEPS_DAEMON_THREADS, Defect.KEEPS_HTTP_CLIENT)) {
            String message = violationOf(() -> tckFor(defect).closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults());

            assertTrue(message.contains("close() left threads running"), defect + ": " + message);
        }
    }

    @Test
    public void threadsOfTheFakeToolAreNotTheBindings() throws Exception {
        AdapterContractTest tck = new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                RowTool tool = rowTool(Defect.NONE);
                return new ToolAdapter() {
                    @Override
                    public AdapterDescriptor descriptor() {
                        return tool.descriptor();
                    }

                    @Override
                    public AdapterInstance bind(AdapterContext context) {
                        //A test server that starts a thread per request
                        ExecutorService server = Executors.newSingleThreadExecutor(
                                runnable -> new Thread(runnable, "testserver-worker"));
                        pools.add(server);
                        server.submit(() -> { });
                        return tool.bind(context);
                    }
                };
            }

            @Override
            protected boolean belongsToTheFakeTool(Thread thread) {
                return thread.getName().equals("testserver-worker");
            }
        };

        assertDoesNotThrow(tck::closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults);
    }

    /**
     * A client in the type whose pool grows on demand and a global pool of a library are no leak of the
     * binding: they do not grow with every binding.
     */
    @Test
    public void aClientOfTheTypeWhosePoolGrowsOnDemandIsNoLeak() {
        ExecutorService shared = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "shared-client-worker");
            thread.setDaemon(true);
            return thread;
        });
        pools.add(shared);
        AtomicBoolean libraryStarted = new AtomicBoolean();
        AtomicInteger binds = new AtomicInteger();
        RowTool rows = rowTool(Defect.NONE);
        ToolAdapter typed = new ToolAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                return rows.descriptor();
            }

            @Override
            public AdapterInstance bind(AdapterContext context) {
                //The pool in the type grows on demand: two tasks at once per binding, three for the first
                //binding after the warm-up round. That adds one worker, which stays but does not multiply
                //with every binding
                int parallel = binds.incrementAndGet() == 2 ? 3 : 2;
                CountDownLatch both = new CountDownLatch(parallel);
                for (int task = 0; task < parallel; task++) {
                    shared.submit(() -> {
                        both.countDown();
                        try {
                            both.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
                //A library starts its global event loops on first use
                if (libraryStarted.compareAndSet(false, true)) {
                    ExecutorService loops = Executors.newFixedThreadPool(2, runnable -> {
                        Thread thread = new Thread(runnable, "library-event-loop");
                        thread.setDaemon(true);
                        return thread;
                    });
                    pools.add(loops);
                    loops.submit(() -> { });
                    loops.submit(() -> { });
                }
                return rows.bind(context);
            }
        };
        AdapterContractTest tck = new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return typed;
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "ROW-PRJ"));
            }
        };

        assertDoesNotThrow(tck::closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults);
    }

    @Test
    public void aBindingThatDependsOnWhatThePreviousLeftIsFound() {
        String message = violationOf(() -> tckFor(Defect.DEPENDS_ON_PREVIOUS)
                .closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults());

        assertTrue(message.contains("delivers other results"), message);
    }

    // ------------------------------------------------------------------
    // Case 5: configuration only from the context
    // ------------------------------------------------------------------

    @Test
    public void aSystemPropertyReadInsteadOfConfigIsFoundAndThePropertiesAreRestored() {
        System.setProperty("tool.label", "set-by-the-test");
        try {
            String message = violationOf(() -> tckFor(Defect.READS_PROPERTY).configurationComesOnlyFromTheContext());

            assertTrue(message.contains("rmm.adapter.rowtool.page.size"), message);
            assertNull(System.getProperty("rmm.adapter.rowtool.page.size"), "the trap is removed again");
            assertEquals("set-by-the-test", System.getProperty("tool.label"), "a previous value is restored");
        } finally {
            System.clearProperty("tool.label");
        }
    }

    /** The number trap also works with a large page size. */
    @Test
    public void aNumberFromASystemPropertyIsFoundEvenWithALargePageSize() {
        String message = violationOf(() -> tckFor(rowTool(Defect.READS_PROPERTY),
                Map.of("tool.label", "first-tool", "page.size", "10"), Map.of("tool.label", "second-tool"))
                .configurationComesOnlyFromTheContext());

        assertTrue(message.contains("other results"), message);
    }

    @Test
    public void theBareKeyIsATrapToo() {
        String message = violationOf(() -> tckFor(Defect.READS_BARE_PROPERTY).configurationComesOnlyFromTheContext());

        assertTrue(message.contains("page.size"), message);
        assertNull(System.getProperty("page.size"));
    }

    @Test
    public void aTrapThatOnlyReachesTheLogIsFound() {
        String message = violationOf(() -> tckFor(Defect.LOGS_PROPERTY).configurationComesOnlyFromTheContext());

        assertTrue(message.contains("appears in the log or store"), message);
    }

    /** Keys named like JDK properties are trapped only in the namespace of the adapter. */
    @Test
    public void keysNamedLikeJdkPropertiesAreTrappedOnlyInTheNamespace() throws Exception {
        String proxyBefore = System.getProperty("https.proxyHost");
        RowTool rows = rowTool(Defect.NONE);
        ToolAdapter proxied = new ToolAdapter() {
            @Override
            public AdapterDescriptor descriptor() {
                AdapterDescriptor own = rows.descriptor();
                List<ConfigKey> keys = new ArrayList<>(own.configKeys());
                keys.add(ConfigKey.platform("https.proxyHost", ConfigType.STRING, "proxy.example", "Proxy"));
                return new AdapterDescriptor(own.systemKey(), own.displayName(), own.adapterVersion(),
                        own.apiMajorVersion(), own.capabilities(), keys, own.identifierKinds());
            }

            @Override
            public AdapterInstance bind(AdapterContext context) {
                String proxy = System.getProperty("https.proxyHost");
                if (proxy != null && proxy.startsWith(AdapterContractTest.TRAP_PREFIX)) {
                    throw new IllegalStateException("the JDK proxy was poisoned: " + proxy);
                }
                return rows.bind(context);
            }
        };
        AdapterContractTest tck = new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return proxied;
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "ROW-PRJ"));
            }
        };

        assertDoesNotThrow(tck::configurationComesOnlyFromTheContext);
        assertEquals(proxyBefore, System.getProperty("https.proxyHost"));
    }

    @Test
    public void aPathInScopeBindingIsAViolation() {
        String message = violationOf(() -> tckFor(Defect.PATH_FOR_BINDING).pathKeysAreDeclaredForThePlatformOnly());

        assertTrue(message.contains("export.dir"), message);
    }

    // ------------------------------------------------------------------
    // The host's HTTP path, case 4 (second part), pause and purge(): a tool over HTTP
    // ------------------------------------------------------------------

    /** The rows of the space {@code /first} of the network tool. */
    private static final List<String> FIRST_ROWS = List.of("F-1", "F-2", "F-3", "F-4", "F-5");

    /**
     * The network tool: two spaces ({@code /first} with five rows, {@code /second} with three) whose search
     * sessions {@link FakeHttpTool#expireState()} expires. It answers only requests sent through
     * {@code context.http()}.
     */
    private FakeHttpTool netTool() throws IOException {
        FakeHttpTool started = FakeHttpTool.start();
        tools.add(started);
        spaces.serve(started, "first", FIRST_ROWS);
        spaces.serve(started, "second", List.of("S-1", "S-2", "S-3"));
        started.onExpiry(spaces::expireSessions);
        return started;
    }

    /** Like a Spring bean: the same network tool for all bindings; cases 1 and 2 use two spaces. */
    private AdapterContractTest tckForNet(NetTool.Defect defect) throws IOException {
        FakeHttpTool fake = netTool();
        NetTool net = new NetTool(defect, clients);
        return new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return net;
            }

            @Override
            protected Map<String, String> configuration() {
                return Map.of("tool.url", fake.uri("/first").toString(), "tool.token", "token-first-1234");
            }

            @Override
            protected Optional<Map<String, String>> secondBindingConfiguration() {
                return Optional.of(Map.of("tool.url", fake.uri("/second").toString(),
                        "tool.token", "token-second-5678"));
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "NET-PRJ"));
            }

            @Override
            protected Optional<FakeHttpTool> networkTool() {
                return Optional.of(fake);
            }

            @Override
            protected Duration deadlineGrace() {
                return Duration.ofSeconds(2);
            }

            @Override
            protected Set<Class<?>> adapterClasses() {
                return Set.of(NetTool.class);
            }
        };
    }

    /**
     * The network tool uses {@code runStore()} and {@code executor().submit} in every read hook, as the
     * template recommends. It passes every case because the TCK calls each hook inside a run, as the host does.
     * After all cases the tool has refused no request.
     */
    @Test
    public void aFaithfulNetworkAdapterPassesEveryCase() throws Exception {
        AdapterContractTest tck = tckForNet(NetTool.Defect.NONE);

        assertDoesNotThrow(tck::everyReadPhaseTerminatesWithSaneCursors);
        assertDoesNotThrow(tck::anExplicitIdScopeDeliversTheNamedElements);
        assertDoesNotThrow(tck::everyRequestGoesThroughTheNetworkPathOfItsOwnContext);
        assertDoesNotThrow(tck::twoBindingsWorkSideBySideWithoutCrossTalk);
        assertDoesNotThrow(tck::closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults);
        assertDoesNotThrow(tck::aHookEndsSoonAfterTheDeadlineOfItsRun);
        assertDoesNotThrow(tck::aNewBindingReloadsWhatTheClearedCachesHeld);
        assertDoesNotThrow(tck::theRunStoreIsEmptyAfterTheRunAndTheNextRunStartsClean);
        assertDoesNotThrow(tck::aCursorSurvivesALongPauseBetweenTwoPages);
        assertDoesNotThrow(tck::purgeChangesNoContentOfTheTool);
        assertDoesNotThrow(tck::configurationComesOnlyFromTheContext);
        assertDoesNotThrow(tck::noRequestOfTheCaseBypassedTheNetworkPath);
    }

    // ------------------------------------------------------------------
    // Case 2: network only through context.http()
    // ------------------------------------------------------------------

    @Test
    public void requestsWithAnOwnHttpClientAreFound() throws Exception {
        String message = violationOf(() -> tckForNet(NetTool.Defect.OWN_CLIENT)
                .everyRequestGoesThroughTheNetworkPathOfItsOwnContext());

        assertTrue(message.contains("without the header that only context.http() sets"), message);
    }

    /** The second binding sends through its own HTTP path, but with the token of the first one. */
    @Test
    public void aForeignTokenIsFound() throws Exception {
        String message = violationOf(() -> tckForNet(NetTool.Defect.FOREIGN_TOKEN)
                .everyRequestGoesThroughTheNetworkPathOfItsOwnContext());

        assertTrue(message.contains("a foreign token"), message);
        assertTrue(message.contains("the header Authorization"), message);
        assertTrue(!message.contains("token-first-1234"), "the message never names the secret: " + message);
    }

    /**
     * Case 2 also finds a foreign Basic sign-in in a charset other than UTF-8: the header carries only the
     * Base64 form in ISO-8859-1, which the case knows from {@code http().auth} of the other binding. With its
     * own password per binding the case passes.
     */
    @Test
    public void aForeignBasicSignInInAnotherCharsetIsFound() throws Exception {
        assertDoesNotThrow(() -> tckForNet(NetTool.Defect.OWN_BASIC_LATIN)
                .everyRequestGoesThroughTheNetworkPathOfItsOwnContext());

        String message = violationOf(() -> tckForNet(NetTool.Defect.FOREIGN_BASIC_LATIN)
                .everyRequestGoesThroughTheNetworkPathOfItsOwnContext());

        assertTrue(message.contains("a foreign token"), message);
        assertTrue(message.contains("the header Authorization"), message);
        String foreign = Base64.getEncoder().encodeToString("account:token-first-1234-ä"
                .getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(!message.contains(foreign) && !message.contains("token-first-1234"),
                "the message never names the secret: " + message);
    }

    /**
     * A request that bypasses the host's HTTP path is found after every case, also in {@code purge()}, which
     * case 2 itself does not run, and also when the adapter ignores the refusal.
     */
    @Test
    public void aRequestPastTheNetworkPathInAnyCaseIsFoundAfterTheCase() throws Exception {
        AdapterContractTest tck = tckForNet(NetTool.Defect.PURGE_OWN_CLIENT);
        assertDoesNotThrow(tck::purgeChangesNoContentOfTheTool, "the content stays, the case itself passes");

        String message = violationOf(tck::noRequestOfTheCaseBypassedTheNetworkPath);

        assertTrue(message.contains("1 request(s) reached networkTool() without the header"), message);
    }

    /**
     * A defect while binding, which case 2 itself does not see (it counts from the work of the binding on), is
     * found after the case: requests are counted since the tool started.
     */
    @Test
    public void aRequestPastTheNetworkPathWhileBindingIsFoundAfterCaseTwo() throws Exception {
        AdapterContractTest tck = tckForNet(NetTool.Defect.BIND_OWN_CLIENT);
        assertDoesNotThrow(tck::everyRequestGoesThroughTheNetworkPathOfItsOwnContext,
                "case 2 counts from the work of the binding on");

        String message = violationOf(tck::noRequestOfTheCaseBypassedTheNetworkPath);

        assertTrue(message.contains("request(s) reached networkTool() without the header"), message);
    }

    @Test
    public void aTypeThatKeepsTheNetworkPathOfTheFirstBindingIsFound() throws Exception {
        String message = violationOf(() -> tckForNet(NetTool.Defect.FIRST_HTTP)
                .everyRequestGoesThroughTheNetworkPathOfItsOwnContext());

        assertTrue(message.contains("through the network path of the other binding"), message);
    }

    /** An adapter without network skips case 2 explicitly (skipped, not passed); an opt-out names its reason. */
    @Test
    public void anAdapterWithoutNetworkSkipsCaseTwoAndAnOptOutNamesItsReason() {
        assertThrows(TestAbortedException.class,
                tckFor(Defect.NONE)::everyRequestGoesThroughTheNetworkPathOfItsOwnContext);
        AdapterContractTest optedOut = new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return rowTool(Defect.NONE);
            }

            @Override
            protected Optional<String> networkOptOut() {
                return Optional.of("own client, not yet migrated");
            }
        };
        TestAbortedException skipped = assertThrows(TestAbortedException.class,
                optedOut::everyRequestGoesThroughTheNetworkPathOfItsOwnContext);
        assertTrue(skipped.getMessage().contains("own client, not yet migrated"), skipped.getMessage());
    }

    // ------------------------------------------------------------------
    // Case 4, second part: deadline, caches, run store, resources without a thread
    // ------------------------------------------------------------------

    /** The deadline grace has an upper bound; a test may only shorten it. */
    @Test
    public void aDeadlineGraceAboveTheMaximumFailsTheCase() throws Exception {
        FakeHttpTool fake = netTool();
        NetTool net = new NetTool(NetTool.Defect.NONE, clients);
        AdapterContractTest patient = new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return net;
            }

            @Override
            protected Map<String, String> configuration() {
                return Map.of("tool.url", fake.uri("/first").toString());
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "NET-PRJ"));
            }

            @Override
            protected Duration deadlineGrace() {
                return Duration.ofMinutes(10);
            }
        };

        String message = violationOf(patient::aHookEndsSoonAfterTheDeadlineOfItsRun);

        assertTrue(message.contains("at most 5 s after the deadline"), message);
    }

    @Test
    public void aHookThatIgnoresTheDeadlineIsFound() throws Exception {
        String message = violationOf(() -> tckForNet(NetTool.Defect.IGNORES_DEADLINE)
                .aHookEndsSoonAfterTheDeadlineOfItsRun());

        assertTrue(message.contains("after the deadline of its run"), message);
    }

    @Test
    public void aCacheThatIsNotReloadedAfterClearingIsFound() throws Exception {
        String message = violationOf(() -> tckForNet(NetTool.Defect.NO_CACHE_RELOAD)
                .aNewBindingReloadsWhatTheClearedCachesHeld());

        assertTrue(message.contains("After the caches were cleared"), message);
    }

    @Test
    public void stateOfARunInAFieldIsFound() throws Exception {
        String message = violationOf(() -> tckForNet(NetTool.Defect.RUN_STATE_IN_FIELD)
                .theRunStoreIsEmptyAfterTheRunAndTheNextRunStartsClean());

        assertTrue(message.contains("A second run of the same binding"), message);
    }

    @Test
    public void aResponseStreamLeftOpenIsFound() throws Exception {
        String message = violationOf(() -> tckForNet(NetTool.Defect.LEAKS_STREAM)
                .closeReleasesAndTheSameConnectionBindsAgainWithTheSameResults());

        assertTrue(message.contains("response stream(s) of http().open(...) open"), message);
    }

    // ------------------------------------------------------------------
    // Pause between two pages and case 7: purge()
    // ------------------------------------------------------------------

    @Test
    public void aCursorThatHoldsOnlyAServerSideIdIsFound() throws Exception {
        AdapterContractTest tck = tckForNet(NetTool.Defect.SERVER_SIDE_CURSOR);
        assertDoesNotThrow(tck::theRunStoreIsEmptyAfterTheRunAndTheNextRunStartsClean,
                "without a pause the session cursor reads everything");

        String message = violationOf(tck::aCursorSurvivesALongPauseBetweenTwoPages);

        assertTrue(message.contains("After a long pause between two pages"), message);
    }

    @Test
    public void aPurgeThatDeletesContentIsFound() throws Exception {
        String message = violationOf(() -> tckForNet(NetTool.Defect.PURGE_DELETES).purgeChangesNoContentOfTheTool());

        assertTrue(message.contains("purge() changed the content of the tool"), message);
    }

    /** A deletion in another project space, which the second binding reads, is found too. */
    @Test
    public void aPurgeThatDeletesContentElsewhereIsFound() throws Exception {
        String message = violationOf(() -> tckForNet(NetTool.Defect.PURGE_DELETES_ELSEWHERE)
                .purgeChangesNoContentOfTheTool());

        assertTrue(message.contains("read through secondBindingConfiguration()"), message);
    }

    /**
     * If {@link FakeHttpTool#expireState()} expires nothing (no {@code onExpiry}), the pause fails instead of
     * passing silently; a read with a single page is skipped.
     */
    @Test
    public void aPauseWithoutStateThatExpiresFailsAndASinglePageIsSkipped() throws Exception {
        FakeHttpTool stateless = FakeHttpTool.start();
        tools.add(stateless);
        spaces.serve(stateless, "first", FIRST_ROWS);
        NetTool net = new NetTool(NetTool.Defect.NONE, clients);
        Function<String, AdapterContractTest> pausing = pageSize -> new AdapterContractTest() {
            @Override
            protected ToolAdapter adapter() {
                return net;
            }

            @Override
            protected Map<String, String> configuration() {
                return Map.of("tool.url", stateless.uri("/first").toString(), "page.size", pageSize);
            }

            @Override
            protected Optional<SyncRunContext> liveRunContext() {
                return Optional.of(new SyncRunContext(UUID.randomUUID(), "NET-PRJ"));
            }

            @Override
            protected Optional<FakeHttpTool> networkTool() {
                return Optional.of(stateless);
            }
        };

        String message = violationOf(pausing.apply("2")::aCursorSurvivesALongPauseBetweenTwoPages);
        assertTrue(message.contains("registers none (FakeHttpTool.onExpiry)"), message);
        TestAbortedException single = assertThrows(TestAbortedException.class,
                pausing.apply("50")::aCursorSurvivesALongPauseBetweenTwoPages);
        assertTrue(single.getMessage().contains("the read has only one page"), single.getMessage());
    }
}
