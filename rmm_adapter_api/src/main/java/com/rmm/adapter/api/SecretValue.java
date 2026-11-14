package com.rmm.adapter.api;

/**
 * A decrypted secret of the connection (token, password) as returned by
 * {@link AdapterConfig#secret(String)}. The value is available only through
 * {@link #reveal()}; {@link #toString()} never shows it. This keeps a secret
 * from ending up by accident in a log line, an error message or the output of a
 * record that carries it (such as {@link Auth.Bearer}).
 *
 * <p>Equality is object identity, not value equality: a comparison through
 * {@code equals} must not compare secrets as a side effect.
 */
public final class SecretValue {

    private final String value;

    private SecretValue(String value) {
        this.value = value;
    }

    /** A secret from a non-empty value. */
    public static SecretValue of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("secret must not be empty");
        }
        return new SecretValue(value);
    }

    /** The value in plain text: only for sending, never for logging. */
    public String reveal() {
        return value;
    }

    /** Masked; never shows the value. */
    @Override
    public String toString() {
        return "SecretValue[****]";
    }
}
