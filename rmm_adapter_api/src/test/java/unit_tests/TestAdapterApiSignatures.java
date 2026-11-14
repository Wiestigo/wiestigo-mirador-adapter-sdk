package unit_tests;

import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterConfig;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AdapterStore;
import com.rmm.adapter.api.MetadataProvider;
import com.rmm.adapter.api.SyncRunContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the public contract in {@code com.rmm.adapter.api}.
 *
 * <p><b>Types.</b> The signatures and annotations of every public type of the
 * package and its subpackages use only primitives, {@code java.*}, the
 * contract itself and the transfer model {@code com.io_elements}: a plugin
 * sees nothing else through the host's class loader. The check reads the
 * signatures, not the class path, because the class path holds more than the
 * contract (Jackson through {@code rmm_adapter_model}). Every transfer model type that a signature reaches is checked the
 * same way until no new ones turn up; the foreign types it shows must be
 * listed with a reason in {@link #TRANSFER_MODEL_EXEMPTIONS}.</p>
 *
 * <p><b>Frozen list.</b> Major 1 is frozen. {@value #FROZEN_LIST} names every
 * public or protected member of the package with the minor version that
 * introduced it; {@link #FROZEN_MINORS} holds the line count and fingerprint
 * of each minor version. The test fails when a listed member is missing or
 * has a different signature (breaks major 1), when a member is not listed,
 * when the list names a minor version above {@link AdapterApiVersion#MINOR},
 * or when an already frozen minor version gains lines. A new member therefore
 * needs MINOR + 1: append its line, raise {@code MINOR}, add the fingerprint
 * of the new minor version.</p>
 *
 * <p>An abstract member added to a type of an earlier minor version fails
 * even with MINOR + 1: a plugin built against the earlier minor does not
 * implement it ({@code AbstractMethodError}), so an existing type gains only
 * default methods. Constants (static final, primitive or {@code String}) are
 * listed with their value: a plugin compiles the value into its own
 * bytecode, so a changed value breaks it like a changed signature. The
 * exception is {@link AdapterApiVersion#MINOR}, which grows with every minor
 * version. The message for a frozen minor version names only the frozen
 * fingerprint, never the new one.</p>
 */
class TestAdapterApiSignatures {

    private static final String API_PACKAGE = "com.rmm.adapter.api";
    private static final String TRANSFER_MODEL = "com.io_elements.";
    private static final List<String> ALLOWED_PREFIXES = List.of("java.", API_PACKAGE + ".", TRANSFER_MODEL);

    /**
     * Foreign types the transfer model shows, each with its reason. Empty: the
     * transfer model carries no annotation or type of the host, and it is
     * meant to stay that way.
     */
    private static final Map<String, String> TRANSFER_MODEL_EXEMPTIONS = Map.of();

    /** The frozen list of contract major 1; each line is {@code <minor> <signature>}. */
    static final String FROZEN_LIST = "/frozen-api/major-1.txt";

    /**
     * Per frozen minor version: the number of its lines and the first 16 hex
     * characters of the SHA-256 over its sorted signatures (joined with a line
     * break). An entry never changes; a new minor version gets a new entry.
     */
    static final Map<Integer, String> FROZEN_MINORS = Map.of(
            0, "582 6755e9febc07d552",
            1, "39 da5499e6adbb085a");

    /** The constant that grows with every minor version: listed without its value. */
    static final String GROWING_CONSTANT = "public static final int " + AdapterApiVersion.class.getName() + ".MINOR";

    /**
     * The contract is exactly the frozen list; every minor version up to
     * {@link AdapterApiVersion#MINOR} is frozen.
     */
    @Test
    void theContractIsTheFrozenList() throws Exception {
        Map<String, Integer> listed = frozenList();
        Set<String> current = new TreeSet<>(members(publicApiTypes()));
        assertTrue(current.size() > 600, "the members of the contract: " + current.size());

        assertEquals(List.of(), problemsOfTheFreeze(listed, current, AdapterApiVersion.MINOR, FROZEN_MINORS),
                "the frozen contract major 1 (" + FROZEN_LIST + ")");
        assertEquals(listed, frozenListOf(String.join("\r\n", frozenListText().lines().toList())),
                "a checkout with CRLF line endings reads the same list");
    }

    /**
     * Negative test of the frozen list: removing or changing a member, a member
     * without a line, a line added to a frozen minor version and a new minor
     * version without {@code MINOR} + 1 all fail; a new minor version with a
     * raised {@code MINOR} and its fingerprint passes.
     */
    @Test
    void theFrozenListDetectsRemovingChangingAndAppendingWithoutANewMinorVersion() {
        Map<String, Integer> listed = new TreeMap<>(Map.of("public void T.a()", 0, "public void T.b()", 0,
                "public void T.c()", 1));
        Map<Integer, String> pinned = Map.of(0, fingerprint(listed, 0), 1, fingerprint(listed, 1));
        Set<String> current = new TreeSet<>(listed.keySet());
        assertEquals(List.of(), problemsOfTheFreeze(listed, current, 1, pinned));

        Set<String> removed = new TreeSet<>(current);
        removed.remove("public void T.b()");
        assertTrue(problemsOfTheFreeze(listed, removed, 1, pinned).stream()
                .anyMatch(p -> p.contains("T.b()") && p.contains("breaks major 1")));

        Set<String> changed = new TreeSet<>(removed);
        changed.add("public int T.b()");
        List<String> changes = problemsOfTheFreeze(listed, changed, 1, pinned);
        assertTrue(changes.stream().anyMatch(p -> p.contains("void T.b()") && p.contains("breaks major 1")),
                changes::toString);
        assertTrue(changes.stream().anyMatch(p -> p.contains("int T.b()") && p.contains("not in")), changes::toString);

        Set<String> appended = new TreeSet<>(current);
        appended.add("public default void T.d()");
        assertTrue(problemsOfTheFreeze(listed, appended, 1, pinned).stream()
                .anyMatch(p -> p.contains("T.d()") && p.contains("minor 2")));

        Map<String, Integer> intoMinorOne = new TreeMap<>(listed);
        intoMinorOne.put("public default void T.d()", 1);
        assertTrue(problemsOfTheFreeze(intoMinorOne, appended, 1, pinned).stream()
                .anyMatch(p -> p.contains("minor 1 is frozen")));

        Map<String, Integer> asMinorTwo = new TreeMap<>(listed);
        asMinorTwo.put("public default void T.d()", 2);
        List<String> withoutRaise = problemsOfTheFreeze(asMinorTwo, appended, 1, pinned);
        assertTrue(withoutRaise.stream().anyMatch(p -> p.contains("AdapterApiVersion.MINOR is 1")),
                withoutRaise::toString);

        Map<Integer, String> pinnedTwo = new TreeMap<>(pinned);
        pinnedTwo.put(2, fingerprint(asMinorTwo, 2));
        assertTrue(problemsOfTheFreeze(asMinorTwo, appended, 2, pinned).stream()
                .anyMatch(p -> p.contains("minor 2 has no fingerprint")));
        assertEquals(List.of(), problemsOfTheFreeze(asMinorTwo, appended, 2, pinnedTwo));
    }

    /**
     * The message for a frozen minor version names the frozen fingerprint, not
     * that of the changed list; otherwise it would simply be copied into
     * {@link #FROZEN_MINORS}.
     */
    @Test
    void theMessageForAFrozenMinorVersionDoesNotNameTheNewFingerprint() {
        Map<String, Integer> listed = new TreeMap<>(Map.of("public void T.a()", 0, "public void T.c()", 1));
        Map<Integer, String> pinned = Map.of(0, fingerprint(listed, 0), 1, fingerprint(listed, 1));
        Map<String, Integer> intoMinorOne = new TreeMap<>(listed);
        intoMinorOne.put("public default void T.d()", 1);
        String changed = fingerprint(intoMinorOne, 1);

        List<String> problems = problemsOfTheFreeze(intoMinorOne, intoMinorOne.keySet(), 1, pinned);
        String frozen = problems.stream().filter(p -> p.contains("minor 1 is frozen")).findFirst().orElseThrow();
        assertTrue(frozen.contains(pinned.get(1)), frozen);
        assertFalse(frozen.contains(changed.substring(changed.indexOf(' ') + 1)), "not the new fingerprint: " + frozen);
    }

    /**
     * An abstract member on a type of an earlier minor version fails, even when
     * appended with MINOR + 1 and a fingerprint: a method of an interface or of
     * an abstract class, public or protected. Without a line, the message says
     * that it may only come as a default method, not that it comes with the next
     * minor version. Abstract members of a new type, a new default method and a
     * new static method pass.
     */
    @Test
    void anAbstractMemberOnAnExistingTypeFails() {
        Map<String, Integer> listed = new TreeMap<>(Map.of("public abstract interface T", 0,
                "public abstract void T.a()", 0, "public abstract class K", 0, "public void K.b()", 0));
        Map<Integer, String> pinnedZero = Map.of(0, fingerprint(listed, 0));
        assertEquals(List.of(), problemsOfTheFreeze(listed, listed.keySet(), 0, pinnedZero));

        for (String added : List.of("public abstract void T.e()",
                "public abstract <V> V T.f(java.util.Map<java.lang.String, V>)",
                "protected abstract java.lang.String K.g()")) {
            Set<String> current = new TreeSet<>(listed.keySet());
            current.add(added);
            List<String> unlisted = problemsOfTheFreeze(listed, current, 0, pinnedZero);
            assertTrue(unlisted.stream().anyMatch(p -> p.contains(added) && p.contains("AbstractMethodError")
                    && p.contains("default method")), unlisted::toString);
            assertTrue(unlisted.stream().noneMatch(p -> p.contains("comes with minor")), unlisted::toString);

            Map<String, Integer> asMinorOne = new TreeMap<>(listed);
            asMinorOne.put(added, 1);
            List<String> appended = problemsOfTheFreeze(asMinorOne, current, 1,
                    Map.of(0, fingerprint(asMinorOne, 0), 1, fingerprint(asMinorOne, 1)));
            assertTrue(appended.stream().anyMatch(p -> p.contains(added) && p.contains("AbstractMethodError")),
                    appended::toString);
        }

        Map<String, Integer> fine = new TreeMap<>(listed);
        fine.put("public default void T.h()", 1);
        fine.put("public abstract interface N", 1);
        fine.put("public abstract void N.i()", 1);
        fine.put("public static void T.j()", 1);
        assertEquals(List.of(), problemsOfTheFreeze(fine, fine.keySet(), 1,
                Map.of(0, fingerprint(fine, 0), 1, fingerprint(fine, 1))));
        assertEquals(List.of(), abstractAdditions(fine, fine.keySet(), 1));
    }

    /**
     * Constants are listed with their value, because a plugin compiles it into
     * its bytecode. Listed without a value: {@link AdapterApiVersion#MINOR}, enum
     * constants, instance fields and constants that are neither primitive nor
     * {@code String}.
     */
    @Test
    void constantsAreListedWithTheirValue() {
        List<String> versions = members(List.of(AdapterApiVersion.class));
        assertTrue(versions.contains("public static final int " + AdapterApiVersion.class.getName() + ".MAJOR = "
                + AdapterApiVersion.MAJOR), versions::toString);
        assertTrue(versions.contains(GROWING_CONSTANT), versions::toString);

        List<String> constants = members(List.of(Constants.class));
        String owner = Constants.class.getName();
        assertTrue(constants.contains("public static final java.lang.String " + owner + ".KEY = \"timeout.connect\""),
                constants::toString);
        assertTrue(constants.contains("public static final int " + owner + ".LIMIT = 200"), constants::toString);
        assertTrue(constants.contains("protected static final long " + owner + ".SHIELDED = 7"), constants::toString);
        assertTrue(constants.contains("public static final java.util.List<java.lang.String> " + owner + ".NAMES"),
                constants::toString);
        assertTrue(constants.contains("public final int " + owner + ".instance"), constants::toString);
    }

    @Test
    void allSignaturesUseOnlyJdkAndContractTypes() throws Exception {
        List<Class<?>> types = publicApiTypes();
        Set<String> names = new TreeSet<>();
        types.forEach(type -> names.add(type.getSimpleName()));
        //The check is only worth something if it also sees the types of the HTTP path and of the stores
        assertTrue(names.containsAll(Set.of("AdapterHttp", "AdapterRequest", "AdapterResponse", "StreamResponse",
                "Body", "Multipart", "RedirectPolicy", "Auth", "TokenProvider", "DocumentCollection", "Document",
                "AdapterCaches", "MemoryCache", "AdapterRunStore", "AdapterExecutor", "TaskContext", "Scheduled",
                "HostLimits", "AdapterStoreLimitException", "SecretValue", "AdapterContext", "AdapterConfig")),
                names.toString());

        Set<Class<?>> reached = new LinkedHashSet<>();
        List<String> violations = new ArrayList<>();
        for (Class<?> type : types) {
            violations.addAll(foreignTypesIn(type, reached));
        }
        assertEquals(List.of(), violations, "signatures with types outside the JDK and the contract");
        assertFalse(reached.isEmpty(), "the signatures reach the transfer model (ElementArtifact and others)");
    }

    /**
     * The transfer model, as far as the contract reaches it, shows only the JDK,
     * the contract and the listed exemptions.
     */
    @Test
    void theReachedTransferModelShowsOnlyJustifiedForeignTypes() throws Exception {
        Set<Class<?>> reached = new LinkedHashSet<>();
        for (Class<?> type : publicApiTypes()) {
            foreignTypesIn(type, reached);
        }
        Map<String, List<String>> foreign = new TreeMap<>();
        Deque<Class<?>> open = new ArrayDeque<>(reached);
        Set<Class<?>> done = new HashSet<>();
        while (!open.isEmpty()) {
            Class<?> type = open.removeFirst();
            if (!done.add(type)) {
                continue;
            }
            Set<Class<?>> next = new LinkedHashSet<>();
            for (String violation : foreignTypesIn(type, next)) {
                String foreignType = violation.substring(violation.lastIndexOf(": ") + 2);
                foreign.computeIfAbsent(foreignType, ignored -> new ArrayList<>()).add(violation);
            }
            next.stream().filter(candidate -> !done.contains(candidate)).forEach(open::addLast);
        }

        assertTrue(done.stream().anyMatch(type -> type.getSimpleName().equals("ElementArtifact")), done.toString());
        assertTrue(foreign.keySet().containsAll(TRANSFER_MODEL_EXEMPTIONS.keySet()),
                "every exemption is needed, otherwise it must be removed: " + foreign.keySet());
        foreign.keySet().removeAll(TRANSFER_MODEL_EXEMPTIONS.keySet());
        assertEquals(Map.of(), foreign, "foreign types in the transfer model without a reason");
    }

    /**
     * Negative test: the check detects a foreign type, also deep inside
     * generics, exceptions and annotations.
     */
    @Test
    void theCheckDetectsForeignTypes() {
        List<String> violations = foreignTypesIn(LeakyApi.class, new HashSet<>());

        for (String method : List.of(".direct", ".nested", ".bound", ".failing", ".annotated", ".parameter")) {
            assertTrue(violations.stream().anyMatch(v -> v.contains(method)), method + " in " + violations);
        }
        assertTrue(violations.stream().noneMatch(v -> v.contains(".fine")), violations.toString());
        assertTrue(violations.stream().allMatch(v -> v.endsWith(Foreign.class.getName())
                || v.endsWith(ForeignMarker.class.getName())), violations.toString());
    }

    /**
     * No abstract member of the frozen list sits on a type of an earlier minor
     * version ({@link #abstractAdditions}). The methods listed below belong to
     * contract 1.0 and must stay default methods, so that implementations that
     * do not override them keep compiling.
     */
    @Test
    void additionsToExistingInterfacesAreDefaultMethods() throws Exception {
        Map<String, Integer> listed = frozenList();
        assertEquals(List.of(), abstractAdditions(listed, listed.keySet(), AdapterApiVersion.MINOR));
        assertTrue(listed.containsKey("public default java.util.Optional<java.util.List<"
                + "com.rmm.adapter.api.IdentifierKind>> com.rmm.adapter.api.AdapterInstance.identifierKinds()"),
                "the addition of minor version 1 that the check sees");

        List<Method> additions = List.of(
                AdapterContext.class.getMethod("http"),
                AdapterContext.class.getMethod("caches"),
                AdapterContext.class.getMethod("runStore"),
                AdapterContext.class.getMethod("runStore", SyncRunContext.class),
                AdapterContext.class.getMethod("executor"),
                AdapterConfig.class.getMethod("secret", String.class),
                AdapterConfig.class.getMethod("url", String.class),
                AdapterConfig.class.getMethod("limit", String.class),
                AdapterConfig.class.getMethod("certificate", String.class),
                AdapterConfig.class.getMethod("host"),
                AdapterStore.class.getMethod("collection", String.class),
                AdapterInstance.class.getMethod("parseEvent", Map.class, InputStream.class),
                MetadataProvider.class.getMethod("commentFields"),
                //Notes and skipping for comments; the handle that awaits own requests on the HTTP path
                com.rmm.adapter.api.TargetWriter.class.getMethod("createCommentWithOutcome", SyncRunContext.class,
                        com.io_elements.layer_infrastructure.database_schema.element.ElementComment.class),
                com.rmm.adapter.api.AdapterHttp.class.getMethod("awaitingOwnRequests"));

        for (Method method : additions) {
            assertTrue(method.isDefault(), method + " must be a default method");
        }
    }

    /**
     * A record that gains a component keeps the constructor with the earlier
     * signature (additive, the contract stays major 1). Since contract 1.1: the
     * image reference forms of the capabilities and {@code apiMinorVersion} of
     * the descriptor.
     */
    @Test
    void additionsToExistingRecordsKeepTheirConstructor() throws NoSuchMethodException {
        com.rmm.adapter.api.AdapterCapabilities.class.getConstructor(Set.class,
                com.rmm.adapter.api.HierarchyModel.class, boolean.class, boolean.class, boolean.class, boolean.class,
                Set.class, Set.class, Set.class, com.rmm.adapter.api.RateLimit.class);
        //The eighth component is apiMinorVersion; the constructor with seven arguments sets it to 0
        com.rmm.adapter.api.AdapterDescriptor.class.getConstructor(String.class, String.class, String.class,
                int.class, com.rmm.adapter.api.AdapterCapabilities.class, List.class, List.class);
    }

    // --- Frozen list --------------------------------------------------------

    /** The lines of the frozen list: signature to minor version. */
    private static Map<String, Integer> frozenList() throws IOException {
        return frozenListOf(frozenListText());
    }

    /** The frozen list as it lies in the checkout, with whatever line ending the checkout has. */
    private static String frozenListText() throws IOException {
        try (InputStream in = TestAdapterApiSignatures.class.getResourceAsStream(FROZEN_LIST)) {
            assertTrue(in != null, FROZEN_LIST);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The lines of {@code text}: signature to minor version. A line ends with LF, CRLF or CR, so the list
     * reads the same in a checkout that converted the line endings.
     */
    private static Map<String, Integer> frozenListOf(String text) {
        Map<String, Integer> listed = new TreeMap<>();
        for (String line : text.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int space = line.indexOf(' ');
            Integer before = listed.put(line.substring(space + 1), Integer.parseInt(line.substring(0, space)));
            assertTrue(before == null, "listed twice: " + line);
        }
        return listed;
    }

    /**
     * What violates the frozen list; empty when the contract matches it exactly
     * and every minor version up to {@code minor} is frozen with its
     * fingerprint.
     */
    static List<String> problemsOfTheFreeze(Map<String, Integer> listed, Set<String> current, int minor,
                                            Map<Integer, String> pinned) {
        List<String> abstractAdditions = abstractAdditions(listed, current, minor);
        int highest = listed.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> problems = new ArrayList<>();
        problems.addAll(removedOrChanged(listed, current));
        problems.addAll(notListed(listed, current, minor, abstractAdditions));
        problems.addAll(abstractOnEarlierTypes(abstractAdditions));
        problems.addAll(listBesideTheMinorVersion(highest, minor));
        problems.addAll(fingerprintsBesideTheList(listed, Math.max(highest, minor), minor, pinned));
        return problems;
    }

    /** Listed members that the contract no longer has in that form. */
    private static List<String> removedOrChanged(Map<String, Integer> listed, Set<String> current) {
        List<String> problems = new ArrayList<>();
        for (String member : listed.keySet()) {
            if (!current.contains(member)) {
                problems.add("removed or changed, which breaks major 1: " + member);
            }
        }
        return problems;
    }

    /** Members of the contract without a line; the abstract additions are reported on their own. */
    private static List<String> notListed(Map<String, Integer> listed, Set<String> current, int minor,
                                          List<String> abstractAdditions) {
        List<String> problems = new ArrayList<>();
        for (String member : current) {
            if (!listed.containsKey(member) && !abstractAdditions.contains(member)) {
                problems.add("not in " + FROZEN_LIST + ": " + member + " — a new member comes with minor "
                        + (minor + 1) + " (append it as such and raise AdapterApiVersion.MINOR)");
            }
        }
        return problems;
    }

    /** One problem per abstract member on a type of an earlier minor version ({@link #abstractAdditions}). */
    private static List<String> abstractOnEarlierTypes(List<String> abstractAdditions) {
        List<String> problems = new ArrayList<>();
        for (String member : abstractAdditions) {
            problems.add("an abstract member on a type of an earlier minor version breaks major 1: " + member
                    + " — a plugin built against the earlier minor does not implement it (AbstractMethodError);"
                    + " add it as a default method (or on a new type)");
        }
        return problems;
    }

    /** The highest minor version of the list must be {@code minor}. */
    private static List<String> listBesideTheMinorVersion(int highest, int minor) {
        return highest == minor ? List.of()
                : List.of("the list reaches minor " + highest + ", AdapterApiVersion.MINOR is " + minor);
    }

    /**
     * Every minor version up to {@code last} has its fingerprint in {@code pinned} and matches it, and
     * {@code pinned} names no later one.
     */
    private static List<String> fingerprintsBesideTheList(Map<String, Integer> listed, int last, int minor,
                                                          Map<Integer, String> pinned) {
        List<String> problems = new ArrayList<>();
        for (int frozen = 0; frozen <= last; frozen++) {
            String expected = pinned.get(frozen);
            String actual = fingerprint(listed, frozen);
            if (expected == null) {
                problems.add("minor " + frozen + " has no fingerprint in FROZEN_MINORS (" + actual + ")");
            } else if (!expected.equals(actual)) {
                //Do not name the new fingerprint: it never belongs in FROZEN_MINORS
                problems.add("minor " + frozen + " is frozen (" + expected + "), the list differs from it — a new"
                        + " member belongs to minor " + (minor + 1));
            }
        }
        for (int extra : pinned.keySet()) {
            if (extra > last) {
                problems.add("a fingerprint for minor " + extra + " without such a minor version");
            }
        }
        return problems;
    }

    /**
     * The abstract members on a type of an earlier minor version: compares the
     * minor version of each member (without a line: {@code minor} + 1) with that
     * of the line of its type. Abstract means the modifier {@code abstract}
     * before the name (interface methods without {@code default} and abstract
     * class methods); a type without a line is new.
     */
    static List<String> abstractAdditions(Map<String, Integer> listed, Set<String> current, int minor) {
        Map<String, Integer> typeMinors = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : listed.entrySet()) {
            typeNameOf(entry.getKey()).ifPresent(type -> typeMinors.put(type, entry.getValue()));
        }
        Set<String> members = new TreeSet<>(listed.keySet());
        members.addAll(current);
        List<String> additions = new ArrayList<>();
        for (String member : members) {
            int paren = member.indexOf('(');
            if (paren < 0) {
                continue;
            }
            List<String> head = List.of(member.substring(0, paren).trim().split("\\s+"));
            String qualified = head.get(head.size() - 1);
            int dot = qualified.lastIndexOf('.');
            Integer typeMinor = dot < 0 ? null : typeMinors.get(qualified.substring(0, dot));
            int memberMinor = listed.getOrDefault(member, minor + 1);
            if (head.contains("abstract") && typeMinor != null && memberMinor > typeMinor) {
                additions.add(member);
            }
        }
        return additions;
    }

    /** The name of the type if the line is a type ({@code ... interface|class|enum|record|@interface <name>}). */
    private static java.util.Optional<String> typeNameOf(String line) {
        if (line.indexOf('(') >= 0) {
            return java.util.Optional.empty();
        }
        List<String> tokens = List.of(line.trim().split("\\s+"));
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (Set.of("interface", "class", "enum", "record", "@interface").contains(tokens.get(i))) {
                return java.util.Optional.of(tokens.get(i + 1));
            }
        }
        return java.util.Optional.empty();
    }

    /** Line count and fingerprint of one minor version. */
    static String fingerprint(Map<String, Integer> listed, int minor) {
        List<String> members = listed.entrySet().stream().filter(entry -> entry.getValue() == minor)
                .map(Map.Entry::getKey).sorted().toList();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", members).getBytes(StandardCharsets.UTF_8));
            return members.size() + " " + java.util.HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The visible members of the types in the notation of
     * {@code toGenericString()}: the type with modifiers and supertypes, fields
     * (including enum constants), constructors and methods (with modifiers such
     * as {@code default}, generics and {@code throws}); without synthetic and
     * bridge methods.
     */
    static List<String> members(List<Class<?>> types) {
        List<String> members = new ArrayList<>();
        for (Class<?> type : types) {
            StringBuilder line = new StringBuilder(typeInTheNotationOfJava17(type));
            Type superclass = type.getGenericSuperclass();
            if (superclass != null && superclass != Object.class && !type.isEnum() && !type.isRecord()) {
                line.append(" extends ").append(superclass.getTypeName());
            }
            Type[] interfaces = type.getGenericInterfaces();
            if (interfaces.length > 0) {
                line.append(type.isInterface() ? " extends " : " implements ").append(String.join(", ",
                        Stream.of(interfaces).map(Type::getTypeName).toList()));
            }
            members.add(line.toString());
            for (Field field : type.getDeclaredFields()) {
                if (visible(field)) {
                    members.add(field.toGenericString() + constantValueOf(field));
                }
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                if (visible(constructor)) {
                    members.add(constructor.toGenericString());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (visible(method) && !method.isBridge()) {
                    members.add(method.toGenericString());
                }
            }
        }
        return members;
    }

    /**
     * {@code toGenericString()} of the type as Java 17 writes it. Newer JDKs
     * (checked with 25) also print {@code sealed} and {@code non-sealed} there;
     * the frozen list was generated on Java 17, and the contract is the same
     * whichever JDK runs the test. Only modifiers before the kind of the type
     * are dropped, never a part of the name.
     */
    static String typeInTheNotationOfJava17(Class<?> type) {
        String[] words = type.toGenericString().split(" ", -1);
        List<String> kept = new ArrayList<>();
        boolean inModifiers = true;
        for (String word : words) {
            if (inModifiers && (word.equals("class") || word.equals("interface") || word.equals("enum")
                    || word.equals("record") || word.equals("@interface"))) {
                inModifiers = false;
            }
            if (inModifiers && (word.equals("sealed") || word.equals("non-sealed"))) {
                continue;
            }
            kept.add(word);
        }
        return String.join(" ", kept);
    }

    /**
     * {@code " = <value>"} for a constant (static final, primitive or
     * {@code String}; a string in quotes), otherwise empty. Without a value:
     * {@link #GROWING_CONSTANT}, enum constants and other objects (their text is
     * not stable, for example the order of a {@code Set.of}, and a plugin does
     * not compile them into its bytecode).
     */
    private static String constantValueOf(Field field) {
        int modifiers = field.getModifiers();
        boolean constant = Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers) && !field.isEnumConstant()
                && (field.getType().isPrimitive() || field.getType() == String.class);
        if (!constant || field.toGenericString().equals(GROWING_CONSTANT)) {
            return "";
        }
        try {
            field.setAccessible(true);
            Object value = field.get(null);
            return " = " + (value instanceof String text ? "\"" + text + "\"" : String.valueOf(value));
        } catch (IllegalAccessException | RuntimeException unreadable) {
            throw new IllegalStateException("the constant " + field + " cannot be read", unreadable);
        }
    }

    // --- Check ------------------------------------------------------------

    /**
     * All foreign types in the visible signatures and annotations of the type;
     * the transfer model types it reaches are added to {@code reached}.
     */
    private static List<String> foreignTypesIn(Class<?> type, Set<Class<?>> reached) {
        Check check = new Check(reached);
        String owner = type.getName();
        check.annotations(type, owner);
        check.type(type.getGenericSuperclass(), owner + " extends");
        for (Type implemented : type.getGenericInterfaces()) {
            check.type(implemented, owner + " implements");
        }
        for (TypeVariable<?> variable : type.getTypeParameters()) {
            check.type(variable, owner + "<" + variable.getName() + ">");
        }
        for (Field field : type.getDeclaredFields()) {
            if (visible(field)) {
                check.type(field.getGenericType(), owner + "." + field.getName());
                check.annotations(field, owner + "." + field.getName());
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (visible(constructor)) {
                check.executable(constructor, owner + ".<init>");
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (visible(method) && !method.isBridge()) {
                check.type(method.getGenericReturnType(), owner + "." + method.getName() + " returns");
                check.executable(method, owner + "." + method.getName());
            }
        }
        return check.violations;
    }

    private static boolean visible(Member member) {
        int modifiers = member.getModifiers();
        return !member.isSynthetic() && (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers));
    }

    /** Collects violations and the reached transfer model types. */
    private static final class Check {

        private final List<String> violations = new ArrayList<>();
        private final Set<Class<?>> reached;

        Check(Set<Class<?>> reached) {
            this.reached = reached;
        }

        void executable(Executable executable, String where) {
            annotations(executable, where);
            for (TypeVariable<?> variable : executable.getTypeParameters()) {
                type(variable, where + "<" + variable.getName() + ">");
            }
            for (Type parameter : executable.getGenericParameterTypes()) {
                type(parameter, where + " parameter");
            }
            for (Parameter parameter : executable.getParameters()) {
                annotations(parameter, where + " parameter");
            }
            for (Type thrown : executable.getGenericExceptionTypes()) {
                type(thrown, where + " throws");
            }
        }

        void annotations(AnnotatedElement element, String where) {
            for (Annotation annotation : element.getDeclaredAnnotations()) {
                type(annotation.annotationType(), where + " @");
            }
        }

        void type(Type type, String where) {
            type(type, where, new HashSet<>());
        }

        private void type(Type type, String where, Set<Type> seen) {
            if (type == null || !seen.add(type)) {
                return;
            }
            if (type instanceof Class<?> raw) {
                Class<?> element = raw;
                while (element.isArray()) {
                    element = element.getComponentType();
                }
                if (element.isPrimitive()) {
                    return;
                }
                if (ALLOWED_PREFIXES.stream().noneMatch(element.getName()::startsWith)) {
                    violations.add(where + ": " + element.getName());
                } else if (element.getName().startsWith(TRANSFER_MODEL)) {
                    reached.add(element);
                }
            } else if (type instanceof ParameterizedType parameterized) {
                type(parameterized.getRawType(), where, seen);
                type(parameterized.getOwnerType(), where, seen);
                for (Type argument : parameterized.getActualTypeArguments()) {
                    type(argument, where, seen);
                }
            } else if (type instanceof WildcardType wildcard) {
                Stream.concat(Stream.of(wildcard.getUpperBounds()), Stream.of(wildcard.getLowerBounds()))
                        .forEach(bound -> type(bound, where, seen));
            } else if (type instanceof TypeVariable<?> variable) {
                for (Type bound : variable.getBounds()) {
                    type(bound, where, seen);
                }
            } else if (type instanceof GenericArrayType array) {
                type(array.getGenericComponentType(), where, seen);
            }
        }
    }

    /**
     * All public types of the package and its subpackages, including nested
     * ones, read from the compiled classes.
     */
    private static List<Class<?>> publicApiTypes() throws IOException, URISyntaxException, ClassNotFoundException {
        Path root = Path.of(AdapterContext.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path packageDir = root.resolve(API_PACKAGE.replace('.', '/'));
        List<Class<?>> types = new ArrayList<>();
        try (Stream<Path> files = Files.walk(packageDir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                String relative = root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), ".");
                String name = relative.substring(0, relative.length() - ".class".length());
                if (name.endsWith("package-info") || name.endsWith("module-info")) {
                    continue;
                }
                Class<?> type = Class.forName(name, false, TestAdapterApiSignatures.class.getClassLoader());
                if (isPublicApi(type)) {
                    types.add(type);
                }
            }
        }
        return types;
    }

    private static boolean isPublicApi(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getDeclaringClass()) {
            if (!Modifier.isPublic(current.getModifiers()) || current.isAnonymousClass() || current.isSynthetic()) {
                return false;
            }
        }
        return true;
    }

    // --- Fixtures of the negative tests -----------------------------------

    /** Constants that are listed with and without their value. */
    public static final class Constants {
        public static final String KEY = "timeout.connect";
        public static final int LIMIT = 200;
        protected static final long SHIELDED = 7L;
        public static final List<String> NAMES = List.of("a", "b");
        public final int instance = 1;
    }

    /** Stands for a type outside the JDK and the contract (for example from Reactor). */
    public static final class Foreign extends Exception {
    }

    /** Stands for a foreign annotation (for example from Spring). */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface ForeignMarker {
    }

    /**
     * Six places with a foreign type: return type, generics, bound, exception,
     * annotation on a method and on a parameter.
     */
    public interface LeakyApi {
        Foreign direct();

        void nested(Map<String, List<? extends Foreign>> values);

        <T extends Foreign> void bound(T value);

        void failing() throws Foreign;

        @ForeignMarker
        void annotated();

        void parameter(@ForeignMarker String value);

        @Deprecated
        String fine(List<String> values) throws IOException;
    }
}
