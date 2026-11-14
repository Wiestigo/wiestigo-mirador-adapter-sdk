package com.rmm.remote.tck;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * The client of ONE connection of the TCK for remote services: https with
 * the client identity of the connection (or without one), HTTP/1.1, no proxy,
 * every request with {@code X-RMM-Adapter-Protocol: 4} and the
 * {@code X-RMM-Binding} headers the case dictates, normally exactly the
 * binding of the identity.
 *
 * <p>It trusts the service the way the host does. An anchor that is a
 * self-signed leaf certificate pins: when the service presents exactly this
 * leaf (byte for byte, or the same public key) and it is valid, the
 * connection is trusted without a host name check. Everything else goes
 * through the PKIX validation of the JDK with a host name check
 * ({@code HTTPS}). Only TLS 1.3 and 1.2 with the host's cipher suites are
 * offered: TLS 1.3, and for TLS 1.2 ECDHE with AEAD. A service that offers
 * only CBC suites cannot talk to the host, and so not to the TCK either.</p>
 */
final class ServiceClient {

    /** An answer of the service: status and body. */
    record Answer(int status, String body) {

        boolean successful() {
            return status >= 200 && status < 300;
        }
    }

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** The only protocols offered, as in the host. */
    static final List<String> PROTOCOLS = List.of("TLSv1.3", "TLSv1.2");
    /** The only cipher suites, in this order, as in the host; suites the JDK does not know are dropped. */
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

    private final String name;
    private final Duration requestTimeout;
    private final String base;
    private final HttpClient http;

    /**
     * @param name       how messages name the connection
     * @param serviceUrl the address of the service ({@code https://...})
     * @param anchorPem  the trust anchors of the service (PEM, one or more certificates)
     * @param identity   the client identity; {@code null}: no client certificate
     * @param timeout    the timeout per request, headers and body
     */
    ServiceClient(String name, String serviceUrl, String anchorPem, RemoteServiceIdentities.Identity identity,
                  Duration timeout) {
        this.name = name;
        this.requestTimeout = timeout;
        String trimmed = serviceUrl == null ? "" : serviceUrl.trim();
        if (!trimmed.startsWith("https://")) {
            throw new ServiceContractViolation("serviceUrl() must be an https address of the service (Mirador"
                    + " connects to a remote service only over https), not <" + trimmed + ">");
        }
        this.base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        try {
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(identity == null ? null : keyManagersOf(identity),
                    new TrustManager[]{new Pinning(anchorsOf(anchorPem))}, null);
            Set<String> known = Set.of(tls.getSupportedSSLParameters().getCipherSuites());
            SSLParameters parameters = new SSLParameters();
            parameters.setProtocols(PROTOCOLS.toArray(String[]::new));
            parameters.setCipherSuites(CIPHER_SUITES.stream().filter(known::contains).toArray(String[]::new));
            this.http = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(HttpClient.Builder.NO_PROXY)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .sslContext(tls)
                    .sslParameters(parameters)
                    .build();
        } catch (GeneralSecurityException unusable) {
            throw new IllegalStateException("the TLS material of the remote TCK is not usable ("
                    + unusable.getClass().getSimpleName() + ")", unusable);
        }
    }

    /** {@code GET path}; throws an {@link IOException} when no answer arrives (for example a refused handshake). */
    Answer get(String path, List<String> bindingHeaders) throws IOException {
        return send(HttpRequest.newBuilder(uri(path)).GET(), bindingHeaders);
    }

    /** {@code POST path} with JSON; throws an {@link IOException} when no answer arrives. */
    Answer post(String path, String body, List<String> bindingHeaders) throws IOException {
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)), bindingHeaders);
    }

    @Override
    public String toString() {
        return name;
    }

    private URI uri(String path) {
        return URI.create(base + path);
    }

    private Answer send(HttpRequest.Builder request, List<String> bindingHeaders) throws IOException {
        request.timeout(requestTimeout).header(RemoteServiceContractTest.PROTOCOL_HEADER,
                String.valueOf(RemoteServiceContractTest.PROTOCOL_VERSION));
        for (String binding : bindingHeaders) {
            request.header(RemoteServiceContractTest.BINDING_HEADER, binding);
        }
        try {
            HttpResponse<String> response = http.send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Answer(response.statusCode(), response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", interrupted);
        }
    }

    private static List<X509Certificate> anchorsOf(String pem) throws CertificateException {
        if (pem == null || pem.isBlank()) {
            throw new ServiceContractViolation("serviceAnchor() must give the certificate (PEM) the connection"
                    + " trusts for the service, as tls.trust of a REMOTE connection does");
        }
        List<X509Certificate> anchors = new ArrayList<>();
        for (Certificate certificate : CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))) {
            anchors.add((X509Certificate) certificate);
        }
        if (anchors.isEmpty()) {
            throw new ServiceContractViolation("serviceAnchor() holds no certificate");
        }
        return anchors;
    }

    /** A key store holding only the key and certificate of the identity, with a throwaway password. */
    private static KeyManager[] keyManagersOf(RemoteServiceIdentities.Identity identity)
            throws GeneralSecurityException {
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        char[] password = Base64.getEncoder().encodeToString(random).toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try {
                store.load(null, null);
            } catch (IOException impossible) {
                throw new GeneralSecurityException("empty key store", impossible);
            }
            store.setKeyEntry("client", identity.key(), password, new X509Certificate[]{identity.certificate()});
            KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, password);
            return factory.getKeyManagers();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** Pins a self-signed leaf certificate, otherwise PKIX with a host name check: the host's trust rule. */
    private static final class Pinning extends X509ExtendedTrustManager {

        private final List<X509Certificate> pins;
        private final X509ExtendedTrustManager pkix;

        private Pinning(List<X509Certificate> anchors) throws GeneralSecurityException {
            this.pins = anchors.stream().filter(Pinning::isSelfSignedLeaf).toList();
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            try {
                store.load(null, null);
            } catch (IOException impossible) {
                throw new GeneralSecurityException("empty key store", impossible);
            }
            int alias = 0;
            for (X509Certificate anchor : anchors) {
                store.setCertificateEntry("service-" + alias++, anchor);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(store);
            X509ExtendedTrustManager found = null;
            for (TrustManager manager : factory.getTrustManagers()) {
                if (manager instanceof X509ExtendedTrustManager extended) {
                    found = extended;
                }
            }
            if (found == null) {
                throw new GeneralSecurityException("no X509 trust manager");
            }
            this.pkix = found;
        }

        private static boolean isSelfSignedLeaf(X509Certificate anchor) {
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

        private boolean pinned(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0 || chain[0].getBasicConstraints() != -1) {
                return false;
            }
            byte[] bytes = chain[0].getEncoded();
            byte[] spki = chain[0].getPublicKey().getEncoded();
            for (X509Certificate pin : pins) {
                if (Arrays.equals(bytes, pin.getEncoded()) || Arrays.equals(spki, pin.getPublicKey().getEncoded())) {
                    chain[0].checkValidity();
                    return true;
                }
            }
            return false;
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
            throw new CertificateException("the remote TCK connects only as a client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new CertificateException("the remote TCK connects only as a client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("the remote TCK connects only as a client");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return pkix.getAcceptedIssuers();
        }
    }
}
