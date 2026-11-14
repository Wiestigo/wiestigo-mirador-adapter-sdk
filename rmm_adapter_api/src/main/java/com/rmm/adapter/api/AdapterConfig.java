package com.rmm.adapter.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;

/**
 * Typed access to the configuration keys declared in
 * {@link AdapterDescriptor#configKeys()}. Keys are the relative names from the
 * declaration (for example {@code "home.url"}); the host resolves them in the
 * namespace {@code rmm.adapter.<systemKey>.*}.
 *
 * <p>The host checks required keys (keys without a default) before binding, so
 * every declared key always yields a value here: the configured one or the
 * declared default. Asking for an undeclared key is a programming error of the
 * adapter and throws {@link IllegalArgumentException}.
 *
 * <p>The typed accessors {@link #secret}, {@link #url}, {@link #limit},
 * {@link #certificate} and {@link #host()} are default methods on top of
 * {@link #string} and {@link #integer}; the host overrides them (masking,
 * platform caps, certificates).
 */
public interface AdapterConfig {

    String string(String key);

    int integer(String key);

    boolean bool(String key);

    Path path(String key);

    /**
     * A secret ({@link ConfigType#SECRET}), decrypted and masked. The value is
     * available only through {@link SecretValue#reveal()}.
     *
     * @throws IllegalArgumentException if the value is empty
     */
    default SecretValue secret(String key) {
        String value = string(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Configuration value <" + key + "> is empty — a secret needs a value");
        }
        return SecretValue.of(value);
    }

    /**
     * An address ({@link ConfigType#URL}): absolute, {@code http} or
     * {@code https}, with a host and without credentials. Whether it may be
     * reached is checked by the host when the value is saved and when a request
     * is sent. Host names with an underscore (container names) are allowed; an
     * internationalized name in Unicode is returned in Punycode ({@code xn--…}).
     *
     * @throws IllegalArgumentException if the value is not such an address;
     *                                  neither the message nor a cause contains
     *                                  the value (it may carry credentials)
     */
    default URI url(String key) {
        String value = string(key);
        URI uri;
        try {
            uri = HttpRules.asciiHost(new URI(value == null ? "" : value.trim()));
        } catch (URISyntaxException e) {
            // No cause: its message contains the whole value
            throw new IllegalArgumentException("Configuration value <" + key + "> is not a valid URL ("
                    + e.getReason() + " at index " + e.getIndex() + ")");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Configuration value <" + key + "> " + e.getMessage());
        }
        Optional<String> problem = HttpRules.httpUriProblem(uri);
        if (problem.isPresent()) {
            throw new IllegalArgumentException("Configuration value <" + key + "> " + problem.get());
        }
        return uri;
    }

    /**
     * A limit (rate, page size, threads, timeouts): the value of the connection,
     * capped by the host at the platform maximum
     * ({@code min(BINDING, PLATFORM)}). Never negative.
     *
     * @throws IllegalArgumentException if the value is negative
     */
    default int limit(String key) {
        int value = integer(key);
        if (value < 0) {
            throw new IllegalArgumentException("Configuration value <" + key + "> = <" + value
                    + "> must not be negative");
        }
        return value;
    }

    /**
     * The certificates of a {@link ConfigType#CERTIFICATE} key (PEM). A private
     * key in the same value stays in the host.
     *
     * <p><b>Every declared {@code CERTIFICATE} key is TLS material of the host's
     * HTTP path</b> ({@link AdapterHttp}, for every {@code https} request of the
     * binding): a value without a private key is a trust anchor of the
     * connection, a value with an unencrypted PKCS#8 key is its client
     * certificate. If the connection has trust anchors, the HTTP path trusts
     * only them; without anchors it trusts the public certificate authorities.
     * At most one value may carry a key; with two, every {@code https} request
     * fails ("not usable"). An adapter that needs a certificate for something
     * else (such as verifying the signature of a webhook or a token) therefore
     * declares it as {@link ConfigType#STRING} or {@link ConfigType#SECRET},
     * not as {@code CERTIFICATE}: otherwise it becomes a trust anchor and the
     * public authorities no longer apply to the binding.
     *
     * <p><b>{@link ConfigType#CLIENT_IDENTITY}</b> (since contract 1.1): the
     * public certificate of the client identity the host holds for the
     * connection; empty while it has none. No accessor returns the private key.
     *
     * @throws UnsupportedOperationException if this context does not provide certificates
     */
    default List<X509Certificate> certificate(String key) {
        throw new UnsupportedOperationException("AdapterConfig.certificate() is not provided by this host");
    }

    /** Limits and facts of the host; {@link HostLimits#unknown()} if none are provided. */
    default HostLimits host() {
        return HostLimits.unknown();
    }
}
