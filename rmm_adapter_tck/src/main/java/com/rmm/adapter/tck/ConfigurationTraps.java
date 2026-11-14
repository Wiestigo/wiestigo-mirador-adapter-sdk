package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.ConfigKey;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.rmm.adapter.tck.AdapterContractTest.TRAP_PREFIX;

/**
 * The trap values of case 5: system properties and environment variables
 * that an adapter reads only if it takes configuration from somewhere else
 * than {@code config()} of its context.
 */
final class ConfigurationTraps {

    /**
     * The TCK does not set bare keys with these prefixes: the JDK itself
     * reads under them (proxy, trust store), so a trap there would hit the
     * HTTP path of the adapter, not its configuration.
     */
    private static final List<String> JDK_PROPERTY_PREFIXES = List.of("java.", "javax.", "jdk.", "sun.", "http.",
            "https.", "socks", "ftp.", "file.", "user.", "os.", "line.", "path.");

    private ConfigurationTraps() {
    }

    /**
     * Two system properties per declared key, with the same trap value.
     * Texts carry {@link AdapterContractTest#TRAP_PREFIX} and so stand out
     * in any output; numbers and booleans show in the result (a page size of
     * 1 changes the page count of the reading as soon as there is more than
     * one element).
     */
    static Map<String, String> systemPropertyTraps(AdapterDescriptor descriptor, Map<String, String> configuration) {
        String token = TRAP_PREFIX + "-" + UUID.randomUUID().toString().substring(0, 8);
        String namespace = "rmm.adapter." + descriptor.systemKey().toLowerCase(Locale.ROOT) + ".";
        Map<String, String> traps = new LinkedHashMap<>();
        for (ConfigKey key : descriptor.configKeys()) {
            String resolved = configuration.getOrDefault(key.key(), String.valueOf(key.defaultValue())).trim();
            String value = switch (key.type()) {
                //1 (or 2): changes the page count as soon as there is more than one element
                case INTEGER -> "1".equals(resolved) ? "2" : "1";
                case BOOLEAN -> String.valueOf(!Boolean.parseBoolean(resolved));
                case URL -> "https://" + token + ".invalid/" + key.key();
                case PATH -> "/" + token + "/" + key.key();
                default -> token + "-" + key.key();
            };
            if (JDK_PROPERTY_PREFIXES.stream().noneMatch(key.key()::startsWith)) {
                traps.put(key.key(), value);
            }
            traps.put(namespace + key.key(), value);
        }
        return traps;
    }

    /** Sets the system properties {@code traps} and returns what they held before, {@code null} for an unset one. */
    static Map<String, String> set(Map<String, String> traps) {
        Map<String, String> previous = new HashMap<>();
        traps.forEach((property, value) -> {
            previous.put(property, System.getProperty(property));
            System.setProperty(property, value);
        });
        return previous;
    }

    /** Puts the system properties back to what {@link #set} found. */
    static void restore(Map<String, String> previous) {
        previous.forEach((property, value) -> {
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        });
    }

    /** The trap values of the environment: every value that starts with {@link AdapterContractTest#TRAP_PREFIX}. */
    static Set<String> environmentTraps() {
        Set<String> traps = new LinkedHashSet<>();
        System.getenv().values().stream().filter(value -> value != null && value.startsWith(TRAP_PREFIX))
                .forEach(traps::add);
        return traps;
    }

    /**
     * No trap value appears in what a binding read ({@code reading},
     * {@code null} if it read nothing) or left behind ({@code traces}).
     */
    static void requireNoTraps(ToolReading reading, List<String> traces, Set<String> trapValues, String when) {
        for (String trap : trapValues) {
            if (reading != null && reading.text().contains(trap)) {
                throw new TckViolation("The trap value <" + trap + "> appears in the elements read " + when
                        + " — the adapter takes configuration from system properties or the environment;"
                        + " only config() of its context is allowed");
            }
            for (String trace : traces) {
                if (trace != null && trace.contains(trap)) {
                    throw new TckViolation("The trap value <" + trap + "> appears in the log or store " + when
                            + " — the adapter takes configuration from system properties or the environment;"
                            + " only config() of its context is allowed: " + trace);
                }
            }
        }
    }
}
