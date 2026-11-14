package com.example.remoteservice;

import com.rmm.remote.tck.RemoteServiceIdentities;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;

/**
 * What the tests of the template need, set up the way an operator does it:
 * the key store of the service (PKCS#12, EC P-256, self-signed for
 * {@code 127.0.0.1} and {@code localhost}, generated with the {@code keytool}
 * of the JDK, ONCE per JVM) and a directory of pinned client certificates,
 * one PEM file per certificate.
 */
final class ServiceFixture {

    static final String PASSWORD = "changeit";
    private static volatile Path keyStore;
    private static volatile X509Certificate certificate;

    private ServiceFixture() {
    }

    /** The key store of the service (a temporary file, deleted when the JVM exits). */
    static Path keyStore() {
        if (keyStore == null) {
            synchronized (ServiceFixture.class) {
                if (keyStore == null) {
                    keyStore = generate();
                }
            }
        }
        return keyStore;
    }

    /** The certificate of the service as PEM, like {@code tls.trust} of the connection. */
    static String certificatePem() {
        keyStore();
        return pem(certificate);
    }

    /** The certificate of the service. */
    static X509Certificate certificate() {
        keyStore();
        return certificate;
    }

    /** The settings of a service on {@code 127.0.0.1} with a free port. */
    static ServiceSettings settings(PinStore pins) {
        return new ServiceSettings(new InetSocketAddress("127.0.0.1", 0), keyStore(), PASSWORD.toCharArray(), pins);
    }

    /** A new directory of pinned certificates with one file {@code <name>.pem} each. */
    static Path pinDirectory(List<String> names, List<X509Certificate> certificates) throws IOException {
        Path directory = Files.createTempDirectory("remote-service-pins-");
        for (int index = 0; index < names.size(); index++) {
            pin(directory, names.get(index), certificates.get(index));
        }
        return directory;
    }

    /** Puts {@code certificate} into the directory as {@code <name>.pem}. */
    static void pin(Path directory, String name, X509Certificate certificate) throws IOException {
        Files.writeString(directory.resolve(name + ".pem"), pem(certificate), StandardCharsets.US_ASCII);
    }

    /** A client of the connection {@code identity}: its certificate, trusting the certificate of the service. */
    static HttpClient client(RemoteServiceIdentities.Identity identity) throws Exception {
        return client(identity, issuers -> { });
    }

    /**
     * Like {@link #client(RemoteServiceIdentities.Identity)}; at every
     * handshake {@code issuersAsked} receives the names the service lists in
     * its certificate request (empty: none).
     */
    static HttpClient client(RemoteServiceIdentities.Identity identity, Consumer<List<Principal>> issuersAsked)
            throws Exception {
        SSLParameters parameters = new SSLParameters();
        parameters.setProtocols(new String[]{"TLSv1.3"});
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(Duration.ofSeconds(10)).sslContext(tls(identity, issuersAsked))
                .sslParameters(parameters).build();
    }

    /**
     * The TLS context of the client of the connection {@code identity}: its
     * certificate, trusting the certificate of the service;
     * {@code issuersAsked} as in
     * {@link #client(RemoteServiceIdentities.Identity, Consumer)}.
     */
    static SSLContext tls(RemoteServiceIdentities.Identity identity, Consumer<List<Principal>> issuersAsked)
            throws Exception {
        char[] password = "client".toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("client", identity.key(), password, new X509Certificate[]{identity.certificate()});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, password);
        KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
        anchors.load(null, null);
        anchors.setCertificateEntry("service", certificate());
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(anchors);
        SSLContext tls = SSLContext.getInstance("TLS");
        X509KeyManager standard = (X509KeyManager) keyManagers.getKeyManagers()[0];
        tls.init(new KeyManager[]{new AskedIssuers(standard, issuersAsked)}, trust.getTrustManagers(), null);
        return tls;
    }

    /** The key manager of the JDK, which passes on the requested names and changes nothing else. */
    private static final class AskedIssuers extends X509ExtendedKeyManager {

        private final X509KeyManager keys;
        private final Consumer<List<Principal>> issuersAsked;

        AskedIssuers(X509KeyManager keys, Consumer<List<Principal>> issuersAsked) {
            this.keys = keys;
            this.issuersAsked = issuersAsked;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            issuersAsked.accept(issuers == null ? List.of() : List.of(issuers));
            return keys.chooseClientAlias(keyType, issuers, null);
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            issuersAsked.accept(issuers == null ? List.of() : List.of(issuers));
            return keys.chooseClientAlias(keyType, issuers, socket);
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return keys.getClientAliases(keyType, issuers);
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return keys.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return keys.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return keys.getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return keys.getPrivateKey(alias);
        }
    }

    static void removeDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            //A temporary directory; a leftover file does no harm
        }
    }

    static String pem(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private static Path generate() {
        try {
            Path directory = Files.createTempDirectory("remote-service-keys-");
            Path store = directory.resolve("service.p12");
            String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
            List<String> command = new ArrayList<>(List.of(keytool, "-genkeypair", "-alias", "service", "-dname",
                    "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1,dns:localhost", "-validity", "365", "-keyalg", "EC",
                    "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA", "-storetype", "PKCS12", "-keystore",
                    store.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool failed: " + output);
            }
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) {
                keys.load(in, PASSWORD.toCharArray());
            }
            certificate = (X509Certificate) keys.getCertificate("service");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> removeDirectory(directory)));
            return store;
        } catch (Exception failed) {
            throw new IllegalStateException("the key store of the test service could not be generated", failed);
        }
    }
}
