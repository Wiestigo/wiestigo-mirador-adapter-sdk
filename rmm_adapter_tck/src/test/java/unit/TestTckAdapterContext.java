package unit;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.tck.TckAdapterContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test context resolves configuration by the rules of the host: only declared keys, defaults for optional
 * keys, and missing required keys fail before initialization with a clear message.
 */
public class TestTckAdapterContext {

    private AdapterDescriptor descriptor(List<ConfigKey> configKeys) {
        return new AdapterDescriptor("ctxtool", "Contexttool", "1.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
                        false, false, false, false, com.rmm.adapter.api.SyncPhase.all(), java.util.Set.of(), Set.of(), null),
                configKeys, List.of());
    }

    @Test
    public void resolvesConfiguredValuesAndDefaultsTyped() {
        TckAdapterContext context = TckAdapterContext.of(descriptor(List.of(
                        ConfigKey.required("home.url", ConfigType.STRING, "Base URL"),
                        ConfigKey.optional("page.size", ConfigType.INTEGER, "50", "Page size"))),
                Map.of("home.url", "https://tool.example"));

        assertEquals("https://tool.example", context.config().string("home.url"));
        assertEquals(50, context.config().integer("page.size"), "the default of the optional key");
    }

    @Test
    public void missingRequiredKeysFailBeforeInitialisationWithTheirNames() {
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                TckAdapterContext.of(descriptor(List.of(
                        ConfigKey.required("home.url", ConfigType.STRING, "Base URL"))), Map.of()));

        assertTrue(thrown.getMessage().contains("home.url"),
                "the message names the missing key: " + thrown.getMessage());
    }

    @Test
    public void undeclaredKeysAreAProgrammingErrorOfTheAdapter() {
        TckAdapterContext context = TckAdapterContext.of(descriptor(List.of()), Map.of());

        assertThrows(IllegalArgumentException.class, () -> context.config().string("not.declared"));
    }

    @Test
    public void theStoreKeepsValuesForTheLifetimeOfTheContext() {
        TckAdapterContext context = TckAdapterContext.of(descriptor(List.of()), Map.of());

        context.store().write("cursor", "42");
        assertEquals("42", context.store().read("cursor").orElseThrow());
        context.store().delete("cursor");
        assertTrue(context.store().read("cursor").isEmpty());
    }

    /** Every context has a random identifier of its own; log and store can be inspected for cases 1 and 5. */
    @Test
    public void everyContextIsABindingOfItsOwnAndKeepsWhatTheAdapterLeft() {
        TckAdapterContext first = TckAdapterContext.of(descriptor(List.of()), Map.of());
        TckAdapterContext second = TckAdapterContext.of(descriptor(List.of()), Map.of());
        TckAdapterContext given = TckAdapterContext.of(descriptor(List.of()), Map.of(), "binding-1");

        assertTrue(!first.binding().bindingId().equals(second.binding().bindingId()), "random per context");
        assertEquals("binding-1", given.binding().bindingId());

        first.logger().info("bound");
        first.logger().warn("slow");
        first.store().write("cursor", "7");
        assertEquals(List.of("bound", "slow"), first.logLines());
        assertEquals(Map.of("cursor", "7"), first.storeEntries());
        assertEquals(List.of(), second.logLines(), "the other binding logged nothing");
        assertEquals(Map.of(), second.storeEntries());
    }

    // ------------------------------------------------------------------
    // Configuration by the rules of the host
    // ------------------------------------------------------------------

    private AdapterDescriptor typedDescriptor() {
        return descriptor(List.of(
                ConfigKey.optional("page.size", ConfigType.INTEGER, "50", "Page size"),
                ConfigKey.platform("page.size.max", ConfigType.INTEGER, "100", "Upper limit of the page size"),
                ConfigKey.platform("export.dir", ConfigType.PATH, "/tmp/tck", "Export directory"),
                ConfigKey.optional("tool.url", ConfigType.URL, "https://tool.test", "Address"),
                ConfigKey.optional("tool.token", ConfigType.SECRET, "", "Access token"),
                ConfigKey.optional("tool.anchor", ConfigType.CERTIFICATE, "", "Trust anchor")));
    }

    @Test
    public void whatTheHostRefusesToSaveForAConnectionTheContextRefusesToBuild() {
        IllegalArgumentException platform = assertThrows(IllegalArgumentException.class,
                () -> TckAdapterContext.of(typedDescriptor(), Map.of("export.dir", "/srv/export")));
        assertTrue(platform.getMessage().contains("scope PLATFORM"), platform.getMessage());
        IllegalArgumentException cap = assertThrows(IllegalArgumentException.class,
                () -> TckAdapterContext.of(typedDescriptor(), Map.of("page.size.max", "500")));
        assertTrue(cap.getMessage().contains("platform limit"), cap.getMessage());
        IllegalArgumentException above = assertThrows(IllegalArgumentException.class,
                () -> TckAdapterContext.of(typedDescriptor(), Map.of("page.size", "500")));
        assertTrue(above.getMessage().contains("above the platform limit of 100"), above.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> TckAdapterContext.of(typedDescriptor(), Map.of("page.size", "many")));
        IllegalArgumentException address = assertThrows(IllegalArgumentException.class,
                () -> TckAdapterContext.of(typedDescriptor(), Map.of("tool.url", "https://user:secret99@tool.test")));
        assertFalse(address.getMessage().contains("secret99"), "no value in the message: " + address.getMessage());
        IllegalArgumentException anchor = assertThrows(IllegalArgumentException.class,
                () -> TckAdapterContext.of(typedDescriptor(), Map.of("tool.anchor", "no PEM")));
        assertTrue(anchor.getMessage().contains("not a valid PEM certificate"), anchor.getMessage());
    }

    /** The host holds the client identity; it never stores a value for it. */
    @Test
    public void aValueForTheClientIdentityIsRefusedLikeTheHostRefusesIt() {
        AdapterDescriptor withIdentity = descriptor(List.of(
                ConfigKey.optional("tls.client", ConfigType.CLIENT_IDENTITY, "", "Client identity")));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TckAdapterContext.of(withIdentity, Map.of("tls.client", "-----BEGIN CERTIFICATE-----x")));

        assertTrue(refused.getMessage().contains("CLIENT_IDENTITY"), refused.getMessage());
        assertFalse(refused.getMessage().contains("BEGIN CERTIFICATE"), refused.getMessage());
        TckAdapterContext.builder(withIdentity).clientIdentity(TckPemFixtures.clientWithKey()).build();
    }

    /**
     * A {@code CLIENT_IDENTITY} key is never a missing value, because it has none that could be set. Without an
     * identity, however, the TCK builds no context, as the host does not bind such a type without one
     * ({@code NOT_CONFIGURED ACCESS_NOT_CONFIGURED}, before the adapter's {@code bind}). With an identity the
     * adapter reads only its certificate.
     */
    @Test
    public void aClientIdentityIsNeverAMissingValueButWithoutOneTheContextIsNotBuilt() {
        AdapterDescriptor required = descriptor(List.of(
                ConfigKey.required("tls.client", ConfigType.CLIENT_IDENTITY, "Client identity")));
        AdapterDescriptor optional = descriptor(List.of(
                ConfigKey.optional("tls.client", ConfigType.CLIENT_IDENTITY, "", "Client identity")));

        for (AdapterDescriptor withIdentity : List.of(required, optional)) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> TckAdapterContext.of(withIdentity, Map.of()));
            assertTrue(refused.getMessage().contains("has no client identity"), refused.getMessage());
            assertFalse(refused.getMessage().contains("Missing required configuration"), refused.getMessage());

            TckAdapterContext context = TckAdapterContext.builder(withIdentity)
                    .clientIdentity(TckPemFixtures.clientWithKey()).build();
            String shown = context.config().string("tls.client");
            assertTrue(shown.contains("BEGIN CERTIFICATE") && !shown.contains("PRIVATE KEY"), "only the certificate");
            assertEquals(1, context.config().certificate("tls.client").size());
        }
    }

    /** The deployment does not set the client identity either: the host reads no value from there. */
    @Test
    public void aDeploymentValueForTheClientIdentityIsRefused() {
        AdapterDescriptor withIdentity = descriptor(List.of(
                ConfigKey.optional("tls.client", ConfigType.CLIENT_IDENTITY, "", "Client identity")));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> TckAdapterContext.builder(withIdentity)
                        .platform(Map.of("tls.client", "-----BEGIN CERTIFICATE-----x")).build());

        assertTrue(refused.getMessage().contains("CLIENT_IDENTITY"), refused.getMessage());
        assertFalse(refused.getMessage().contains("BEGIN CERTIFICATE"), refused.getMessage());
        TckAdapterContext.builder(withIdentity).platform(Map.of("tls.client", " "))
                .clientIdentity(TckPemFixtures.clientWithKey()).build();
    }

    /**
     * A client identity in scope PLATFORM or with a default is a defect that the host reports when it loads
     * the type; the TCK refuses to build the context.
     */
    @Test
    public void aClientIdentityInScopePlatformOrWithADefaultIsRefused() {
        for (ConfigKey wrong : List.of(
                ConfigKey.platform("tls.client", ConfigType.CLIENT_IDENTITY, null, "Client identity"),
                ConfigKey.platform("tls.client", ConfigType.CLIENT_IDENTITY, "", "Client identity"),
                ConfigKey.optional("tls.client", ConfigType.CLIENT_IDENTITY, "-----BEGIN CERTIFICATE-----x",
                        "Client identity"))) {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> TckAdapterContext.of(descriptor(List.of(wrong)), Map.of()), wrong.toString());
            assertTrue(refused.getMessage().contains("<tls.client>"), refused.getMessage());
            assertTrue(refused.getMessage().contains("CLIENT_IDENTITY"), refused.getMessage());
            assertTrue(refused.getMessage().contains("scope BINDING"), refused.getMessage());
            assertFalse(refused.getMessage().contains("BEGIN CERTIFICATE"), refused.getMessage());
        }
        TckAdapterContext.builder(descriptor(List.of(
                ConfigKey.required("tls.client", ConfigType.CLIENT_IDENTITY, "Client identity"))))
                .clientIdentity(TckPemFixtures.clientWithKey()).build();
    }

    @Test
    public void platformKeysComeOnlyFromTheDeploymentAndCapTheLimitOfTheConnection() {
        TckAdapterContext deployed = TckAdapterContext.builder(typedDescriptor())
                .configuration(Map.of("page.size", "300"))
                .platform(Map.of("page.size.max", "500", "export.dir", "/srv/export"))
                .build();
        assertEquals(300, deployed.config().limit("page.size"));
        assertEquals(java.nio.file.Path.of("/srv/export"), deployed.config().path("export.dir"));

        TckAdapterContext capped = TckAdapterContext.builder(typedDescriptor())
                .platform(Map.of("page.size.max", "20")).build();
        assertEquals(20, capped.config().limit("page.size"), "the default 50 of the connection is capped at 20");
        assertEquals(50, capped.config().integer("page.size"), "integer() reads the value as it is");
        assertThrows(IllegalArgumentException.class, () -> TckAdapterContext.builder(typedDescriptor())
                .configuration(Map.of("page.size", "30")).platform(Map.of("page.size.max", "20")).build());
    }

    @Test
    public void typedAccessorsRequireTheDeclaredType() {
        var config = TckAdapterContext.of(typedDescriptor(), Map.of("tool.token", "tok-123456")).config();

        assertThrows(IllegalArgumentException.class, () -> config.url("page.size"));
        assertThrows(IllegalArgumentException.class, () -> config.secret("tool.url"));
        assertThrows(IllegalArgumentException.class, () -> config.limit("tool.url"));
        assertThrows(IllegalArgumentException.class, () -> config.certificate("tool.token"));
        assertEquals(java.net.URI.create("https://tool.test"), config.url("tool.url"));
        assertEquals("tok-123456", config.secret("tool.token").reveal());
        assertEquals(com.rmm.adapter.api.HostLimits.unknown(), config.host());
    }

    @Test
    public void aCertificateKeepsItsPrivateKeyInTheTck() {
        String anchor = TckPemFixtures.anchor();
        String client = TckPemFixtures.clientWithKey();
        var withAnchor = TckAdapterContext.of(typedDescriptor(), Map.of("tool.anchor", anchor)).config();
        var withKey = TckAdapterContext.of(typedDescriptor(), Map.of("tool.anchor", client)).config();

        assertEquals(1, withAnchor.certificate("tool.anchor").size());
        assertTrue(withAnchor.certificate("tool.anchor").get(0).getSubjectX500Principal().getName()
                .contains("TCK Test CA"), "the anchor is a certificate authority of its own");
        assertTrue(withAnchor.certificate("tool.anchor").get(0).getBasicConstraints() >= 0, "a CA certificate");
        assertEquals(1, withKey.certificate("tool.anchor").size(), "only the certificate, never the key");
        assertTrue(withKey.certificate("tool.anchor").get(0).getSubjectX500Principal().getName()
                .contains("tool.test"));
        assertFalse(withKey.toString().contains("PRIVATE"), withKey.toString());
    }

    /**
     * A secret of the connection never comes from the deployment (the host serves several tenants), and a
     * value of the connection has at most as many characters as the store of the host keeps; a certificate
     * may be longer.
     */
    @Test
    public void secretsOfTheConnectionNeverComeFromTheDeploymentAndValuesKeepTheLengthOfTheHostStore() {
        AdapterDescriptor withNamedSecret = descriptor(List.of(
                ConfigKey.optional("tool.token", ConfigType.SECRET, "", "Access token"),
                ConfigKey.optional("tool.anchor", ConfigType.CERTIFICATE, "", "Trust anchor"),
                ConfigKey.optional("hook.api.key", ConfigType.STRING, "", "Key of the webhook"),
                ConfigKey.optional("tool.label", ConfigType.STRING, "", "Label")));
        for (String key : List.of("tool.token", "tool.anchor", "hook.api.key")) {
            String value = key.equals("tool.anchor") ? TckPemFixtures.anchor() : "secret-123456";
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> TckAdapterContext.builder(withNamedSecret).platform(Map.of(key, value)).build(), key);
            assertTrue(refused.getMessage().contains("never reads it from the deployment"), refused.getMessage());
            assertFalse(refused.getMessage().contains("secret-123456"), refused.getMessage());
        }
        TckAdapterContext labelled = TckAdapterContext.builder(withNamedSecret)
                .platform(Map.of("tool.label", "from-the-operator")).build();
        assertEquals("from-the-operator", labelled.config().string("tool.label"),
                "an ordinary key of the connection still falls back to the deployment");

        assertThrows(IllegalArgumentException.class, () -> TckAdapterContext.of(withNamedSecret,
                Map.of("tool.label", "x".repeat(4001))), "the host store keeps at most 4000 characters");
        TckAdapterContext.of(withNamedSecret, Map.of("tool.label", "x".repeat(4000)));
        String longChain = TckPemFixtures.anchor() + "comment between the blocks\n".repeat(300)
                + TckPemFixtures.anchor();
        assertTrue(longChain.length() > 4000);
        assertEquals(2, TckAdapterContext.of(withNamedSecret, Map.of("tool.anchor", longChain)).config()
                .certificate("tool.anchor").size(), "a certificate may be longer");
    }

    @Test
    public void secretsAreMaskedInTheLogAndShownNowhere() {
        TckAdapterContext context = TckAdapterContext.of(typedDescriptor(), Map.of("tool.token", "tok-123456"));

        String token = context.config().secret("tool.token").reveal();
        context.logger().info("calling with " + token);

        assertEquals(List.of("calling with ****"), context.logLines());
        assertFalse(context.toString().contains(token), context.toString());
        assertFalse(context.config().toString().contains(token), context.config().toString());
        assertFalse(context.http().toString().contains(token), context.http().toString());
    }

    // ------------------------------------------------------------------
    // Stores, run, executor() and release
    // ------------------------------------------------------------------

    @Test
    public void theRunStoreBelongsToItsRunAndIsEmptiedAtItsEnd() {
        TckAdapterContext context = TckAdapterContext.of(descriptor(List.of()), Map.of());
        assertThrows(IllegalStateException.class, context::runStore, "outside a run");
        com.rmm.adapter.api.SyncRunContext run = new com.rmm.adapter.api.SyncRunContext(java.util.UUID.randomUUID(),
                "PRJ");

        com.rmm.adapter.api.DocumentCollection held;
        TckAdapterContext.Run ended;
        try (TckAdapterContext.Run started = context.startRun(run)) {
            ended = started;
            held = context.runStore().collection("items");
            held.put("i-1", "{\"n\":1}");
            held.put("i-2", "{\"n\":2}");
            assertEquals(run.runId(), context.runStore().runId());
            assertEquals(2, context.runStoreDocuments(run.runId()));
            assertEquals(2, started.documents());
            assertEquals(run.runId(), context.runStore(run).runId(), "named from another thread");
        }

        assertEquals(0, ended.documents(), "the collections of the run are emptied at its end");
        assertEquals(0, context.runStoreDocuments(run.runId()), "emptied at the end of the run");
        assertThrows(IllegalStateException.class, () -> held.get("i-1"), "a held collection ends with the run");
        assertThrows(IllegalStateException.class, () -> context.runStore(run), "the run is no longer active");
        assertThrows(IllegalStateException.class, context::runStore, "the thread is no longer in the run");
    }

    @Test
    public void documentCachesLastUntilClearedAndMemoryCachesStartAnewWithEveryBinding() {
        TckAdapterContext context = TckAdapterContext.of(descriptor(List.of()), Map.of());
        context.caches().collection("labels").put("all", "{\"a\":\"b\"}");
        com.rmm.adapter.api.MemoryCache<String> memory = context.caches().memory("recent", 2);
        memory.put("1", "one");
        memory.put("2", "two");
        memory.get("1");
        memory.put("3", "three");
        assertEquals(2, memory.size());
        assertTrue(memory.get("2").isEmpty(), "the least recently used entry gave way");
        assertTrue(context.caches().<String>memory("recent", 2) == memory, "the same name, the same cache");

        context.release();

        assertTrue(context.caches().collection("labels").get("all").isPresent(), "documents outlive a binding");
        assertEquals(0, context.caches().memory("recent", 2).size(), "a new binding starts with empty memory");
        context.clearCaches();
        assertTrue(context.caches().collection("labels").get("all").isEmpty(), "cleared by the administrator");
        com.rmm.adapter.api.MemoryCache<Integer> large = context.caches().memory("large", 50_000);
        for (int entry = 0; entry <= TckAdapterContext.MEMORY_CACHE_LIMIT; entry++) {
            large.put("k" + entry, entry);
        }
        assertEquals(TckAdapterContext.MEMORY_CACHE_LIMIT, large.size(), "capped at the platform limit");
    }

    @Test
    public void submitWorksOnlyInARunCarriesTheRunAndEndsWithTheBinding() throws Exception {
        TckAdapterContext context = TckAdapterContext.of(descriptor(List.of()), Map.of(), "binding-exec");
        assertThrows(IllegalStateException.class, () -> context.executor().submit(() -> 1), "outside a run");
        assertEquals(TckAdapterContext.DEFAULT_PARALLELISM, context.executor().parallelism());
        com.rmm.adapter.api.SyncRunContext run = new com.rmm.adapter.api.SyncRunContext(java.util.UUID.randomUUID(),
                "PRJ");

        String seen = context.inRun(run, () -> context.executor().submit(() -> context.runStore().runId() + " "
                + context.http().currentRun().orElseThrow().runId() + " " + Thread.currentThread().getName()).get());

        assertTrue(seen.startsWith(run.runId() + " " + run.runId() + " rmm-tck-executor-binding-"), seen);
        assertTrue(alive("rmm-tck-executor-binding-"), "the pool of the binding stays until it is released");
        context.release();
        //release() waits until the pool has terminated; a thread reports that shortly before it ends itself. The
        //threads of the pool have no idle timeout: without release() they would keep running, so the wait is bounded
        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (alive("rmm-tck-executor-binding-") && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertFalse(alive("rmm-tck-executor-binding-"), "release() ends the threads of executor()");
    }

    /**
     * At the end of a run, tasks of the run that still wait are cancelled; a running task finishes and is not
     * interrupted, as in the host.
     */
    @Test
    public void atTheEndOfARunWaitingTasksAreCancelledAndRunningOnesFinish() throws Exception {
        TckAdapterContext context = TckAdapterContext.builder(descriptor(List.of())).parallelism(1).build();
        com.rmm.adapter.api.SyncRunContext run = new com.rmm.adapter.api.SyncRunContext(java.util.UUID.randomUUID(),
                "PRJ");
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<String> finished = new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean queuedRan = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.Future<?> queued;
        try (TckAdapterContext.Run ignored = context.startRun(run)) {
            context.executor().submit(() -> {
                started.countDown();
                try {
                    proceed.await();
                    finished.complete("finished");
                } catch (InterruptedException interrupted) {
                    finished.complete("interrupted");
                }
                return null;
            });
            queued = context.executor().submit(() -> {
                queuedRan.set(true);
                return null;
            });
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
        }
        proceed.countDown();

        assertEquals("finished", finished.get(10, java.util.concurrent.TimeUnit.SECONDS),
                "a running task is not interrupted at the end of its run");
        assertTrue(queued.isCancelled(), "a waiting task is cancelled");
        Thread.sleep(100);
        assertFalse(queuedRan.get(), "a cancelled task never runs");
        context.release();
    }

    @Test
    public void scheduledTasksHaveUniqueNamesAndRunWhenTheTckFiresThem() throws Exception {
        TckAdapterContext context = TckAdapterContext.of(descriptor(List.of()), Map.of());
        List<String> runs = new java.util.concurrent.CopyOnWriteArrayList<>();
        var reload = context.executor().schedule("reload", java.time.Duration.ofHours(1), task -> {
            runs.add(task.name() + " " + (task.deadline() != null) + " " + task.stopRequested());
            runs.add(String.valueOf(context.executor().submit(() -> "on the side").get()));
        });
        assertThrows(IllegalStateException.class,
                () -> context.executor().schedule("reload", java.time.Duration.ofHours(1), task -> { }));
        assertThrows(IllegalArgumentException.class,
                () -> context.executor().schedule("Reload Now", java.time.Duration.ofHours(1), task -> { }));
        assertEquals(java.util.Set.of("reload"), context.scheduledTasks());

        context.runScheduled("reload");
        reload.close();

        assertEquals(List.of("reload true false", "on the side"), runs, "submit works inside a scheduled task");
        assertThrows(IllegalStateException.class, () -> context.runScheduled("reload"));
        context.executor().schedule("reload", java.time.Duration.ofHours(1), task -> { }).close();
        context.release();
        assertEquals(java.util.Set.of(), context.scheduledTasks());
    }

    /**
     * A seam that knows only the {@code ContractContext} interface fires a scheduled task like
     * {@link TckAdapterContext#runScheduled}; the TCK has no host metrics and no tenants.
     */
    @Test
    public void theContractContextFiresAScheduledTaskWithoutKnowingTheTck() throws Exception {
        com.rmm.adapter.tck.ContractContext context = TckAdapterContext.of(descriptor(List.of()), Map.of());
        List<String> runs = new java.util.concurrent.CopyOnWriteArrayList<>();
        context.inTenant(() -> context.executor().schedule("reload", java.time.Duration.ofHours(1),
                task -> runs.add(task.name())));

        context.runScheduled("reload");

        assertEquals(List.of("reload"), runs);
        assertThrows(IllegalStateException.class, () -> context.runScheduled("refresh"), "nothing of that name");
        assertEquals(java.util.Optional.empty(), context.hostMetrics(), "no metrics of a host in the TCK");
        context.release();
    }

    /** As in the host: at most 16 scheduled tasks per binding. */
    @Test
    public void aBindingHasAtMostSixteenScheduledTasksLikeTheHost() {
        TckAdapterContext context = TckAdapterContext.of(descriptor(List.of()), Map.of());
        List<com.rmm.adapter.api.AdapterExecutor.Scheduled> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < TckAdapterContext.MAX_SCHEDULED; i++) {
            tasks.add(context.executor().schedule("reload_" + i, java.time.Duration.ofHours(1), task -> { }));
        }
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> context.executor().schedule("one_more", java.time.Duration.ofHours(1), task -> { }));
        assertTrue(refused.getMessage().contains("At most 16 tasks may be scheduled"), refused.getMessage());
        tasks.get(0).close();
        context.executor().schedule("one_more", java.time.Duration.ofHours(1), task -> { });
        assertEquals(16, context.scheduledTasks().size());
        context.release();
    }

    /** As in the host: at most 1000 tasks wait to start. */
    @Test
    public void atMostAThousandTasksWaitToStartLikeTheHost() throws Exception {
        TckAdapterContext context = TckAdapterContext.builder(descriptor(List.of())).parallelism(1).build();
        com.rmm.adapter.api.SyncRunContext run = new com.rmm.adapter.api.SyncRunContext(java.util.UUID.randomUUID(),
                "PRJ");
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        try (TckAdapterContext.Run ignored = context.startRun(run)) {
            futures.add(context.executor().submit(() -> {
                started.countDown();
                proceed.await();
                return null;
            }));
            assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS));
            for (int i = 0; i < TckAdapterContext.MAX_QUEUED_TASKS; i++) {
                futures.add(context.executor().submit(ran::incrementAndGet));
            }
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> context.executor().submit(ran::incrementAndGet));
            proceed.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertEquals(TckAdapterContext.MAX_QUEUED_TASKS, ran.get());
            assertEquals(1, (int) context.executor().submit(() -> 1).get(10, java.util.concurrent.TimeUnit.SECONDS),
                    "room again");
        }
        context.release();
    }

    /**
     * Closing follows the order of the host: first the tasks of the binding (up to 10 s), then
     * {@code instance.close()}, then the HTTP path. A task never works against a client that the adapter's
     * {@code close()} already closed.
     */
    @Test
    public void closingABindingEndsItsTasksFirstThenTheInstanceThenTheNetworkPath() throws Exception {
        try (com.rmm.adapter.tck.FakeHttpTool tool = com.rmm.adapter.tck.FakeHttpTool.start()
                .text("/rows", "text/plain", "row")) {
            TckAdapterContext context = TckAdapterContext.builder(descriptor(List.of())).parallelism(1).build();
            com.rmm.adapter.api.AdapterHttp http = context.http();
            List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.concurrent.atomic.AtomicBoolean closeEntered = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.CountDownLatch working = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
            com.rmm.adapter.api.SyncRunContext run = new com.rmm.adapter.api.SyncRunContext(
                    java.util.UUID.randomUUID(), "PRJ");
            //The task is already working when its run ends: it finishes
            assertTrue(context.inRun(run, () -> {
                context.executor().submit(() -> {
                    working.countDown();
                    proceed.await();
                    order.add("task ended");
                    return null;
                });
                return working.await(10, java.util.concurrent.TimeUnit.SECONDS);
            }));
            com.rmm.adapter.api.AdapterInstance instance = new com.rmm.adapter.api.AdapterInstance() {
                @Override
                public java.util.Optional<com.rmm.adapter.api.SourceReader> reader() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<com.rmm.adapter.api.TargetWriter> writer() {
                    return java.util.Optional.empty();
                }

                @Override
                public void close() {
                    closeEntered.set(true);
                    try {
                        order.add("instance closed, network path " + http.send(com.rmm.adapter.api.AdapterRequest
                                .get(tool.uri("/rows"))).bodyAsString());
                    } catch (Exception closedTooEarly) {
                        order.add("instance closed without network path");
                    }
                }
            };

            Thread closing = new Thread(() -> context.closeBinding(instance), "test-closing");
            closing.start();
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (closing.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.TIMED_WAITING, closing.getState(), "the closing waits");
            assertFalse(closeEntered.get(), "the instance does not close while a task works");
            assertEquals(List.of(), order);
            proceed.countDown();
            closing.join(10_000);

            assertEquals(List.of("task ended", "instance closed, network path row"), order);
            assertThrows(IllegalStateException.class, () -> http.send(com.rmm.adapter.api.AdapterRequest
                    .get(tool.uri("/rows"))), "the network path goes last");
        }
    }

    /** The stamp belongs to the binding: after the release, a new binding stamps a new one. */
    @Test
    public void theStampBelongsToTheBindingNotToTheConnection() throws Exception {
        try (com.rmm.adapter.tck.FakeHttpTool tool = com.rmm.adapter.tck.FakeHttpTool.start()
                .text("/rows", "text/plain", "row")) {
            TckAdapterContext context = TckAdapterContext.of(descriptor(List.of()), Map.of());
            context.http().send(com.rmm.adapter.api.AdapterRequest.get(tool.uri("/rows")));
            context.http().send(com.rmm.adapter.api.AdapterRequest.get(tool.uri("/rows")));
            context.release();
            context.http().send(com.rmm.adapter.api.AdapterRequest.get(tool.uri("/rows")));

            List<String> stamps = tool.requests().stream().map(com.rmm.adapter.tck.FakeHttpTool.Request::stamp)
                    .toList();
            assertEquals(stamps.get(0), stamps.get(1), "one binding, one stamp");
            assertFalse(stamps.get(0).equals(stamps.get(2)), "a new binding of the same connection stamps anew");
            context.release();
        }
    }

    @Test
    public void theNetworkPathIsOneObjectPerBindingAndClosedAfterTheRelease() {
        TckAdapterContext context = TckAdapterContext.of(descriptor(List.of()), Map.of());
        var http = context.http();
        assertTrue(http == context.http(), "the same object while the binding lasts");

        context.release();

        assertThrows(IllegalStateException.class, () -> http.send(com.rmm.adapter.api.AdapterRequest.get(
                java.net.URI.create("http://127.0.0.1:9/"))), "the old network path refuses to send");
        assertTrue(http != context.http(), "a new binding gets a new network path");
        assertEquals(0, context.httpRequests());
    }

    private static boolean alive(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith(prefix));
    }

}
