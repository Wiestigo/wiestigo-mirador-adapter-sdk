package com.rmm.adapter.api;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Authentication at the tool, registered for an origin through
 * {@link AdapterHttp#auth(java.net.URI, Auth)}. The host sets
 * {@code Authorization} only for requests to this origin and removes it on a
 * redirect to another one.
 *
 * <p>Secrets travel as {@link SecretValue}; the string form of the records
 * therefore never shows them.
 */
public sealed interface Auth {

    /**
     * Basic with user and password (RFC 7617, charset UTF-8).
     *
     * @throws IllegalArgumentException if UTF-8 cannot encode the user or the
     *         password (a lone surrogate), or the user is empty or contains
     *         {@code :} or control characters; the message contains no value
     */
    static Auth basic(String user, SecretValue password) {
        return new Basic(user, password, StandardCharsets.UTF_8);
    }

    /**
     * Basic with user and password in a named charset. RFC 7617 names UTF-8
     * (parameter {@code charset}), but some tools expect another one, for
     * example ISO-8859-1. The charset determines the bytes of
     * {@code user:password} before Base64; only users and passwords with
     * characters outside ASCII are sent differently. The host adds the
     * password and its Base64 form in this charset to the masking of the
     * binding.
     *
     * @throws IllegalArgumentException if the charset cannot encode the user
     *         or the password (the message contains neither value), or it is
     *         {@code null}
     */
    static Auth basic(String user, SecretValue password, Charset charset) {
        return new Basic(user, password, charset);
    }

    /** Bearer with a fixed token (such as a personal access token). */
    static Auth bearer(SecretValue token) {
        return new Bearer(token);
    }

    /** OAuth 2: the host holds and renews the token that the provider fetches. */
    static Auth oauth(TokenProvider provider) {
        return new OAuth(provider);
    }

    /**
     * Basic; the user contains no colon (RFC 7617). User and password are
     * encoded in {@code charset} (default UTF-8, see
     * {@link Auth#basic(String, SecretValue, Charset)}); the charset must be
     * able to encode both.
     */
    record Basic(String user, SecretValue password, Charset charset) implements Auth {

        public Basic {
            if (user == null || user.isEmpty() || user.indexOf(':') >= 0 || !HttpRules.hasNoControl(user)) {
                throw new IllegalArgumentException(
                        "basic auth user must not be empty or contain ':' or control characters");
            }
            if (password == null) {
                throw new IllegalArgumentException("basic auth password must not be null");
            }
            if (charset == null) {
                throw new IllegalArgumentException("basic auth charset must not be null");
            }
            // Never silently replaced by '?': the adapter would log in with a different password
            if (!charset.canEncode()) {
                throw new IllegalArgumentException("basic auth charset " + charset.name() + " cannot encode");
            }
            if (!encodes(charset, user)) {
                throw new IllegalArgumentException("basic auth user cannot be encoded in " + charset.name());
            }
            if (!encodes(charset, password.reveal())) {
                throw new IllegalArgumentException("basic auth password cannot be encoded in " + charset.name());
            }
        }

        /**
         * Basic in UTF-8 (RFC 7617).
         *
         * @throws IllegalArgumentException as for {@link Auth#basic(String, SecretValue)}
         */
        public Basic(String user, SecretValue password) {
            this(user, password, StandardCharsets.UTF_8);
        }

        /**
         * The Base64 form of {@code user:password} in {@link #charset()}, the
         * value after {@code Authorization: Basic}. A secret like the password:
         * only for sending and for masking.
         */
        public SecretValue token() {
            return SecretValue.of(Base64.getEncoder().encodeToString((user + ":" + password.reveal())
                    .getBytes(charset)));
        }

        private static boolean encodes(Charset charset, String value) {
            return charset.newEncoder().canEncode(value);
        }
    }

    /** Bearer with a fixed token. */
    record Bearer(SecretValue token) implements Auth {

        public Bearer {
            if (token == null) {
                throw new IllegalArgumentException("bearer token must not be null");
            }
            if (!HttpRules.isHeaderValue(token.reveal())) {
                throw new IllegalArgumentException("bearer token must not contain control characters");
            }
        }
    }

    /** OAuth 2 through a {@link TokenProvider}. */
    record OAuth(TokenProvider provider) implements Auth {

        public OAuth {
            if (provider == null) {
                throw new IllegalArgumentException("token provider must not be null");
            }
        }
    }
}
