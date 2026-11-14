package com.rmm.adapter.api;

/** Value type of a declared configuration key. */
public enum ConfigType {
    STRING,
    INTEGER,
    BOOLEAN,
    /** A path in the host's file system; allowed only in scope {@link ConfigScope#PLATFORM}. */
    PATH,
    /** Like STRING, but masked in logs and marked for secret scanning. */
    SECRET,
    /**
     * An address. The type lets the host recognize addresses, for the address
     * check and for the fresh second factor the administration requires for
     * them.
     */
    URL,
    /**
     * A certificate (PEM), recognizable by its type like addresses and
     * secrets. Every such key is TLS material of the host's HTTP path; see
     * {@link AdapterConfig#certificate}.
     */
    CERTIFICATE,
    /**
     * The client identity of the connection (since contract 1.1): a key pair
     * (ECDSA P-256) and a self-signed certificate with the {@code bindingId} in
     * its SAN, valid for one year. <b>The host</b> creates and holds it per
     * connection; the administration accepts no value for such a key. Through
     * {@link AdapterConfig#string} and {@link AdapterConfig#certificate} the
     * adapter reads only the public certificate and never sees the private
     * key; the host's HTTP path ({@link AdapterHttp}) uses it to authenticate
     * every {@code https} request of the binding. Meaningful only in scope
     * {@link ConfigScope#BINDING}.
     *
     * <p>The key does not leak, but the identity leaves a trace: the
     * certificate, and with it the {@code connectionId} in its SAN, is shown to
     * every {@code https} peer that the binding contacts and that requests a
     * client certificate. An adapter with this key therefore contacts only
     * addresses of the connection (a remote adapter only its {@code url}).
     */
    CLIENT_IDENTITY
}
