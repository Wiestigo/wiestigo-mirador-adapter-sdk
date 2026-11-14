package unit;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Certificates for the tests of TLS material, client identity and pinning on the TCK's HTTP path.
 * Generated once per test class ({@link #generate()} in {@code @BeforeAll}) with the JDK's {@code keytool}
 * in a temporary directory, so no private key sits in the source. All keys are EC P-256. The class also
 * generates single pairs ({@link #selfSigned}, {@link #bindingIdentity}) and builds the TLS context of a tool
 * that serves a pair ({@link #serverContext}).
 *
 * @param identity           the client identity of a connection (self-signed, one year)
 * @param expiredIdentity    an expired client identity
 * @param notYetValidIdentity a client identity that becomes valid tomorrow (only {@code notBefore} is in the
 *                           future)
 * @param leaf               a self-signed leaf without the CA basic constraint, for {@code other.test}
 * @param renewedLeaf        the same key pair as {@link #leaf}, signed again for another name (different
 *                           bytes, same public key)
 * @param authorityWithLeafKey the same key pair as {@link #leaf}, signed by itself with the CA basic
 *                           constraint, for {@code other.test} (the pinned SPKI on a certificate authority)
 * @param expiredLeaf        a self-signed leaf for {@code other.test} that expired eight days ago
 * @param otherLeaf          another self-signed leaf for {@code other.test}
 * @param root               a self-signed root with the CA basic constraint, without SAN
 * @param issuedForIp        a leaf issued by the root for {@code 127.0.0.1} (chain: leaf, root)
 * @param issuedForOther     a leaf issued by the root for {@code other.test} (chain: leaf, root)
 */
record TckTlsFixtures(Pair identity, Pair expiredIdentity, Pair notYetValidIdentity, Pair leaf, Pair renewedLeaf,
                      Pair authorityWithLeafKey, Pair expiredLeaf, Pair otherLeaf, Pair root, Pair issuedForIp,
                      Pair issuedForOther) {

    /**
     * A key with its chain.
     *
     * @param key   the private key
     * @param chain the end-entity certificate first
     */
    record Pair(PrivateKey key, List<X509Certificate> chain) {

        X509Certificate certificate() {
            return chain.get(0);
        }

        /** Only the end-entity certificate as PEM: a trust anchor. */
        String certificatePem() {
            return pem("CERTIFICATE", encoded(certificate()));
        }

        /** Certificate and key in one PEM, the form in which the host holds a client identity. */
        String withKeyPem() {
            return certificatePem() + pem("PRIVATE KEY", key.getEncoded());
        }

        /** Leaves out the key. */
        @Override
        public String toString() {
            return "Pair[" + certificate().getSubjectX500Principal() + "]";
        }
    }

    private static final String PASSWORD = "changeit";

    static TckTlsFixtures generate() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("rmm-tck-tls-");
            Path store = directory.resolve("tls.p12");
            keytool(store, "-genkeypair", "-alias", "identity", "-dname", "CN=tck-client", "-validity", "365");
            keytool(store, "-genkeypair", "-alias", "expired", "-dname", "CN=tck-client", "-startdate", "-10d",
                    "-validity", "1");
            keytool(store, "-genkeypair", "-alias", "early", "-dname", "CN=tck-client", "-startdate", "+1d",
                    "-validity", "2");
            keytool(store, "-genkeypair", "-alias", "expiredleaf", "-dname", "CN=other.test",
                    "-ext", "san=dns:other.test", "-startdate", "-10d", "-validity", "2");
            keytool(store, "-genkeypair", "-alias", "leaf", "-dname", "CN=other.test", "-ext", "san=dns:other.test",
                    "-validity", "3650");
            keytool(store, "-genkeypair", "-alias", "otherleaf", "-dname", "CN=other.test",
                    "-ext", "san=dns:other.test", "-validity", "3650");
            keytool(store, "-genkeypair", "-alias", "root", "-dname", "CN=TCK Pin Root", "-ext", "bc:c",
                    "-validity", "3650");
            keytool(store, "-genkeypair", "-alias", "forip", "-dname", "CN=tool.test", "-validity", "3650");
            keytool(store, "-genkeypair", "-alias", "forother", "-dname", "CN=tool.test", "-validity", "3650");
            X509Certificate forIp = issue(store, directory, "forip", "san=ip:127.0.0.1");
            X509Certificate forOther = issue(store, directory, "forother", "san=dns:other.test");
            //The leaf's key, issued by itself as an authority: the same SPKI, but a CA
            X509Certificate authority = issue(store, directory, "leaf", "leaf", "3650", "bc:c",
                    "san=dns:other.test");
            KeyStore before = load(store);
            Pair leaf = pairOf(before, "leaf");
            keytool(store, "-selfcert", "-alias", "leaf", "-dname", "CN=renewed.test", "-validity", "3650");
            KeyStore after = load(store);
            Pair root = pairOf(before, "root");
            return new TckTlsFixtures(pairOf(before, "identity"), pairOf(before, "expired"),
                    pairOf(before, "early"), leaf, pairOf(after, "leaf"),
                    new Pair(leaf.key(), List.of(authority)), pairOf(before, "expiredleaf"),
                    pairOf(before, "otherleaf"), root,
                    new Pair(key(before, "forip"), List.of(forIp, root.certificate())),
                    new Pair(key(before, "forother"), List.of(forOther, root.certificate())));
        } catch (Exception failed) {
            throw new IllegalStateException("test certificates not generated", failed);
        } finally {
            removeDirectory(directory);
        }
    }

    /** A certificate issued by the root for the key {@code alias}, with {@code san}. */
    private static X509Certificate issue(Path store, Path directory, String alias, String san) throws Exception {
        return issue(store, directory, alias, "root", "3650", san);
    }

    /**
     * A certificate issued by {@code issuer} for the key {@code alias},
     * valid {@code days} days, with {@code extensions}.
     */
    private static X509Certificate issue(Path store, Path directory, String alias, String issuer, String days,
                                         String... extensions) throws Exception {
        Path request = directory.resolve(alias + "-" + issuer + ".csr");
        Path issued = directory.resolve(alias + "-" + issuer + ".crt");
        keytool(store, "-certreq", "-alias", alias, "-file", request.toString());
        List<String> arguments = new ArrayList<>(List.of("-gencert", "-alias", issuer, "-infile", request.toString(),
                "-outfile", issued.toString(), "-validity", days, "-rfc"));
        for (String extension : extensions) {
            arguments.addAll(List.of("-ext", extension));
        }
        keytool(store, arguments.toArray(String[]::new));
        try (InputStream in = Files.newInputStream(issued)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    /**
     * A client identity that names its binding ({@code URI:urn:rmm:binding:<bindingId>}, one year), as the
     * host issues it for a connection. Generated anew on every call.
     */
    static Pair bindingIdentity(String bindingId) {
        return selfSigned("-dname", "CN=tck-client", "-ext", "san=uri:urn:rmm:binding:" + bindingId,
                "-validity", "365");
    }

    /**
     * A single self-signed key pair in a store of its own, generated anew on every call;
     * {@code description} are the {@code keytool -genkeypair} arguments that describe the certificate.
     */
    static Pair selfSigned(String... description) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("rmm-tck-tls-");
            Path store = directory.resolve("pair.p12");
            List<String> arguments = new ArrayList<>(List.of("-genkeypair", "-alias", "pair"));
            arguments.addAll(List.of(description));
            keytool(store, arguments.toArray(String[]::new));
            return pairOf(load(store), "pair");
        } catch (Exception failed) {
            throw new IllegalStateException("test certificate not generated", failed);
        } finally {
            removeDirectory(directory);
        }
    }

    /**
     * The TLS context of a tool that presents {@code served} (with its chain). With {@code clientTrust} it
     * trusts only that client certificate; without, it has the trust of the JDK.
     */
    static SSLContext serverContext(Pair served, X509Certificate clientTrust) throws Exception {
        char[] password = PASSWORD.toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("served", served.key(), password, served.chain().toArray(X509Certificate[]::new));
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, password);
        TrustManagerFactory trustManagers = null;
        if (clientTrust != null) {
            KeyStore trusted = KeyStore.getInstance("PKCS12");
            trusted.load(null, null);
            trusted.setCertificateEntry("client", clientTrust);
            trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trusted);
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), trustManagers == null ? null : trustManagers.getTrustManagers(),
                null);
        return context;
    }

    private static void removeDirectory(Path directory) {
        if (directory != null) {
            try (Stream<Path> files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (Exception ignored) {
                //a temporary directory; a leftover is harmless
            }
        }
    }

    private static void keytool(Path store, String... arguments) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        List<String> command = new ArrayList<>(List.of(keytool));
        command.addAll(List.of(arguments));
        if (arguments[0].equals("-genkeypair")) {
            command.addAll(List.of("-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA"));
        }
        command.addAll(List.of("-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", PASSWORD,
                "-keypass", PASSWORD));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool " + arguments[0] + " " + arguments[2] + " failed: " + output);
        }
    }

    private static KeyStore load(Path store) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keyStore.load(in, PASSWORD.toCharArray());
        }
        return keyStore;
    }

    private static PrivateKey key(KeyStore store, String alias) throws Exception {
        return (PrivateKey) store.getKey(alias, PASSWORD.toCharArray());
    }

    private static Pair pairOf(KeyStore store, String alias) throws Exception {
        return new Pair(key(store, alias), List.of((X509Certificate) store.getCertificate(alias)));
    }

    private static byte[] encoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (Exception unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    private static String pem(String type, byte[] derBytes) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(derBytes)
                + "\n-----END " + type + "-----\n";
    }
}
