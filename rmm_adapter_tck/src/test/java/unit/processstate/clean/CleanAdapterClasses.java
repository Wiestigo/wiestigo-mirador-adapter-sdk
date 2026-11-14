package unit.processstate.clean;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Fixture for case 3 of the TCK: classes that hold only constants, including immutable collections,
 * stateless functions and enums with immutable fields, plus everything the compiler generates as synthetic
 * fields and classes (a switch over an enum, {@code assert}, lambdas, inner and anonymous classes). The
 * scan must report nothing here.
 */
public final class CleanAdapterClasses {

    private static final Logger LOG = Logger.getLogger(CleanAdapterClasses.class.getName());
    private static final int PAGE_SIZE = 50;
    private static final Integer RETRIES = 3;
    private static final String SEPARATOR = ";";
    private static final Pattern ID = Pattern.compile("[A-Z]+-\\d+");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final UUID NAMESPACE = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final Comparator<String> ORDER = Comparator.naturalOrder();
    private static final Comparator<String> BY_LENGTH = Comparator.comparing(String::length);
    private static final Function<String, String> TRIM = String::trim;
    private static final Function<String, String> UPPER = value -> value.toUpperCase(java.util.Locale.ROOT);
    private static final Kind DEFAULT_KIND = Kind.ARTIFACT;
    /** Immutable collections are constants; adapters commonly declare their field lists this way. */
    private static final Set<String> UNWRITABLE_FIELDS = Set.of("created", "updated");
    private static final List<String> REQUESTED_FIELDS = List.of("summary", "status");
    private static final Map<String, String> SYSTEM_FIELDS = Map.ofEntries(Map.entry("summary", "Name"));
    private static final List<String> WRAPPED = Collections.unmodifiableList(Arrays.asList("a", "b"));
    private static final Set<Kind> LINKABLE = EnumSet.copyOf(List.of(Kind.RELATIONSHIP));
    private static final Map<String, String> NONE = Collections.emptyMap();

    /** State of a binding lives in instance fields, which case 3 does not check. */
    private final List<String> rows = new java.util.ArrayList<>();
    private int reads;

    enum Kind {
        ARTIFACT("artifact"), RELATIONSHIP("relationship");

        private static final String PREFIX = "kind-";
        private final String label;
        private final Set<String> aliases = Set.of();

        Kind(String label) {
            this.label = label;
        }
    }

    String describe(Kind kind) {
        assert kind != null : "kind";
        reads++;
        Runnable trace = () -> LOG.fine(SEPARATOR + PAGE_SIZE + RETRIES + ID + TIMEOUT + NAMESPACE + UNWRITABLE_FIELDS
                + REQUESTED_FIELDS + SYSTEM_FIELDS + WRAPPED + LINKABLE + NONE + kind.aliases);
        trace.run();
        rows.add(UPPER.apply(TRIM.apply(" " + kind.label + " ")));
        rows.sort(ORDER.thenComparing(BY_LENGTH));
        return switch (kind) {
            case ARTIFACT -> Kind.PREFIX + "artifact" + DEFAULT_KIND;
            case RELATIONSHIP -> new Object() {
                @Override
                public String toString() {
                    return Kind.PREFIX + "relationship" + reads;
                }
            }.toString();
        };
    }

    /** An inner class without static fields. */
    final class Reader {
        private int page;

        int next() {
            return ++page;
        }
    }
}
