package com.rmm.adapter.tck;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The TLS material of one binding in the TCK. The TCK applies the same rules
 * as the host:
 *
 * <ul>
 *   <li><b>Trust anchors:</b> every declared {@code CERTIFICATE} value
 *       without a private key. With anchors, only they are trusted; without
 *       anchors, the public CAs of the JDK's trust store.</li>
 *   <li><b>Client certificate:</b> a {@code CERTIFICATE} value with a PKCS#8
 *       key, or the connection's client identity ({@code CLIENT_IDENTITY}).
 *       At most one; with two the material is unusable and every
 *       {@code https} request fails.</li>
 *   <li><b>Pinning:</b> only an anchor that is a self-signed leaf pins
 *       ({@link #isSelfSignedLeaf}). If the peer presents a leaf that equals
 *       such an anchor, byte for byte or in its public key
 *       ({@link #pinnedBy}), the connection is trusted without a host name
 *       check while the leaf is valid; if it is expired or not yet valid,
 *       the handshake fails for good. Everything else goes through the
 *       JDK's PKIX validation with the host name check: a CA as anchor (even
 *       a self-signed one presented as the end certificate), a leaf issued
 *       by a CA as anchor, or a certificate with a CA basic constraint and
 *       the pinned key.</li>
 *   <li><b>Protocols and cipher suites:</b> only TLS 1.3 and 1.2, and only
 *       the host's cipher suites as far as the JDK knows them.</li>
 * </ul>
 *
 * <p>Before every attempt the HTTP path asks whether the client identity has
 * expired ({@link #expired}), by the clock of this material; tests of this
 * package set that clock ({@link #clock}) instead of waiting. Neither a
 * message nor {@link #toString()} shows a value.</p>
 */
final class TckTlsMaterial {

    /**
     * The clock every new material takes over; only tests of this package
     * set it (and reset it to {@link Clock#systemUTC()} afterwards).
     */
    static volatile Clock clock = Clock.systemUTC();

    /** The only protocols, as in the host. */
    static final List<String> PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");
    /** The only cipher suites, in this order, as in the host; those the JDK does not know are dropped. */
    static final List<String> CIPHER_SUITES = List.of(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256");

    /** The material is unusable; the message shows no value. */
    static final class Unusable extends Exception {
        Unusable(String message) {
            super(message, null, false, false);
        }
    }

    private final String bindingId;
    private final List<X509Certificate> anchors;
    /** The anchors that pin ({@link #isSelfSignedLeaf}), filtered once, as in the host. */
    private final List<X509Certificate> pins;
    private final List<TckPem.Material> clients;
    /** The clock for the expiry check, taken over at construction ({@link #clock}). */
    private final Clock now;

    /**
     * @param bindingId    the binding (for messages only)
     * @param certificates the connection's {@code CERTIFICATE} keys that have a value
     * @param identity     the connection's client identity; empty if there is none
     */
    TckTlsMaterial(String bindingId, List<TckPem.Material> certificates, Optional<TckPem.Material> identity) {
        this.bindingId = bindingId;
        List<X509Certificate> trusted = new ArrayList<>();
        List<TckPem.Material> withKey = new ArrayList<>();
        for (TckPem.Material material : certificates) {
            if (material.privateKey().isEmpty()) {
                trusted.addAll(material.certificates());
            } else {
                withKey.add(material);
            }
        }
        identity.ifPresent(withKey::add);
        this.anchors = List.copyOf(trusted);
        this.pins = anchors.stream().filter(TckTlsMaterial::isSelfSignedLeaf).toList();
        this.clients = List.copyOf(withKey);
        this.now = clock;
    }

    /**
     * Whether {@code identity} has expired by the clock of this material. As
     * in the host, only {@code notAfter} counts, not {@code notBefore}.
     */
    boolean expired(X509Certificate identity) {
        return !now.instant().isBefore(identity.getNotAfter().toInstant());
    }

    /** Whether the connection has neither trust anchors nor a client certificate; the shared client is used then. */
    boolean isEmpty() {
        return anchors.isEmpty() && clients.isEmpty();
    }

    /**
     * The {@link SSLContext} of the binding: trust by the anchors (with
     * pinning) or the public CAs, plus the client certificate if there is
     * one.
     *
     * @throws Unusable if more than one value holds a key or the JDK does not accept the material
     */
    SSLContext sslContext() throws Unusable {
        if (clients.size() > 1) {
            throw new Unusable("the TLS material of connection <" + bindingId + "> is not usable: more than one"
                    + " certificate value holds a private key - keep the client certificate in one value");
        }
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(clients.isEmpty() ? null : keyManagersOf(clients.get(0)),
                    new TrustManager[]{new Pinning(pins, pkixOf(anchors))}, null);
            return context;
        } catch (GeneralSecurityException | RuntimeException failed) {
            //Without the cause: it could show parts of the value
            throw new Unusable("the TLS material of connection <" + bindingId + "> is not usable ("
                    + failed.getClass().getSimpleName() + ")");
        }
    }

    /** Protocols and cipher suites as in the host, filtered by what {@code context} supports. */
    static SSLParameters parameters(SSLContext context) {
        Set<String> known = Set.of(context.getSupportedSSLParameters().getCipherSuites());
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(PROTOCOLS.toArray(String[]::new));
        parameters.setCipherSuites(CIPHER_SUITES.stream().filter(known::contains).toArray(String[]::new));
        return parameters;
    }

    /**
     * Whether the anchor is a self-signed leaf, by the same rule as the
     * host: no CA basic constraint, issuer equal to subject, and signed with
     * its own key. Only such an anchor pins; a self-signed CA and a leaf
     * issued by a CA do not.
     */
    static boolean isSelfSignedLeaf(X509Certificate anchor) {
        if (anchor.getBasicConstraints() != -1
                || !anchor.getSubjectX500Principal().equals(anchor.getIssuerX500Principal())) {
            return false;
        }
        try {
            anchor.verify(anchor.getPublicKey());
            return true;
        } catch (GeneralSecurityException notSelfSigned) {
            return false;
        }
    }

    /**
     * Whether the peer's certificate is one of the pinned leaves: itself a
     * leaf and equal byte for byte or in its public key (SPKI), by the same
     * rule as the host.
     */
    static boolean pinnedBy(X509Certificate presented, List<X509Certificate> pins) {
        if (presented == null || pins.isEmpty() || presented.getBasicConstraints() != -1) {
            return false;
        }
        try {
            byte[] bytes = presented.getEncoded();
            byte[] spki = presented.getPublicKey().getEncoded();
            for (X509Certificate pin : pins) {
                if (Arrays.equals(bytes, pin.getEncoded()) || Arrays.equals(spki, pin.getPublicKey().getEncoded())) {
                    return true;
                }
            }
            return false;
        } catch (CertificateException unreadable) {
            return false;
        }
    }

    /**
     * The JDK's PKIX validation: with anchors a trust store of them alone,
     * without anchors the public CAs, as in the host.
     */
    private static X509ExtendedTrustManager pkixOf(List<X509Certificate> anchors) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (anchors.isEmpty()) {
            factory.init((KeyStore) null);
        } else {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            try {
                store.load(null, null);
            } catch (IOException impossible) {
                throw new GeneralSecurityException("empty key store", impossible);
            }
            int alias = 0;
            for (X509Certificate anchor : anchors) {
                store.setCertificateEntry("connection-" + alias++, anchor);
            }
            factory.init(store);
        }
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509ExtendedTrustManager extended) {
                return extended;
            }
        }
        throw new GeneralSecurityException("no X509 trust manager");
    }

    /** An in-memory key store with only this connection's key and chain and a throwaway password, as in the host. */
    static javax.net.ssl.KeyManager[] keyManagersOf(TckPem.Material client) throws GeneralSecurityException {
        PrivateKey key = client.privateKey().orElseThrow();
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        char[] password = Base64.getEncoder().encodeToString(random).toCharArray();
        Arrays.fill(random, (byte) 0);
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try {
                store.load(null, null);
            } catch (IOException impossible) {
                throw new GeneralSecurityException("empty key store", impossible);
            }
            store.setKeyEntry("client", key, password, client.certificates().toArray(X509Certificate[]::new));
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, password);
            return factory.getKeyManagers();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Shows no values. */
    @Override
    public String toString() {
        return "TckTlsMaterial[connection " + bindingId + ", " + anchors.size() + " anchor(s), "
                + clients.size() + " client certificate(s)]";
    }

    /**
     * The trust of the binding: a pinned self-signed leaf ({@link #pinnedBy})
     * is trusted without a host name check while it is valid; everything else
     * goes through the JDK's PKIX validation with the host name the JDK client
     * sets on the {@link SSLEngine} ({@code HTTPS}). There is no custom
     * verifier that could let anything else through.
     */
    private static final class Pinning extends X509ExtendedTrustManager {

        private final List<X509Certificate> pins;
        private final X509ExtendedTrustManager pkix;

        private Pinning(List<X509Certificate> pins, X509ExtendedTrustManager pkix) {
            this.pins = pins;
            this.pkix = pkix;
        }

        /**
         * Pinned and valid: accepted. Pinned and expired or not yet valid:
         * rejected for good (a {@link CertificateException} from
         * {@code checkValidity()}), as in the host.
         */
        private boolean pinned(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0 || !pinnedBy(chain[0], pins)) {
                return false;
            }
            chain[0].checkValidity();
            return true;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            if (!pinned(chain)) {
                pkix.checkServerTrusted(chain, authType, engine);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            if (!pinned(chain)) {
                pkix.checkServerTrusted(chain, authType, socket);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (!pinned(chain)) {
                pkix.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("the network path of the TCK connects only as a client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("the network path of the TCK connects only as a client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("the network path of the TCK connects only as a client");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return pkix.getAcceptedIssuers();
        }
    }
}
