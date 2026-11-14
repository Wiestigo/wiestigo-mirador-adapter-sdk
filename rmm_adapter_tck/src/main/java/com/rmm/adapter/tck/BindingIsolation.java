package com.rmm.adapter.tck;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.TargetWriter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * TCK case 1: two bindings of the same adapter work side by side and nothing
 * crosses from one to the other. The case catches, for example, a
 * process-wide cache that all tenants share.
 *
 * <p>The check runs on two bound sides A and B:</p>
 * <ol>
 *   <li>{@code store()} is separate per binding: B does not read what the
 *       TCK writes in A, and vice versa.</li>
 *   <li>With a run context: both read on two threads at once, for several
 *       rounds, and each delivers the same as when it read alone.</li>
 *   <li>With the write direction and {@code roundTripArtifact()}: creating
 *       an artifact in A does not change what B reads.</li>
 *   <li>What only B does (reading) leaves the log, store and counters of A
 *       unchanged, and vice versa.</li>
 *   <li>No marker value of A (its {@code bindingId} and the distinctive
 *       texts of its configuration, {@link #markers}) appears in the
 *       elements, log or store of B, and vice versa.</li>
 * </ol>
 *
 * <p>Public so that a host can run the same check through its own adapter
 * registry.</p>
 */
public final class BindingIsolation {

    /** Number of rounds in which both sides read at once. */
    static final int PARALLEL_ROUNDS = 3;
    /** Shorter configuration values are no markers: they occur by chance in another binding's data. */
    static final int MIN_MARKER_LENGTH = 8;

    /** Only values of these types are markers; numbers and booleans are never distinctive. */
    private static final Set<ConfigType> MARKER_TYPES = Set.of(ConfigType.STRING, ConfigType.URL, ConfigType.SECRET,
            ConfigType.CERTIFICATE, ConfigType.PATH);

    private BindingIsolation() {
    }

    /**
     * How a call runs on behalf of a binding: directly in the TCK, inside the
     * binding's tenant in a host (whose context rejects access from another
     * tenant).
     */
    public interface Scope {
        <T> T call(Callable<T> work) throws Exception;

        /** Directly on the calling thread. */
        static Scope direct() {
            return new Scope() {
                @Override
                public <T> T call(Callable<T> work) throws Exception {
                    return work.call();
                }
            };
        }
    }

    /**
     * One bound side.
     *
     * @param name     used in messages ("A", "B")
     * @param instance the binding
     * @param context  its context (its {@code store()} is checked)
     * @param scope    how calls of this binding run
     * @param markers  values that only this side knows
     * @param traces   what the side has left behind (log lines, store
     *                 content) at the time of the call
     */
    public record Side(String name, AdapterInstance instance, AdapterContext context, Scope scope,
                       Set<String> markers, Supplier<Collection<String>> traces) {

        public Side {
            markers = Set.copyOf(markers);
        }
    }

    /**
     * The marker values of one side: its {@code bindingId} (random, assigned
     * by the host or the TCK) and every distinctive value of a key of type
     * {@code STRING}, {@code URL}, {@code SECRET}, {@code CERTIFICATE} or
     * {@code PATH}. A value is distinctive if it has at least
     * {@value #MIN_MARKER_LENGTH} characters, reads neither as a number nor
     * as a boolean, and neither equals nor contains nor is contained in a
     * value of the other side. So {@code true} or {@code 1000} never becomes
     * a marker.
     *
     * @param ownConfiguration   relative keys and values of this side
     * @param otherConfiguration relative keys and values of the other side
     */
    public static Set<String> markers(AdapterContext own, AdapterDescriptor descriptor,
                                      Map<String, String> ownConfiguration, Map<String, String> otherConfiguration) {
        Set<String> markers = new java.util.LinkedHashSet<>();
        markers.add(own.binding().bindingId());
        for (ConfigKey key : descriptor.configKeys()) {
            String value = ownConfiguration.get(key.key());
            if (MARKER_TYPES.contains(key.type()) && isDistinctive(value, otherConfiguration.values())) {
                markers.add(value.trim());
            }
        }
        return markers;
    }

    private static boolean isDistinctive(String value, Collection<String> otherValues) {
        if (value == null || value.trim().length() < MIN_MARKER_LENGTH) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")
                || trimmed.matches("[-+]?\\d+([.,]\\d+)?")) {
            return false;
        }
        for (String other : otherValues) {
            if (other != null && (other.contains(trimmed)
                    || other.trim().length() >= MIN_MARKER_LENGTH && trimmed.contains(other.trim()))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Runs the check.
     *
     * @param capabilities the capabilities of both sides (same adapter type)
     * @param soloA        what A read while it was the only binding; null without a run context
     * @param soloB        the same for B
     * @param externalProjectId the project to read; null skips reading and writing
     * @param roundTrip    an artifact template to create in A; empty skips the creation
     */
    public static void require(AdapterCapabilities capabilities, Side a, ToolReading soloA, Side b,
                               ToolReading soloB, String externalProjectId,
                               Supplier<Optional<ElementArtifact>> roundTrip, int maxPages) throws Exception {
        requireSeparateStores(a, b);
        requireSeparateStores(b, a);
        if (externalProjectId != null) {
            requireUntouchedBy(capabilities, b, a, externalProjectId, maxPages);
            requireUntouchedBy(capabilities, a, b, externalProjectId, maxPages);
        }
        List<ToolReading> readsOfA = new ArrayList<>();
        List<ToolReading> readsOfB = new ArrayList<>();
        if (soloA != null) {
            readsOfA.add(soloA);
        }
        if (soloB != null) {
            readsOfB.add(soloB);
        }
        boolean reading = externalProjectId != null && soloA != null && soloB != null
                && a.instance().reader().isPresent() && b.instance().reader().isPresent();
        if (reading) {
            readInParallel(capabilities, a, soloA, b, soloB, externalProjectId, maxPages, readsOfA, readsOfB);
            requireInvisibleCreation(capabilities, a, b, externalProjectId, roundTrip, maxPages, readsOfB);
        }
        requireNoMarkers(a, b, readsOfB);
        requireNoMarkers(b, a, readsOfA);
    }

    /**
     * What only {@code actor} does changes nothing in {@code other}: a read
     * by {@code actor} leaves the log, store and counters of {@code other}
     * unchanged. This also catches traces without a marker value (a cursor,
     * a timestamp).
     */
    static void requireUntouchedBy(AdapterCapabilities capabilities, Side actor, Side other, String project,
                                   int maxPages) throws Exception {
        if (actor.instance().reader().isEmpty()) {
            return;
        }
        List<String> before = List.copyOf(other.traces().get());
        SourceReader reader = actor.instance().reader().orElseThrow();
        actor.scope().call(() -> ToolReading.of(actor.context(), reader, capabilities, project, maxPages));
        List<String> after = List.copyOf(other.traces().get());
        if (!before.equals(after)) {
            throw new TckViolation("Reading in binding " + actor.name() + " changed what binding " + other.name()
                    + " holds (log, store or counters) — a binding works only on its own context.\nBefore: "
                    + before + "\nAfter: " + after);
        }
    }

    private static void requireSeparateStores(Side writer, Side other) throws Exception {
        String key = "tck/isolation/" + UUID.randomUUID();
        String value = "tck-isolation-" + writer.name() + "-" + UUID.randomUUID();
        writer.scope().call(() -> {
            writer.context().store().write(key, value);
            return null;
        });
        try {
            Optional<String> seen = other.scope().call(() -> other.context().store().read(key));
            if (seen != null && seen.isPresent()) {
                throw new TckViolation("store() of binding " + writer.name() + " is visible in binding "
                        + other.name() + " (key <" + key + ">) — every binding has its own store; a store shared"
                        + " by bindings hands one connection's state to another");
            }
        } finally {
            writer.scope().call(() -> {
                writer.context().store().delete(key);
                return null;
            });
        }
    }

    private static void readInParallel(AdapterCapabilities capabilities, Side a, ToolReading soloA, Side b,
                                       ToolReading soloB, String project, int maxPages,
                                       List<ToolReading> readsOfA, List<ToolReading> readsOfB) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "tck-binding-isolation");
            thread.setDaemon(true);
            return thread;
        });
        try {
            for (int round = 0; round < PARALLEL_ROUNDS; round++) {
                CyclicBarrier start = new CyclicBarrier(2);
                Future<ToolReading> ofA = threads.submit(() -> readTogether(capabilities, a, project, maxPages, start));
                Future<ToolReading> ofB = threads.submit(() -> readTogether(capabilities, b, project, maxPages, start));
                ToolReading parallelA = resultOf(ofA, a);
                ToolReading parallelB = resultOf(ofB, b);
                readsOfA.add(parallelA);
                readsOfB.add(parallelB);
                requireSame(a, soloA, parallelA, round);
                requireSame(b, soloB, parallelB, round);
            }
        } finally {
            threads.shutdownNow();
        }
    }

    private static ToolReading readTogether(AdapterCapabilities capabilities, Side side, String project,
                                            int maxPages, CyclicBarrier start) throws Exception {
        start.await(30, TimeUnit.SECONDS);
        SourceReader reader = side.instance().reader().orElseThrow();
        return side.scope().call(() -> ToolReading.of(side.context(), reader, capabilities, project, maxPages));
    }

    private static ToolReading resultOf(Future<ToolReading> future, Side side) throws Exception {
        try {
            return future.get(5, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TckViolation violation) {
                throw violation;
            }
            TckViolation violation = new TckViolation("Binding " + side.name()
                    + " failed while another binding read in parallel: " + cause);
            violation.initCause(cause);
            throw violation;
        }
    }

    private static void requireSame(Side side, ToolReading alone, ToolReading together, int round) {
        if (!alone.equals(together)) {
            throw new TckViolation("Binding " + side.name() + " delivered other results while another binding"
                    + " read in parallel (round " + (round + 1) + ") — state shared between bindings (a field of"
                    + " the ToolAdapter, a static cache) lets one connection see another one's tool.\nAlone:\n"
                    + alone + "\nIn parallel:\n" + together);
        }
    }

    private static void requireInvisibleCreation(AdapterCapabilities capabilities, Side a, Side b, String project,
                                                 Supplier<Optional<ElementArtifact>> roundTrip, int maxPages,
                                                 List<ToolReading> readsOfB) throws Exception {
        Optional<ElementArtifact> template = roundTrip.get();
        if (template.isEmpty() || a.instance().writer().isEmpty()) {
            return;
        }
        TargetWriter writer = a.instance().writer().orElseThrow();
        SourceReader readerOfB = b.instance().reader().orElseThrow();
        ToolReading before = b.scope().call(() -> ToolReading.of(b.context(), readerOfB, capabilities, project,
                maxPages));
        //Create and clean up in one run of A each, as in the host
        com.rmm.adapter.api.SyncRunContext creating = new com.rmm.adapter.api.SyncRunContext(UUID.randomUUID(),
                project);
        String created = a.scope().call(() -> TckAdapterContext.runIn(a.context(), creating,
                () -> writer.createArtifact(creating, template.orElseThrow())));
        try {
            ToolReading after = b.scope().call(() -> ToolReading.of(b.context(), readerOfB, capabilities, project,
                    maxPages));
            readsOfB.add(after);
            if (!before.equals(after)) {
                throw new TckViolation("Creating <" + created + "> in binding " + a.name() + " changed what binding "
                        + b.name() + " reads — two connections with different configuration must not share a"
                        + " tool.\nBefore:\n" + before + "\nAfter:\n" + after);
            }
        } finally {
            com.rmm.adapter.api.SyncRunContext deleting = new com.rmm.adapter.api.SyncRunContext(UUID.randomUUID(),
                    project);
            a.scope().call(() -> TckAdapterContext.runIn(a.context(), deleting, () -> {
                writer.deleteArtifact(deleting, created);
                return null;
            }));
        }
    }

    private static void requireNoMarkers(Side owner, Side other, List<ToolReading> readsOfOther) {
        List<String> places = new ArrayList<>();
        for (ToolReading read : readsOfOther) {
            places.add(read.text());
        }
        Collection<String> traces = other.traces().get();
        for (String marker : owner.markers()) {
            for (String place : places) {
                if (place.contains(marker)) {
                    throw new TckViolation("The value <" + marker + "> of binding " + owner.name()
                            + " appears in the elements of binding " + other.name()
                            + " — no id, configuration value or credential may cross bindings");
                }
            }
            for (String trace : traces) {
                if (trace != null && trace.contains(marker)) {
                    throw new TckViolation("The value <" + marker + "> of binding " + owner.name()
                            + " appears in the log or store of binding " + other.name() + ": " + trace);
                }
            }
        }
    }
}
