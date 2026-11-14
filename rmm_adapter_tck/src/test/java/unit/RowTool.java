package unit;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigScope;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tool with both directions, faithful except for its {@link Defect}: every binding reads the data set of its
 * tool ({@code tool.label}) plus what it created, and owns a pool that {@code close()} shuts down.
 */
final class RowTool implements ToolAdapter {

    /** The outside world: one data set per tool; its identifiers do not carry the name of the tool. */
    private static final Map<String, List<String>> WORLD = Map.of(
            "first-tool", List.of("F-1", "F-2", "F-3"),
            "second-tool", List.of("S-1", "S-2"),
            "release-tool", List.of("release-1000-1", "release-1000-2"));

    /** The defects a tool can have, one per check. */
    enum Defect {
        NONE,
        /** The type keeps the tool of the first binding ("the first client wins"). */
        FIRST_CLIENT,
        /** The type keeps the last context and, on binding, writes a cursor into the store of that context. */
        WRITES_PEER_STORE,
        /** The type keeps the last context and writes the bindingId of the next binding into its store. */
        WRITES_PEER_ID,
        /** While several bindings are open, each one delivers one element more (without a marker). */
        DRIFTS_WHEN_OTHERS_ARE_OPEN,
        /** Created elements live in the type: every binding reads what another one created. */
        SHARED_CREATIONS,
        /** {@code close()} does not shut down the pool of the binding. */
        KEEPS_THREADS,
        /** {@code close()} does not shut down a daemon pool of the binding. */
        KEEPS_DAEMON_THREADS,
        /** One {@code HttpClient} per binding, never closed. */
        KEEPS_HTTP_CLIENT,
        /** A new binding delivers what the previous binding left behind. */
        DEPENDS_ON_PREVIOUS,
        /** The page size comes from {@code rmm.adapter.rowtool.page.size} if that property exists. */
        READS_PROPERTY,
        /** The page size comes from the bare system property {@code page.size}. */
        READS_BARE_PROPERTY,
        /** A system property only ends up in the log. */
        LOGS_PROPERTY,
        /** An export directory declared as {@code PATH} in scope {@code BINDING}. */
        PATH_FOR_BINDING,
        /** The type caches, per project, the data set of the first read. */
        PROJECT_CACHE,
        /** The type keeps one client per address: the first binding of an address determines the tool. */
        CLIENT_PER_ADDRESS,
        /** A read writes a cursor into the store of the previous binding. */
        READ_WRITES_PEER_STORE,
        /** The type counts every request in the metrics of the most recently bound binding. */
        METRICS_OF_LAST_BINDING,
        /** Faithful, but without a fixed order: every read delivers another order. */
        SHUFFLED
    }

    private final Defect defect;
    /** Receives the pool of every binding, so that the test cleans up even after a "threads remain" failure. */
    private final List<ExecutorService> pools;
    /**
     * Receives the clients of the "HttpClient per binding" defect, held strongly: otherwise a garbage
     * collection could end their selector thread before the case counts the threads.
     */
    private final List<HttpClient> clients;
    private volatile String firstTool;
    private volatile AdapterContext lastContext;
    private final AtomicInteger open = new AtomicInteger();
    private final List<String> sharedCreations = new CopyOnWriteArrayList<>();
    private final AtomicInteger closed = new AtomicInteger();
    private final Map<String, List<String>> rowsByProject = new ConcurrentHashMap<>();
    private final Map<String, String> toolByAddress = new ConcurrentHashMap<>();

    RowTool(Defect defect, List<ExecutorService> pools, List<HttpClient> clients) {
        this.defect = defect;
        this.pools = pools;
        this.clients = clients;
    }

    @Override
    public AdapterDescriptor descriptor() {
        ConfigKey exportDirectory = defect == Defect.PATH_FOR_BINDING
                ? new ConfigKey("export.dir", ConfigType.PATH, "/tmp/rowtool", "Export directory",
                ConfigScope.BINDING)
                : ConfigKey.platform("export.dir", ConfigType.PATH, "/tmp/rowtool", "Export directory");
        return new AdapterDescriptor("ROWTOOL", "Row Tool", "1.0.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(Set.of(SyncDirection.TO_RMM, SyncDirection.FROM_RMM), HierarchyModel.FLAT,
                        false, false, false, false, SyncPhase.artifactsOnly(), SyncPhase.artifactsOnly(),
                        Set.of(), null),
                List.of(ConfigKey.optional("page.size", ConfigType.INTEGER, "2", "Rows per page"),
                        ConfigKey.optional("tool.label", ConfigType.STRING, "first-tool",
                                "The tool of the binding"),
                        ConfigKey.optional("tool.url", ConfigType.URL, "https://rows.example",
                                "The address of the tool"),
                        exportDirectory),
                List.of());
    }

    @Override
    public AdapterInstance bind(AdapterContext context) {
        int pageSize = pageSize(context);
        String tool = context.config().string("tool.label");
        if (defect == Defect.FIRST_CLIENT) {
            if (firstTool == null) {
                firstTool = tool;
            }
            tool = firstTool;
        }
        if (defect == Defect.CLIENT_PER_ADDRESS) {
            String own = tool;
            tool = toolByAddress.computeIfAbsent(context.config().string("tool.url"), address -> own);
        }
        if (defect == Defect.LOGS_PROPERTY) {
            context.logger().info("label from system: " + System.getProperty("rmm.adapter.rowtool.tool.label"));
        }
        if (defect == Defect.WRITES_PEER_STORE && lastContext != null) {
            lastContext.store().write("delta.cursor", "2026-10-16T08:00:00Z");
        }
        if (defect == Defect.WRITES_PEER_ID && lastContext != null) {
            lastContext.store().write("peer", context.binding().bindingId());
        }
        AdapterContext previousContext = lastContext;
        lastContext = context;
        ExecutorService pool = defect == Defect.KEEPS_DAEMON_THREADS
                ? Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "rowtool-daemon");
                    thread.setDaemon(true);
                    return thread;
                })
                : Executors.newSingleThreadExecutor();
        pools.add(pool);
        pool.submit(() -> { });
        if (defect == Defect.KEEPS_HTTP_CLIENT) {
            //The SelectorManager of an HttpClient is a daemon thread that ends only with the client;
            //the type keeps the client (held strongly, see clients)
            clients.add(HttpClient.newHttpClient());
        }
        List<String> rows = new ArrayList<>(WORLD.getOrDefault(tool, List.of()));
        if (defect == Defect.DEPENDS_ON_PREVIOUS) {
            for (int index = 0; index < closed.get(); index++) {
                rows.add("leftover-" + index);
            }
        }
        List<String> created = defect == Defect.SHARED_CREATIONS ? sharedCreations : new CopyOnWriteArrayList<>();
        open.incrementAndGet();
        List<String> ownRows = rows;
        SourceReader reader = (run, scope, cursor) -> {
            context.metrics().recordToolRequest();
            if (defect == Defect.METRICS_OF_LAST_BINDING) {
                lastContext.metrics().recordToolRequest();
            }
            if (defect == Defect.READ_WRITES_PEER_STORE && previousContext != null) {
                previousContext.store().write("read.cursor", "2026-10-16T08:00:00Z");
            }
            List<String> all = new ArrayList<>(defect == Defect.PROJECT_CACHE
                    ? rowsByProject.computeIfAbsent(run.externalProjectId(), project -> ownRows) : ownRows);
            all.addAll(created);
            if (defect == Defect.SHUFFLED) {
                Collections.shuffle(all, new Random(run.runId().hashCode()));
            }
            if (defect == Defect.DRIFTS_WHEN_OTHERS_ARE_OPEN && open.get() > 1) {
                all.add("extra");
            }
            int start = cursor == null ? 0 : Integer.parseInt(cursor.value());
            int end = Math.min(start + pageSize, all.size());
            List<ElementArtifact> page = new ArrayList<>();
            for (String row : all.subList(start, end)) {
                ElementArtifact artifact = new ElementArtifact();
                artifact.setId(new ElementId(row));
                page.add(artifact);
            }
            boolean last = end >= all.size();
            return new SourcePage<>(page, List.of(), page.size(),
                    last ? null : new PageCursor(String.valueOf(end)), last);
        };
        TargetWriter writer = new RowWriter(created);
        return new AdapterInstance() {
            @Override
            public Optional<SourceReader> reader() {
                return Optional.of(reader);
            }

            @Override
            public Optional<TargetWriter> writer() {
                return Optional.of(writer);
            }

            @Override
            public void close() {
                open.decrementAndGet();
                closed.incrementAndGet();
                if (defect != Defect.KEEPS_THREADS && defect != Defect.KEEPS_DAEMON_THREADS) {
                    pool.shutdownNow();
                }
            }
        };
    }

    private int pageSize(AdapterContext context) {
        String property = defect == Defect.READS_PROPERTY ? System.getProperty("rmm.adapter.rowtool.page.size")
                : defect == Defect.READS_BARE_PROPERTY ? System.getProperty("page.size") : null;
        return property != null ? Integer.parseInt(property) : context.config().integer("page.size");
    }
}
