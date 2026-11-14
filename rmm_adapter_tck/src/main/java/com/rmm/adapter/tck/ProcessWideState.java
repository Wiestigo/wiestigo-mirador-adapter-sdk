package com.rmm.adapter.tck;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * TCK case 3: no process-wide state. An adapter type is loaded once per
 * process and shared by all bindings of all tenants, so a static field that
 * can change is state that crosses between bindings.
 *
 * <p>The check uses reflection:</p>
 * <ul>
 *   <li>a static field that is not {@code final} is a violation;</li>
 *   <li>a {@code static final} field is allowed only as a constant: a
 *       primitive or its wrapper, {@code String}, an enum, {@code Pattern},
 *       immutable JDK values ({@code java.time}, {@code UUID},
 *       {@code BigDecimal}, {@code Charset}, {@code Locale}, {@code URI},
 *       {@code Class}) or a logger of a well-known library;</li>
 *   <li>a collection or map is a constant if its value is immutable:
 *       {@code List.of}, {@code Set.of}, {@code Map.of}/{@code ofEntries},
 *       {@code Collections.unmodifiable…},
 *       {@code Collections.empty…}/{@code singleton…}, or an
 *       {@code EnumSet};</li>
 *   <li>a function ({@code java.util.function}, {@code Comparator},
 *       {@code Runnable}, {@code Callable}) is a constant if it holds no
 *       state: a lambda or method reference whose captured values are
 *       themselves constants;</li>
 *   <li>the constants of an enum exist once per process: their instance
 *       fields must be {@code final} and hold constants.</li>
 * </ul>
 * <p>Arrays, mutable collections, clients, mappers and everything else are
 * violations. For collections, functions and enums the case reads the
 * values and initializes the class to do so, as the host does when binding.
 * Compiler-generated fields ({@code $VALUES}, {@code $assertionsDisabled},
 * {@code $SwitchMap$…}) are ignored.</p>
 *
 * <p>Limits: a {@code Collections.unmodifiable…} wrapper around a collection
 * that the adapter changes elsewhere, and an {@code EnumSet} that someone
 * changes later, count as constants. JDK functions with captured values
 * ({@code Comparator.comparing(...)}) cannot be inspected and count as
 * stateless.</p>
 */
public final class ProcessWideState {

    private static final Set<String> IMMUTABLE_VALUES = Set.of(
            "java.lang.String", "java.lang.Class", "java.util.regex.Pattern", "java.util.UUID",
            "java.math.BigDecimal", "java.math.BigInteger", "java.nio.charset.Charset", "java.util.Locale",
            "java.net.URI", "java.lang.Boolean", "java.lang.Character", "java.lang.Byte", "java.lang.Short",
            "java.lang.Integer", "java.lang.Long", "java.lang.Float", "java.lang.Double");

    /** Loggers of well-known libraries only; an adapter's own class such as {@code AuditLogger} is no logger. */
    private static final Set<String> LOGGERS = Set.of(
            "java.util.logging.Logger", "java.lang.System$Logger", "org.slf4j.Logger",
            "org.apache.logging.log4j.Logger", "org.apache.logging.log4j.spi.ExtendedLogger",
            "org.apache.commons.logging.Log", "ch.qos.logback.classic.Logger", "org.jboss.logging.Logger");

    private static final Set<String> FUNCTIONS = Set.of(
            "java.util.Comparator", "java.lang.Runnable", "java.util.concurrent.Callable");

    /** The packages of the TCK and of the contract: never adapter code. */
    private static final String TCK_PACKAGE = "com.rmm.adapter.tck";
    private static final String API_PACKAGE = "com.rmm.adapter.api";

    /** How deep the check follows the captured values of a function. */
    private static final int MAX_DEPTH = 4;

    private ProcessWideState() {
    }

    /** The violations in the classes, one line per field; empty if there are none. */
    public static List<String> violations(Set<Class<?>> classes) {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : classes) {
            if (type.isSynthetic()) {
                continue;
            }
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (LinkageError unresolved) {
                //The host cannot load a class whose fields refer to missing types
                //either; binding reports that there, not this case
                continue;
            }
            for (Field field : fields) {
                String finding = Modifier.isStatic(field.getModifiers()) ? judgeStatic(field)
                        : type.isEnum() ? judgeEnumField(type, field) : null;
                if (finding != null) {
                    violations.add(type.getName() + "." + field.getName() + " (" + field.getType().getTypeName()
                            + "): " + finding);
                }
            }
        }
        return violations;
    }

    private static String judgeStatic(Field field) {
        if (field.isSynthetic() || field.getName().startsWith("$") || field.isEnumConstant()) {
            return null;
        }
        if (!Modifier.isFinal(field.getModifiers())) {
            return "static and not final — shared by every binding of every tenant";
        }
        Class<?> type = field.getType();
        if (type.getName().startsWith(API_PACKAGE + ".")) {
            return "a static part of the contract — logger, store and config belong to one binding";
        }
        if (isConstantType(type)) {
            return null;
        }
        if (type.isArray()) {
            return "a static array — its elements stay writable for every binding";
        }
        if (java.util.Collection.class.isAssignableFrom(type) || java.util.Map.class.isAssignableFrom(type)) {
            Value value = valueOf(field, null);
            if (value.failure() != null) {
                return "a static collection whose value could not be read (" + value.failure() + ")";
            }
            return isImmutableCollection(value.value()) ? null
                    : "a mutable static collection (" + value.value().getClass().getName() + ") — shared by every"
                    + " binding; use List.of/Set.of/Map.of for constants, keep state in the AdapterInstance";
        }
        if (isFunctionType(type)) {
            Value value = valueOf(field, null);
            if (value.failure() != null) {
                return "a static function whose value could not be read (" + value.failure() + ")";
            }
            return isConstantValue(value.value(), 0) ? null
                    : "a static function that keeps state — every binding would share what it remembers";
        }
        return "a static object — clients, mappers, caches and holders belong to the AdapterInstance, not to"
                + " the type";
    }

    /** An instance field of an enum: every constant exists once per process. */
    private static String judgeEnumField(Class<?> enumType, Field field) {
        if (field.isSynthetic()) {
            return null;
        }
        if (!Modifier.isFinal(field.getModifiers())) {
            return "an enum constant is one object per process — a field that is not final is shared state";
        }
        if (isConstantType(field.getType())) {
            return null;
        }
        Object[] constants;
        try {
            Class.forName(enumType.getName(), true, enumType.getClassLoader());
            constants = enumType.getEnumConstants();
        } catch (Throwable unreadable) {
            return "a field of an enum constant whose value could not be read (" + unreadable + ")";
        }
        for (Object constant : constants == null ? new Object[0] : constants) {
            Value value = valueOf(field, constant);
            if (value.failure() != null) {
                return "a field of an enum constant whose value could not be read (" + value.failure() + ")";
            }
            if (!isConstantValue(value.value(), 0)) {
                return "an enum constant is one object per process — " + constant + " holds a mutable "
                        + value.value().getClass().getName();
            }
        }
        return null;
    }

    private static boolean isConstantType(Class<?> type) {
        return type.isPrimitive() || type.isEnum() || IMMUTABLE_VALUES.contains(type.getName())
                || type.getName().startsWith("java.time.") || LOGGERS.contains(type.getName());
    }

    private static boolean isFunctionType(Class<?> type) {
        return type.getName().startsWith("java.util.function.") || FUNCTIONS.contains(type.getName());
    }

    private static boolean isImmutableCollection(Object value) {
        if (value == null || value instanceof EnumSet) {
            return true;
        }
        String name = value.getClass().getName();
        return name.startsWith("java.util.ImmutableCollections$") || name.startsWith("java.util.Collections$Unmodifiable")
                || name.startsWith("java.util.Collections$Empty") || name.startsWith("java.util.Collections$Singleton");
    }

    /**
     * Whether a value is a constant: an immutable value, an immutable
     * collection, an enum or a stateless function, whose captured values are
     * checked recursively.
     */
    private static boolean isConstantValue(Object value, int depth) {
        if (value == null || value instanceof Enum<?> || isImmutableCollection(value)) {
            return true;
        }
        Class<?> type = value.getClass();
        if (isConstantType(type)) {
            return true;
        }
        if (depth > MAX_DEPTH) {
            return false;
        }
        boolean function = isFunctionType(type) || java.util.Arrays.stream(type.getInterfaces())
                .anyMatch(ProcessWideState::isFunctionType);
        if (!function) {
            return false;
        }
        if (type.getName().startsWith("java.") && type.getModule().isNamed()) {
            //JDK functions (Comparator.comparing, naturalOrder) cannot be
            //inspected; they hold only what the caller passed in
            return true;
        }
        for (Class<?> level = type; level != null && level != Object.class; level = level.getSuperclass()) {
            for (Field field : level.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!Modifier.isFinal(field.getModifiers())) {
                    return false;
                }
                Value captured = valueOf(field, value);
                if (captured.failure() != null || !isConstantValue(captured.value(), depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A value that was read, or the reason why it could not be read. */
    private record Value(Object value, String failure) {
    }

    private static Value valueOf(Field field, Object owner) {
        try {
            if (owner == null) {
                Class.forName(field.getDeclaringClass().getName(), true, field.getDeclaringClass().getClassLoader());
            }
            field.setAccessible(true);
            return new Value(field.get(owner), null);
        } catch (Throwable unreadable) {
            return new Value(null, unreadable.getClass().getSimpleName() + ": " + unreadable.getMessage());
        }
    }

    /**
     * The classes of an adapter, derived from the code source of its class
     * (a directory, a jar, or the URL of a plugin unit that delivers the
     * jar, {@code rmm-plugin:<sha256>!/}): all class files there except the
     * packages of the TCK ({@code com.rmm.adapter.tck}, not its subpackages)
     * and of the contract ({@code com.rmm.adapter.api}). The adapter's own
     * package is always included.
     *
     * @throws TckViolation if the code source cannot be determined or read;
     *                      the author then names the classes in
     *                      {@code adapterClasses()}
     */
    public static Set<Class<?>> classesOf(Class<?> adapterClass) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        List<String> unloadable = new ArrayList<>();
        for (String name : classNamesBeside(adapterClass)) {
            try {
                classes.add(Class.forName(name, false, adapterClass.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError notLoadable) {
                unloadable.add(name);
            }
        }
        if (!unloadable.isEmpty()) {
            System.out.println("[TCK] Case 3: " + unloadable.size() + " classes of the code source of "
                    + adapterClass.getName() + " cannot be loaded and are not checked: " + unloadable);
        }
        return classes;
    }

    private static List<String> classNamesBeside(Class<?> adapterClass) {
        java.net.URL source = PluginUnit.codeSourceOf(adapterClass);
        if (source == null) {
            throw new TckViolation("The code source of " + adapterClass.getName() + " cannot be determined —"
                    + " case 3 would check nothing. Name the adapter's classes in adapterClasses()");
        }
        String ownPackage = adapterClass.getPackageName();
        try {
            List<String> files;
            if (!"file".equalsIgnoreCase(source.getProtocol())) {
                //A unit the host loads from bytes (rmm-plugin:<sha256>!/): the code source delivers the
                //jar itself
                files = entryNamesOf(source);
                return files.stream().map(ProcessWideState::binaryName)
                        .filter(name -> name != null && belongsToTheAdapter(name, ownPackage))
                        .sorted().toList();
            }
            Path location = Path.of(source.toURI());
            if (Files.isDirectory(location)) {
                try (Stream<Path> walk = Files.walk(location)) {
                    files = walk.filter(file -> file.toString().endsWith(".class"))
                            .map(file -> location.relativize(file).toString().replace('\\', '/')).toList();
                }
            } else {
                try (JarFile jar = new JarFile(location.toFile())) {
                    files = jar.stream().map(JarEntry::getName).filter(name -> name.endsWith(".class")).toList();
                }
            }
            return files.stream().map(ProcessWideState::binaryName)
                    .filter(name -> name != null && belongsToTheAdapter(name, ownPackage))
                    .sorted().toList();
        } catch (IOException | URISyntaxException | IllegalArgumentException | java.nio.file.FileSystemNotFoundException
                 unreadable) {
            throw new TckViolation("The code source " + source + " of " + adapterClass.getName()
                    + " cannot be read (" + unreadable + ") — name the adapter's classes in adapterClasses()");
        }
    }

    /** The class files of the jar the URL delivers. */
    private static List<String> entryNamesOf(java.net.URL unit) throws IOException {
        java.net.URLConnection connection = unit.openConnection();
        connection.setUseCaches(false);
        List<String> names = new ArrayList<>();
        try (java.util.zip.ZipInputStream jar = new java.util.zip.ZipInputStream(connection.getInputStream())) {
            for (java.util.zip.ZipEntry entry = jar.getNextEntry(); entry != null; entry = jar.getNextEntry()) {
                if (entry.getName().endsWith(".class")) {
                    names.add(entry.getName());
                }
            }
        }
        return names;
    }

    private static boolean belongsToTheAdapter(String className, String ownPackage) {
        int dot = className.lastIndexOf('.');
        String packageName = dot < 0 ? "" : className.substring(0, dot);
        if (packageName.equals(ownPackage) || packageName.startsWith(ownPackage + ".")) {
            return true;
        }
        return !packageName.equals(TCK_PACKAGE) && !packageName.equals(API_PACKAGE)
                && !packageName.startsWith(API_PACKAGE + ".") && !className.startsWith("META-INF");
    }

    private static String binaryName(String classFile) {
        String name = classFile.substring(0, classFile.length() - ".class".length()).replace('/', '.');
        if (name.endsWith("module-info") || name.endsWith("package-info")) {
            return null;
        }
        return name;
    }
}
