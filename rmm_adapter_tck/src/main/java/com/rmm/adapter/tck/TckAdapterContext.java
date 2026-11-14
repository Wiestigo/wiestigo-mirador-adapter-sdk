package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterBinding;
import com.rmm.adapter.api.AdapterCaches;
import com.rmm.adapter.api.AdapterConfig;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterExecutor;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterLogger;
import com.rmm.adapter.api.AdapterMetrics;
import com.rmm.adapter.api.AdapterRunStore;
import com.rmm.adapter.api.AdapterStore;
import com.rmm.adapter.api.HostLimits;
import com.rmm.adapter.api.MemoryCache;
import com.rmm.adapter.api.SyncRunContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link AdapterContext} for tests without a running host: configuration from maps (RELATIVE keys, as in the
 * declaration), log output on standard out, in-memory stores, metrics as counters. The rules are the host's:
 * only declared keys are readable, and missing required keys fail BEFORE binding with a message that names them.
 * Every context is a connection of its own with a random, opaque identifier.
 *
 * <p><b>Configuration</b> ({@link MapConfig}): the values of the connection and, separately, those of the
 * deployment ({@link Builder#platform}). A key in scope {@code PLATFORM} comes only from the deployment. What
 * Mirador's adapter administration rejects on saving (a PLATFORM key or {@code <name>.max} in the connection, a
 * number above its upper bound, an invalid address or an unusable certificate), the context rejects when it is
 * built.</p>
 *
 * <p><b>Services of the context:</b></p>
 * <ul>
 *   <li>{@link #http()}: {@link TckAdapterHttp}, the HTTP path built on the JDK client. It stamps every request
 *       with a marker of this binding (new per binding: after {@link #release()} a new bind of the same
 *       connection gets a new one), honors the run's deadline and counts the attempts. As in the host, the
 *       number of retries follows the descriptor's {@code RateLimit} and the connection's value of
 *       {@code http.retries} if the adapter declares that key ({@link TckAdapterHttp.Settings#forConnection}).
 *       It uses the TLS material of the connection as the host does: trust anchors and client certificate from
 *       the {@code CERTIFICATE} values, the client identity from {@link Builder#clientIdentity}, pinning of a
 *       self-signed leaf.</li>
 *   <li>{@link #caches()}: document collections, durable until {@link #clearCaches()}, and memory caches with LRU
 *       eviction (at most {@value #MEMORY_CACHE_LIMIT} entries) that start empty for each binding.</li>
 *   <li>{@link #runStore()}: the run store of the run the thread is bound to ({@link #startRun},
 *       {@link Run#within}). It is emptied at the end of the run; afterwards every access throws
 *       {@link IllegalStateException}. Every hook call of the TCK cases runs in a run, as in the host; only
 *       {@code bind}, {@code close}, {@code checkHealth}, {@code purge} and the metadata calls run without
 *       one.</li>
 *   <li>{@link #executor()}: {@code submit} only in a run or in an execution of a scheduled task, at most
 *       {@link #parallelismOf} at a time per binding, with the run of the caller. At the end of the run, waiting
 *       tasks of the run are cancelled and running ones finish (as in the host). A task that waits without a time
 *       limit for a task of its run that has not started yet executes it itself (as in the host: nested tasks
 *       never deadlock the pool). At most {@value #MAX_QUEUED_TASKS} tasks wait to start and at most
 *       {@value #MAX_SCHEDULED} are scheduled. In the TCK scheduled tasks run only through
 *       {@link #runScheduled} (the TCK has no clock and no ticker). The TCK never pauses a run at a limit; in the
 *       host, running tasks then wait before every request and every store access until the run continues.</li>
 * </ul>
 *
 * <p><b>Binding and connection:</b> the context is the connection ({@code bindingId}, {@code store()}, document
 * caches). What the host releases when the last lease of a binding is returned, the TCK releases in the same
 * order: {@link #releaseTasks()} releases the threads of {@code executor()} and the scheduled tasks, BEFORE
 * {@code instance.close()}; {@link #release()} then releases the HTTP path and the memory caches
 * ({@link ContractContext#closeBinding}). A new bind of the same connection gets new ones afterwards.</p>
 *
 * <p>The context also records what the adapter logs and stores and how often it sends ({@link #logLines()},
 * {@link #storeEntries()}, {@link #httpRequests()}): the TCK uses this to check that nothing from one binding
 * shows up in another (cases 1, 2 and 5). The log masks secrets that the binding has handed out, as in the host,
 * but only those from {@code config()}. The host also masks sign-ins made through {@code http().auth} (such as
 * the Base64 form of Basic); the TCK's log does not, which makes the TCK stricter, not more lenient. Case 2 knows
 * them anyway ({@link #revealedSecrets()}); {@link #toString()} shows no value.</p>
 */
public final class TckAdapterContext implements ContractContext {

    /** Maximum number of entries of a cache from {@code caches().memory}. */
    public static final int MEMORY_CACHE_LIMIT = 10_000;
    /** How many tasks from {@code executor().submit} run at the same time per binding (the host's default). */
    public static final int DEFAULT_PARALLELISM = 8;
    /** The maximum number of tasks from {@code executor().submit} per binding waiting to start (as in the host). */
    public static final int MAX_QUEUED_TASKS = 1000;
    /** The maximum number of scheduled tasks a binding has at the same time (the host's default). */
    public static final int MAX_SCHEDULED = 16;
    /** Deadline of one execution of a scheduled task (60 min). */
    public static final Duration SCHEDULE_DEADLINE = Duration.ofMinutes(60);
    /** How long {@link #release()} waits for the threads of {@code executor()}; same as the host. */
    static final long RELEASE_WAIT_SECONDS = 10;
    /** The prefix of the alternative name with which a client identity certificate names its binding. */
    public static final String BINDING_URN_PREFIX = "urn:rmm:binding:";

    private final AdapterBinding binding;
    private final MapConfig config;
    private final TckAdapterLogger logger;
    /** The documents of the connection across its three stores. */
    private final TckDocuments documents = new TckDocuments();
    private final TckAdapterStore store;
    private final TckAdapterMetrics metrics = new TckAdapterMetrics();
    private final TckAdapterCaches caches;
    private final TckAdapterHttp.Settings network;
    private final int parallelism;
    private final AtomicLong httpAttempts = new AtomicLong();
    private final Map<UUID, Run> activeRuns = new ConcurrentHashMap<>();
    /** What each thread is currently working on: a run or an execution of a scheduled task. */
    private final TckThreadOwner owner = new TckThreadOwner();
    private Generation generation;

    private TckAdapterContext(Builder builder, MapConfig config) {
        this.binding = new AdapterBinding(builder.bindingId);
        this.config = config;
        this.logger = new TckAdapterLogger(builder.descriptor.systemKey(), new CopyOnWriteArrayList<>(),
                config::mask);
        this.store = new TckAdapterStore(binding.bindingId(), documents);
        this.caches = new TckAdapterCaches(binding.bindingId(), documents, () -> generation().memory);
        //As in the host: the retries follow the adapter's RateLimit and the connection's value of http.retries if
        //the adapter declares that key
        com.rmm.adapter.api.RateLimit declared = builder.descriptor.capabilities() == null ? null
                : builder.descriptor.capabilities().rateLimit();
        this.network = builder.network.forConnection(declared,
                config.reservedLimit(com.rmm.adapter.api.AdapterConventions.HTTP_RETRIES_KEY));
        this.parallelism = builder.parallelism;
    }

    /**
     * A context for the descriptor of an adapter. {@code configuration} carries the RELATIVE keys of the
     * declaration (for example {@code home.url}); in operation the host adds the namespace
     * {@code rmm.adapter.<systemKey>.}. These are the values of the connection; values of the operator (scope
     * {@code PLATFORM}) are set with {@link Builder#platform}.
     *
     * @throws IllegalStateException    if declared required keys are missing; the host applies the same check
     *                                  and registers the adapter as DEFECT if it fails
     * @throws IllegalArgumentException if the host would not save a value like this (see the class description)
     */
    public static TckAdapterContext of(AdapterDescriptor descriptor, Map<String, String> configuration) {
        return builder(descriptor).configuration(configuration).build();
    }

    /**
     * A context with a given {@code bindingId}, for tests that must know the identifier in advance. It stays
     * opaque; the host assigns a random UUID (the {@code connectionId} of the connection).
     */
    public static TckAdapterContext of(AdapterDescriptor descriptor, Map<String, String> configuration,
                                       String bindingId) {
        return builder(descriptor).configuration(configuration).bindingId(bindingId).build();
    }

    /** A context with all settings: connection, deployment, identifier, limits. */
    public static Builder builder(AdapterDescriptor descriptor) {
        return new Builder(descriptor);
    }

    /**
     * Runs {@code work} as run {@code run} if {@code context} is a {@link ContractContext}
     * ({@link ContractContext#inRun}: this context or that of a host under the TCK). Any other context (the
     * host's own) binds its runs itself; {@code work} then runs directly.
     */
    public static <T> T runIn(AdapterContext context, SyncRunContext run, Callable<T> work) throws Exception {
        if (context instanceof ContractContext contract) {
            return contract.inRun(run, work);
        }
        return work.call();
    }

    @Override
    public AdapterBinding binding() {
        return binding;
    }

    @Override
    public AdapterConfig config() {
        return config;
    }

    @Override
    public AdapterLogger logger() {
        return logger;
    }

    @Override
    public AdapterStore store() {
        return store;
    }

    @Override
    public AdapterMetrics metrics() {
        return metrics;
    }

    @Override
    public AdapterHttp http() {
        return generation().http;
    }

    @Override
    public AdapterCaches caches() {
        return caches;
    }

    @Override
    public AdapterRunStore runStore() {
        Run run = currentRun();
        if (run == null) {
            throw new IllegalStateException("AdapterContext.runStore() is called outside a run — the host binds hook"
                    + " calls and executor().submit tasks to their run; other threads name it with"
                    + " runStore(SyncRunContext)");
        }
        return run.store;
    }

    @Override
    public AdapterRunStore runStore(SyncRunContext run) {
        Objects.requireNonNull(run, "run");
        return activeRun(run.runId()).store;
    }

    @Override
    public AdapterExecutor executor() {
        return generation().executor;
    }

    // ------------------------------------------------------------------
    // The TCK as the host: runs, release, clearing, scheduled tasks
    // ------------------------------------------------------------------

    /**
     * Starts a run of this connection and binds the calling thread to it, as the host does for a hook call. Its
     * deadline is {@link SyncRunContext#deadline()} (the TCK's clock never stands still). Closing ends the run:
     * waiting tasks from {@code submit} are cancelled, running ones finish, and the run store is emptied.
     *
     * @throws IllegalStateException if this run is already active
     */
    @Override
    public Run startRun(SyncRunContext run) {
        Objects.requireNonNull(run, "run");
        Run started = new Run(run, owner.current());
        if (activeRuns.putIfAbsent(run.runId(), started) != null) {
            throw new IllegalStateException("Run <" + run.runId() + "> is already active in connection <"
                    + binding.bindingId() + ">");
        }
        owner.bind(started);
        return started;
    }

    /** Runs {@code work} in a run of its own ({@link #startRun}) and ends the run afterwards. */
    @Override
    public <T> T inRun(SyncRunContext run, Callable<T> work) throws Exception {
        try (Run ignored = startRun(run)) {
            return work.call();
        }
    }

    /**
     * Releases what the host releases when the last lease of a binding is returned, in the same order: the
     * threads of {@code executor()} (waiting at most {@value #RELEASE_WAIT_SECONDS} s) and the scheduled tasks,
     * then the HTTP path (every request through it throws afterwards) and the memory caches. The connection
     * stays; a new bind gets new ones. May be called more than once.
     */
    @Override
    public void release() {
        Generation released;
        synchronized (this) {
            released = generation;
            generation = null;
        }
        if (released != null) {
            released.close();
        }
    }

    /**
     * Releases only the tasks of the current binding first, as the host does BEFORE {@code instance.close()}: no
     * new task and no new schedule is accepted, waiting tasks are cancelled, running ones have up to
     * {@value #RELEASE_WAIT_SECONDS} s and are then interrupted. The HTTP path stays until {@link #release()};
     * until then {@code executor()} returns the released executor. Does nothing without a current binding.
     */
    @Override
    public void releaseTasks() {
        Generation current = current();
        if (current != null) {
            current.executor.close();
        }
    }

    /**
     * Clears the caches, as the administrator can: all collections from {@code caches().collection} and the
     * memory caches of the current binding. The host then binds anew and the adapter reloads; {@code store()}
     * stays.
     */
    @Override
    public void clearCaches() {
        caches.dropCollections();
        Generation current = current();
        if (current != null) {
            current.memory.values().forEach(MemoryCache::clear);
        }
    }

    /**
     * Runs the scheduled task {@code name} of the current binding once on the calling thread, with the host's
     * deadline for an execution ({@link #SCHEDULE_DEADLINE}). In the host a ticker fires it; the TCK has none
     * ({@link ContractContext#runScheduled}).
     *
     * @throws IllegalStateException if no task of this name is scheduled
     */
    @Override
    public void runScheduled(String name) throws Exception {
        generation().executor.runNow(name);
    }

    /** The names of the scheduled tasks of the current binding that have not been closed. */
    public Set<String> scheduledTasks() {
        Generation current = current();
        return current == null ? Set.of() : current.executor.scheduledNames();
    }

    /** How many tasks of this binding may run at the same time. */
    public int parallelismOf() {
        return parallelism;
    }

    /** Counted tool calls, for tests that check the reporting of metrics. */
    @Override
    public long toolRequests() {
        return metrics.requests();
    }

    /** Counted retries of tool calls. */
    @Override
    public long toolRetries() {
        return metrics.retries();
    }

    /** Sum of the reported throttle wait time in milliseconds. */
    @Override
    public long throttleWaitMillis() {
        return metrics.throttleWaitMillis();
    }

    /** How many attempts went through {@link #http()}, across all bindings of this connection. */
    @Override
    public long httpRequests() {
        return httpAttempts.get();
    }

    /** How many streams from {@code http().open(...)} of the current binding are still open. */
    @Override
    public int openStreams() {
        Generation current = current();
        return current == null ? 0 : current.http.openStreams();
    }

    /**
     * How many documents the run store of this active run holds; 0 for a finished or unknown run. What a
     * finished run still holds is reported by {@link Run#documents()}.
     */
    public int runStoreDocuments(UUID runId) {
        Run run = activeRuns.get(runId);
        return run == null ? 0 : run.documents();
    }

    /** How many documents the connection holds across all three stores. */
    @Override
    public long connectionDocuments() {
        return documents.documents();
    }

    /** How many documents the collections from {@code caches().collection} hold. */
    @Override
    public long cacheDocuments() {
        return caches.documents();
    }

    /** The occasions on which {@code caches()} did not store at its limit; in the host a hint at the connection. */
    public List<String> cacheHints() {
        return documents.cacheHints();
    }

    /** What the adapter has written through {@link #logger()}, in order (masked as in the host). */
    @Override
    public List<String> logLines() {
        return java.util.Collections.unmodifiableList(new ArrayList<>(logger.lines()));
    }

    /** The content of {@link #store()} at this moment. */
    @Override
    public Map<String, String> storeEntries() {
        return store.entries();
    }

    /**
     * The marker that {@link #http()} of the current binding stamps into every request, for the fake tool
     * (case 2). New per binding.
     */
    @Override
    public String networkStamp() {
        return generation().stamp;
    }

    /**
     * Does what the host does after binding: from then on {@code config()} reads the keys that the instance
     * declares beyond those of its type from the values of the connection ({@link MapConfig}).
     */
    public void declareConnectionKeys(java.util.List<com.rmm.adapter.api.ConfigKey> keys) {
        config.declareConnectionKeys(keys);
    }

    /** Only the secrets from this connection's own {@code config()} that it has handed out. */
    @Override
    public Set<String> configSecrets() {
        return config.revealed();
    }

    /**
     * The secrets that this binding has handed out or signed in with: those from {@code config()} and the
     * sign-ins of its HTTP path ({@code auth}); for the check that another binding sends none of them (case 2).
     */
    @Override
    public Set<String> revealedSecrets() {
        Set<String> secrets = new java.util.HashSet<>(config.revealed());
        Generation current = current();
        if (current != null) {
            secrets.addAll(current.http.signIns());
        }
        return Set.copyOf(secrets);
    }

    /** Without configuration values and without the binding marker. */
    @Override
    public String toString() {
        return "TckAdapterContext[connection " + binding.bindingId() + ", " + config + "]";
    }

    /** What the current binding holds; the first use after a release starts a new binding. */
    private synchronized Generation generation() {
        if (generation == null) {
            generation = new Generation();
        }
        return generation;
    }

    /** What the current binding holds; {@code null} if nothing has used it yet or it has been released. */
    private synchronized Generation current() {
        return generation;
    }

    /** The run of the thread; {@code null} outside a run or after its end. */
    private Run currentRun() {
        return owner.current() instanceof Run run && !run.ended ? run : null;
    }

    private Run activeRun(UUID runId) {
        Run run = activeRuns.get(runId);
        if (run == null) {
            throw new IllegalStateException("Run <" + runId + "> is not an active run of connection <"
                    + binding.bindingId() + "> — name only runs of this binding that have not ended");
        }
        return run;
    }

    /**
     * A run of this connection ({@link #startRun}). Closing ends it: tasks of the run from {@code submit} that
     * are still waiting are cancelled, running ones finish (as in the host, which does not interrupt them); the
     * run store is emptied, and every access to it throws {@link IllegalStateException} afterwards.
     */
    public final class Run implements ContractContext.Run {

        private final SyncRunContext context;
        private final Object previous;
        private final Thread starter = Thread.currentThread();
        private final TckAdapterRunStore store;
        /** The tasks submitted in this run; those that still wait at its end are cancelled. */
        private final Set<TckAdapterExecutor.Task<?>> tasks = ConcurrentHashMap.newKeySet();
        private volatile boolean ended;

        private Run(SyncRunContext context, Object previous) {
            this.context = context;
            this.previous = previous;
            this.store = new TckAdapterRunStore(context.runId(), this::requireActive, documents);
        }

        /** The run as the hooks receive it. */
        @Override
        public SyncRunContext context() {
            return context;
        }

        /** Whether the run has ended. */
        @Override
        public boolean ended() {
            return ended;
        }

        /** Ends the run (see the class description). May be called more than once. */
        @Override
        public void close() {
            if (ended) {
                return;
            }
            ended = true;
            activeRuns.remove(context.runId(), this);
            //Cancel waiting tasks, do not interrupt running ones: they finish and deliver their result, as in the host
            tasks.forEach(TckAdapterExecutor.Task::cancelIfWaiting);
            tasks.clear();
            store.drop();
            if (Thread.currentThread() == starter) {
                owner.bind(previous);
            }
        }

        /** Takes note of a task submitted in this run; a task submitted after its end is cancelled at once. */
        void submitted(TckAdapterExecutor.Task<?> task) {
            tasks.add(task);
            if (ended) {
                task.cancel(false);
            }
        }

        /**
         * Runs {@code work} on the calling thread in this run, as the host binds the parallel tasks of a run (for
         * example the batches of a write) to their run.
         *
         * @throws IllegalStateException if the run has ended
         */
        @Override
        public <T> T within(Callable<T> work) throws Exception {
            requireActive();
            return owner.call(this, work);
        }

        /**
         * How many documents the run store of this run holds, also after its end: then it must be 0, because the
         * host empties it at the end of the run.
         */
        @Override
        public int documents() {
            return store.documents();
        }

        private void requireActive() {
            if (ended) {
                throw new IllegalStateException("Run <" + context.runId() + "> has ended — its run store is emptied"
                        + " at the end of the run; keep what must outlive a run in store()");
            }
        }

        @Override
        public String toString() {
            return "Run[" + context.runId() + (ended ? ", ended" : "") + "]";
        }
    }

    /** What a binding holds until {@link #release()} releases it. */
    private final class Generation {

        private final String stamp = UUID.randomUUID().toString();
        private final TckAdapterHttp http;
        private final TckAdapterExecutor executor = new TckAdapterExecutor(binding.bindingId(), parallelism, owner);
        private final Map<String, MemoryCache<?>> memory = new ConcurrentHashMap<>();

        private Generation() {
            //The TLS material of the connection, as the host reads it for the binding
            TckAdapterHttp.Tls tls = new TckAdapterHttp.Tls(new TckTlsMaterial(binding.bindingId(),
                    config.certificateMaterials(), config.clientIdentity()), config.declaresClientIdentity(),
                    config.clientIdentity().map(identity -> identity.certificates().get(0)));
            this.http = new TckAdapterHttp(binding.bindingId(), stamp, network, new TckAdapterHttp.Runs() {
                @Override
                public Optional<SyncRunContext> current() {
                    Run run = currentRun();
                    return run == null ? Optional.empty() : Optional.of(run.context);
                }

                @Override
                public SyncRunContext active(UUID runId) {
                    return activeRun(runId).context;
                }
            }, httpAttempts, tls);
        }

        private void close() {
            //As in the host: the tasks first (unless releaseTasks() already did that); during their grace period
            //they may still use the HTTP path
            executor.close();
            http.close();
            memory.values().forEach(MemoryCache::clear);
            memory.clear();
        }
    }

    /** Builds a {@link TckAdapterContext}. */
    public static final class Builder {

        private final AdapterDescriptor descriptor;
        private Map<String, String> configuration = Map.of();
        private Map<String, String> platform = Map.of();
        private String bindingId = UUID.randomUUID().toString();
        /** Whether the test set the identifier itself; otherwise a client identity may name it. */
        private boolean bindingIdGiven;
        private TckAdapterHttp.Settings network = TckAdapterHttp.Settings.defaults();
        private HostLimits hostLimits = HostLimits.unknown();
        private int parallelism = DEFAULT_PARALLELISM;
        private Optional<TckPem.Material> clientIdentity = Optional.empty();

        private Builder(AdapterDescriptor descriptor) {
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }

        /** The values of the connection (relative keys), maintained by the tenant. */
        public Builder configuration(Map<String, String> configuration) {
            this.configuration = Map.copyOf(Objects.requireNonNull(configuration, "configuration"));
            return this;
        }

        /** The values of the deployment (relative keys), set by the operator; the only source for PLATFORM. */
        public Builder platform(Map<String, String> platform) {
            this.platform = Map.copyOf(Objects.requireNonNull(platform, "platform"));
            return this;
        }

        public Builder bindingId(String bindingId) {
            if (bindingId == null || bindingId.isBlank()) {
                throw new IllegalArgumentException("bindingId must not be empty");
            }
            this.bindingId = bindingId;
            this.bindingIdGiven = true;
            return this;
        }

        /**
         * Limits of the HTTP path; default {@link TckAdapterHttp.Settings#defaults()}. Their
         * {@link TckAdapterHttp.Settings#maxRetries()} cap the retries in addition to the {@code RateLimit} and
         * {@code http.retries} ({@link TckAdapterHttp.Settings#forConnection}). The host has no such setting; it
         * exists only for checks that need fewer retries.
         */
        public Builder network(TckAdapterHttp.Settings network) {
            this.network = Objects.requireNonNull(network, "network");
            return this;
        }

        /**
         * The client identity of the connection, as the host creates and keeps it for a type with a
         * {@code CLIENT_IDENTITY} key: a PEM with the certificate and its unencrypted private key (PKCS#8). The
         * adapter reads only the public certificate through {@code config()}; the HTTP path presents the identity
         * on every {@code https} request. Without an identity (the default) or with an expired one,
         * {@link #build()} does not build the context of such a type, just as the host does not bind it then.
         *
         * <p>If the certificate names its binding ({@code URI:urn:rmm:binding:<bindingId>}, as the host issues it
         * for the connection), that is the identifier of the context unless the test sets its own
         * ({@link #bindingId}): a service that compares the binding header with the certificate then sees the
         * same identifier as in the host.</p>
         *
         * @throws IllegalArgumentException if the value is not a usable PEM or the key is missing; the message
         *                                  does not contain the value
         */
        public Builder clientIdentity(String pem) {
            this.clientIdentity = Optional.of(TckClientIdentity.parse(pem));
            return this;
        }

        /** What {@code config().host()} returns; default {@link HostLimits#unknown()}. */
        public Builder hostLimits(HostLimits hostLimits) {
            this.hostLimits = Objects.requireNonNull(hostLimits, "hostLimits");
            return this;
        }

        /** How many tasks from {@code submit} run at the same time; default {@value #DEFAULT_PARALLELISM}. */
        public Builder parallelism(int parallelism) {
            if (parallelism < 1) {
                throw new IllegalArgumentException("parallelism must be positive: " + parallelism);
            }
            this.parallelism = parallelism;
            return this;
        }

        /**
         * Builds the context.
         *
         * @throws IllegalStateException    if declared required keys are missing, or if the type declares a
         *                                  {@code CLIENT_IDENTITY} key and the connection has no identity or an
         *                                  expired one ({@link #clientIdentity})
         * @throws IllegalArgumentException if the host would not save a value of the connection like this, would
         *                                  not load the type like this (a wrongly declared upper bound or a
         *                                  wrongly declared reserved key), would not bind with a value of a
         *                                  reserved key, or a client identity is given for a type without a
         *                                  {@code CLIENT_IDENTITY} key
         */
        public TckAdapterContext build() {
            MapConfig config = new MapConfig(descriptor, configuration, platform, hostLimits, clientIdentity);
            List<String> missing = config.missingRequiredKeys();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Missing required configuration for the TCK run: "
                        + String.join(", ", missing) + " — provide the relative keys via configuration() (scope"
                        + " BINDING) or platformConfiguration() (scope PLATFORM)");
            }
            TckClientIdentity.requireUsable(config);
            if (!bindingIdGiven) {
                clientIdentity.flatMap(identity -> TckClientIdentity.bindingNamedBy(identity.certificates().get(0)))
                        .ifPresent(named -> bindingId = named);
            }
            return new TckAdapterContext(this, config);
        }
    }
}
