package unit.processstate.mutable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Fixture for case 3 of the TCK: one instance of each kind of process-wide state that all bindings would
 * share. The scan must report every one, including those in an inner class, in an enum singleton and behind
 * a function.
 */
public final class MutableAdapterClasses {

    /** A counter: not final. */
    private static int binds;
    /** One cache per process. */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    /** Immutable reference, mutable content. */
    private static final String[] LAST_IDS = new String[2];
    /** One client for all bindings. */
    private static final StringBuilder CLIENT = new StringBuilder();
    /** A function with memory: a lambda that captures a map. */
    private static final Function<String, String> LOOKUP = memo(new HashMap<>());
    /** A function with a field: an anonymous class that counts. */
    private static final Supplier<Integer> COUNTER = new Supplier<>() {
        private int calls;

        @Override
        public Integer get() {
            return ++calls;
        }
    };
    /** Named like a logger, but a class of its own with a list of lines. */
    private static final AuditLogger AUDIT = new AuditLogger();

    /** In a nested class as well. */
    static final class Holder {
        static final List<String> SEEN = new java.util.ArrayList<>();
    }

    /** An enum singleton with mutable content: one per process. */
    enum Registry {
        INSTANCE;

        private final Map<String, String> entries = new HashMap<>();

        void put(String key) {
            entries.put(key, key);
        }
    }

    static final class AuditLogger {
        private final List<String> lines = new java.util.ArrayList<>();

        void info(String line) {
            lines.add(line);
        }
    }

    private static Function<String, String> memo(Map<String, String> remembered) {
        return key -> remembered.computeIfAbsent(key, String::toUpperCase);
    }

    static int touch() {
        CACHE.put("a", "b");
        LAST_IDS[0] = CLIENT.append('x').toString();
        Holder.SEEN.add(LOOKUP.apply("x"));
        Registry.INSTANCE.put("x");
        AUDIT.info("x" + COUNTER.get());
        return ++binds;
    }
}
