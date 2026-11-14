package com.rmm.adapter.tck;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The host's rules for the client identity of a connection: what a usable identity is, which binding its
 * certificate names, and when a type that declares {@code CLIENT_IDENTITY} is bound.
 */
final class TckClientIdentity {

    private TckClientIdentity() {
    }

    /**
     * Reads an identity: a PEM with the certificate and its unencrypted private key (PKCS#8).
     *
     * @throws IllegalArgumentException if the value is not a usable PEM or the key is missing; the message does
     *                                  not contain the value
     */
    static TckPem.Material parse(String pem) {
        TckPem.Material identity = TckPem.parse(Objects.requireNonNull(pem, "pem"), "The client identity");
        if (identity.privateKey().isEmpty()) {
            throw new IllegalArgumentException("The client identity is not a valid PEM certificate: it holds no"
                    + " private key - pass the certificate and its unencrypted PKCS#8 key (BEGIN PRIVATE KEY)");
        }
        return identity;
    }

    /**
     * The binding that a client identity certificate names: the rest of an alternative name
     * {@code URI:}{@value TckAdapterContext#BINDING_URN_PREFIX}...; empty if there is none.
     */
    static Optional<String> bindingNamedBy(X509Certificate certificate) {
        String prefix = TckAdapterContext.BINDING_URN_PREFIX;
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names == null) {
                return Optional.empty();
            }
            for (List<?> name : names) {
                //6 = uniformResourceIdentifier (RFC 5280)
                if (name.size() == 2 && Integer.valueOf(6).equals(name.get(0))
                        && name.get(1) instanceof String uri && uri.startsWith(prefix)
                        && uri.length() > prefix.length()) {
                    return Optional.of(uri.substring(prefix.length()));
                }
            }
            return Optional.empty();
        } catch (CertificateParsingException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * A type with {@code CLIENT_IDENTITY} binds only with an identity that has not expired, as in the host
     * before {@code bind} (without one: {@code NOT_CONFIGURED ACCESS_NOT_CONFIGURED}; expired:
     * {@code DEFECT CERTIFICATE_EXPIRED}). As in the host, only {@code notAfter} counts.
     *
     * @throws IllegalStateException if the type declares such a key and the connection has no identity or an
     *                               expired one
     */
    static void requireUsable(MapConfig config) {
        if (!config.declaresClientIdentity()) {
            return;
        }
        TckPem.Material identity = config.clientIdentity().orElseThrow(() -> new IllegalStateException(
                "The connection has no client identity — the host binds a type that declares CLIENT_IDENTITY"
                        + " only with one (NOT_CONFIGURED ACCESS_NOT_CONFIGURED); pass it via clientIdentity()"));
        if (!Instant.now().isBefore(identity.certificates().get(0).getNotAfter().toInstant())) {
            throw new IllegalStateException("The client certificate of the connection has expired - renew it;"
                    + " the host does not bind with it (DEFECT CERTIFICATE_EXPIRED)");
        }
    }
}
