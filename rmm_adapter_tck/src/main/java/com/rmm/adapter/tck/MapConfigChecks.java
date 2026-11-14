package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterConfig;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigScope;
import com.rmm.adapter.api.ConfigType;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * What the host refuses before a binding reads its configuration ({@link MapConfig}): a declaration with which
 * it would not load the adapter type, a value of the connection that Mirador's adapter administration would
 * not save, and a secret of the connection among the deployment's values. Every check throws
 * {@link IllegalArgumentException}; no message shows a value that can carry credentials or a key.
 */
final class MapConfigChecks {

    private MapConfigChecks() {
    }

    /**
     * Rejects a declaration the host would not load: a client identity, a reserved key of the HTTP path or a
     * cap {@code <name>.max} that is declared wrongly.
     */
    static void refuseWhatTheHostWouldNotLoad(Map<String, ConfigKey> declared) {
        refuseClientIdentitiesDeclaredWrongly(declared);
        refuseReservedKeysDeclaredWrongly(declared);
        refuseCapsDeclaredWrongly(declared);
    }

    /**
     * Rejects a client identity declared in scope {@code PLATFORM} or with a
     * non-empty default: the host keeps the identity per connection, so such a
     * declaration would have no effect. The message does not show the default.
     */
    private static void refuseClientIdentitiesDeclaredWrongly(Map<String, ConfigKey> declared) {
        for (ConfigKey declaration : declared.values()) {
            if (declaration.type() != ConfigType.CLIENT_IDENTITY) {
                continue;
            }
            if (declaration.scope() != ConfigScope.BINDING) {
                throw new IllegalArgumentException("Configuration key <" + declaration.key() + "> is a"
                        + " CLIENT_IDENTITY and must be declared in scope BINDING, not in scope "
                        + declaration.scope() + ": the host keeps the client identity per connection");
            }
            if (declaration.defaultValue() != null && !declaration.defaultValue().isBlank()) {
                throw new IllegalArgumentException("Configuration key <" + declaration.key() + "> is a"
                        + " CLIENT_IDENTITY in scope BINDING and must have no default (declare it with"
                        + " ConfigKey.required or the default \"\"): the host creates the client identity of a"
                        + " connection, a default is never read");
            }
        }
    }

    /**
     * Rejects a reserved key that is not declared as {@code INTEGER} in scope
     * {@code BINDING} or whose default is neither empty nor an integer of at
     * least 0. {@link #refuseCapsDeclaredWrongly} checks its cap.
     */
    private static void refuseReservedKeysDeclaredWrongly(Map<String, ConfigKey> declared) {
        for (String key : MapConfig.RESERVED_KEYS) {
            ConfigKey declaration = declared.get(key);
            if (declaration == null) {
                continue;
            }
            if (declaration.type() != ConfigType.INTEGER || declaration.scope() != ConfigScope.BINDING) {
                throw new IllegalArgumentException("Configuration key <" + key + "> is reserved for the network"
                        + " path of the host and must be declared as INTEGER in scope BINDING, not as "
                        + declaration.type() + " in scope " + declaration.scope());
            }
            String fallback = declaration.defaultValue();
            if (fallback != null && !fallback.isBlank() && !isLimit(fallback)) {
                throw new IllegalArgumentException("Configuration key <" + key + "> is reserved for the network"
                        + " path of the host and has the default <" + fallback + ">, not an integer of at least 0");
            }
        }
    }

    /**
     * Rejects a cap {@code <name>.max} of a declared key that is not
     * PLATFORM/INTEGER, has a default that is neither empty nor an integer of
     * at least 0, or caps a key that is not INTEGER. As in the host, an
     * unreadable deployment value is only reported on read ({@link MapConfig#limit}).
     */
    private static void refuseCapsDeclaredWrongly(Map<String, ConfigKey> declared) {
        for (ConfigKey cap : declared.values()) {
            if (!cap.key().endsWith(MapConfig.CAP_SUFFIX)) {
                continue;
            }
            String key = cap.key().substring(0, cap.key().length() - MapConfig.CAP_SUFFIX.length());
            ConfigKey capped = declared.get(key);
            if (capped == null) {
                continue;
            }
            if (cap.scope() != ConfigScope.PLATFORM || cap.type() != ConfigType.INTEGER) {
                throw new IllegalArgumentException("Configuration key <" + cap.key() + "> caps <" + key
                        + "> and must be declared as INTEGER in scope PLATFORM");
            }
            if (cap.defaultValue() != null && !cap.defaultValue().isBlank() && !isLimit(cap.defaultValue())) {
                throw new IllegalArgumentException("Configuration key <" + cap.key() + "> caps <" + key
                        + "> and has the default <" + cap.defaultValue() + ">, not an integer of at least 0");
            }
            if (capped.type() != ConfigType.INTEGER) {
                throw new IllegalArgumentException("Configuration key <" + cap.key() + "> caps <" + key
                        + ">, which is not declared as INTEGER");
            }
        }
    }

    private static boolean isLimit(String value) {
        try {
            return Integer.parseInt(value.trim()) >= 0;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /**
     * Rejects what Mirador's adapter administration rejects when a connection
     * value is saved; otherwise an adapter would pass the TCK with a
     * configuration that never exists in the host. No message shows a value
     * that can carry credentials or a key.
     *
     * @param connection the values of the connection
     * @param capOf      the platform cap of an INTEGER key, {@code null} without one; it throws
     *                   {@link IllegalArgumentException} for a cap that cannot be read
     */
    static void refuseWhatTheHostWouldNotSave(Map<String, ConfigKey> declared, Map<String, String> connection,
                                              Function<String, Integer> capOf) {
        for (Map.Entry<String, String> entry : connection.entrySet()) {
            String key = entry.getKey();
            ConfigKey declaration = declared.get(key);
            if (key.endsWith(MapConfig.CAP_SUFFIX)) {
                throw new IllegalArgumentException("Configuration key <" + key + "> is a platform limit"
                        + " (<name>.max): only the operator sets it — pass it with the deployment values"
                        + " (platform configuration), not with the connection");
            }
            if (declaration != null && declaration.scope() == ConfigScope.PLATFORM) {
                throw new IllegalArgumentException("Configuration key <" + key + "> is declared in scope PLATFORM:"
                        + " only the operator sets it — pass it with the deployment values (platform"
                        + " configuration), not with the connection");
            }
            String value = MapConfig.nonBlank(entry.getValue());
            if (declaration == null || value == null) {
                continue;
            }
            if (declaration.type() == ConfigType.CLIENT_IDENTITY) {
                throw new IllegalArgumentException("Configuration key <" + key + "> is declared as CLIENT_IDENTITY:"
                        + " the host creates and keeps the client identity of a connection and never saves a value"
                        + " for it");
            }
            int longest = declaration.type() == ConfigType.CERTIFICATE ? TckPem.MAX_CHARS : MapConfig.MAX_VALUE_LENGTH;
            if (entry.getValue().length() > longest) {
                throw new IllegalArgumentException("Configuration value <" + key + "> exceeds " + longest
                        + " characters — the host does not save a longer value for a connection");
            }
            switch (declaration.type()) {
                case INTEGER -> refuseAboveTheCap(key, value, capOf);
                case URL -> new Single(key, value).url(key);
                case CERTIFICATE -> TckPem.parse(value, "Configuration value <" + key + ">");
                default -> {
                    //the host does not check other types on save
                }
            }
        }
    }

    private static void refuseAboveTheCap(String key, String value, Function<String, Integer> capOf) {
        long number;
        try {
            number = Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("Configuration value <" + key + "> = <" + value
                    + "> is not an integer");
        }
        Integer maximum;
        try {
            maximum = capOf.apply(key);
        } catch (IllegalArgumentException unreadable) {
            //An unreadable cap is reported when the adapter reads it (limit), not on save, as in the host
            return;
        }
        if (maximum != null && number > maximum) {
            throw new IllegalArgumentException("Configuration value <" + key + "> = " + number + " is above the"
                    + " platform limit of " + maximum + " (" + key + MapConfig.CAP_SUFFIX + ") — choose at most "
                    + maximum);
        }
    }

    /**
     * Rejects a secret of the connection (see {@link MapConfig#isSecret}) in the
     * deployment's values. A multi-tenant host never reads it there, so a test
     * would pass in the TCK with a value the binding does not have in the host.
     * The message does not show the value.
     */
    static void refuseSecretsOfTheConnectionInTheDeployment(Map<String, ConfigKey> declared,
                                                            Map<String, String> deployment) {
        for (String key : deployment.keySet()) {
            ConfigKey declaration = declared.get(key);
            if (declaration != null && declaration.type() == ConfigType.CLIENT_IDENTITY
                    && MapConfig.nonBlank(deployment.get(key)) != null) {
                throw new IllegalArgumentException("Configuration key <" + key + "> is declared as CLIENT_IDENTITY:"
                        + " the host creates and keeps the client identity of a connection and never reads a value"
                        + " for it, neither from the connection nor from the deployment");
            }
            if (declaration != null && declaration.scope() == ConfigScope.BINDING && MapConfig.isSecret(declaration)
                    && MapConfig.nonBlank(deployment.get(key)) != null) {
                throw new IllegalArgumentException("Configuration key <" + key + "> is a secret of the connection"
                        + " (" + declaration.type() + (declaration.type() == ConfigType.SECRET
                        || declaration.type() == ConfigType.CERTIFICATE ? "" : ", by its name") + "): in"
                        + " multi-tenant mode the host never reads it from the deployment — pass it with the"
                        + " connection values (configuration()), not with the platform configuration");
            }
        }
    }

    /** A single value, checked by the rule of {@link AdapterConfig#url}. */
    private record Single(String key, String value) implements AdapterConfig {

        @Override
        public String string(String requested) {
            return value;
        }

        @Override
        public int integer(String requested) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean bool(String requested) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path path(String requested) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toString() {
            return "Single[" + key + "]";
        }
    }
}
