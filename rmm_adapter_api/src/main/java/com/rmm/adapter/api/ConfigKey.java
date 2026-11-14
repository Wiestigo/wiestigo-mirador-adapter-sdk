package com.rmm.adapter.api;

/**
 * A configuration key declared by the adapter. The host resolves it in the
 * enforced namespace {@code rmm.adapter.<systemKey>.<key>} and passes it, typed,
 * into the {@link AdapterConfig}. The namespace rules out collisions between
 * adapters.
 *
 * @param key          relative key, for example {@code "home.url"}, without the
 *                     namespace prefix; the host adds that.
 * @param type         value type; {@link ConfigType#SECRET} marks values for
 *                     masked logging and secret scanning.
 * @param defaultValue default value; null = required key, whose absence
 *                     prevents the adapter from binding (with a clear message,
 *                     not only during operation).
 * @param description  appears in documentation and in the administration.
 * @param scope        scope of the value; {@link ConfigScope#BINDING} is the
 *                     default. {@link ConfigType#PATH} in scope BINDING is
 *                     rejected by the host and the TCK, not by this
 *                     constructor; otherwise a descriptor would already fail
 *                     when it is constructed.
 */
public record ConfigKey(String key, ConfigType type, String defaultValue, String description, ConfigScope scope) {

    public ConfigKey {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        if (key.startsWith("rmm.")) {
            throw new IllegalArgumentException("key '" + key
                    + "' must be declared relative — the host adds the namespace rmm.adapter.<systemKey>.");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException(
                    "description must not be empty — it appears in documentation and diagnostics");
        }
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
    }

    /** A key in scope {@link ConfigScope#BINDING}. */
    public ConfigKey(String key, ConfigType type, String defaultValue, String description) {
        this(key, type, defaultValue, description, ConfigScope.BINDING);
    }

    /** A required key without a default, in scope {@link ConfigScope#BINDING}. */
    public static ConfigKey required(String key, ConfigType type, String description) {
        return new ConfigKey(key, type, null, description);
    }

    /** An optional key with a default, in scope {@link ConfigScope#BINDING}. */
    public static ConfigKey optional(String key, ConfigType type, String defaultValue, String description) {
        if (defaultValue == null) {
            throw new IllegalArgumentException("Optional keys need a default — use required(...) otherwise");
        }
        return new ConfigKey(key, type, defaultValue, description);
    }

    /**
     * A key in scope {@link ConfigScope#PLATFORM}: set by the operator,
     * invisible to the tenant. {@code defaultValue} null = required key.
     */
    public static ConfigKey platform(String key, ConfigType type, String defaultValue, String description) {
        return new ConfigKey(key, type, defaultValue, description, ConfigScope.PLATFORM);
    }

    public boolean isRequired() {
        return defaultValue == null;
    }
}
