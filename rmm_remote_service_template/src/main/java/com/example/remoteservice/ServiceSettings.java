package com.example.remoteservice;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;

/**
 * What the operator gives the service: address and port, the key store with
 * the key and certificate of the service (PKCS#12; the Mirador administrator
 * stores its certificate as {@code tls.trust} of the connection) and the
 * pinned client certificates of the connections ({@link PinStore}).
 *
 * @param address          where the service listens
 * @param keyStore         the key store of the service (PKCS#12, exactly one key)
 * @param keyStorePassword its password (also the password of the key)
 * @param pins             the pinned client certificates
 */
public record ServiceSettings(InetSocketAddress address, Path keyStore, char[] keyStorePassword, PinStore pins) {

    /** The environment variables of {@link #fromEnvironment}. */
    public static final String ADDRESS = "REMOTE_SERVICE_ADDRESS";
    public static final String PORT = "REMOTE_SERVICE_PORT";
    public static final String KEY_STORE = "REMOTE_SERVICE_KEYSTORE";
    public static final String KEY_STORE_PASSWORD = "REMOTE_SERVICE_KEYSTORE_PASSWORD";
    public static final String PIN_DIRECTORY = "REMOTE_SERVICE_PINS";

    /** Defaults: all addresses, port 8443. */
    static final String DEFAULT_ADDRESS = "0.0.0.0";
    static final int DEFAULT_PORT = 8443;

    /**
     * The settings from the environment: {@value #ADDRESS} (default
     * {@value #DEFAULT_ADDRESS}), {@value #PORT} (default 8443) and, required,
     * {@value #KEY_STORE}, {@value #KEY_STORE_PASSWORD} and
     * {@value #PIN_DIRECTORY} (the directory of the pinned certificates).
     */
    public static ServiceSettings fromEnvironment(Map<String, String> environment) {
        String address = environment.getOrDefault(ADDRESS, DEFAULT_ADDRESS);
        int port;
        try {
            port = Integer.parseInt(environment.getOrDefault(PORT, String.valueOf(DEFAULT_PORT)).trim());
        } catch (NumberFormatException unreadable) {
            throw new IllegalArgumentException(PORT + " is no port number");
        }
        return new ServiceSettings(new InetSocketAddress(address, port), Path.of(required(environment, KEY_STORE)),
                required(environment, KEY_STORE_PASSWORD).toCharArray(),
                PinStore.ofDirectory(Path.of(required(environment, PIN_DIRECTORY))));
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("the environment variable " + name + " is required");
        }
        return value;
    }

    /** Omits the password. */
    @Override
    public String toString() {
        return "ServiceSettings[" + address + ", key store " + keyStore + "]";
    }
}
