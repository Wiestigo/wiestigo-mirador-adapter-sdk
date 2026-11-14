package com.rmm.remote.tck;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The client identities of the TCK for remote services: two connections
 * whose certificates the service pins for the test run ({@link #first()},
 * {@link #second()}; {@link #pinned()}), a third it does not know
 * ({@link #unpinned()}), and a fourth it does not know either but that names
 * the same binding as the first ({@link #forgedFirst()}: same subject
 * alternative name, different key, since anyone can issue a certificate with
 * {@code urn:rmm:binding:<A>}). Each is a self-signed leaf certificate with
 * an EC P-256 key, valid for one year, that names its binding as the subject
 * alternative name {@code URI:urn:rmm:binding:<bindingId>}, the way Mirador
 * generates it for a connection of type {@code REMOTE}
 * ({@code docs/REMOTE_PROTOCOL.md}, "Operation and configuration"). They are
 * generated with the {@code keytool} of the JDK in a temporary directory,
 * ONCE per JVM.
 *
 * <p>The service under test must trust exactly {@link #pinned()}, the way
 * its operator pins the downloaded certificates of two connections, before
 * {@link RemoteServiceContractTest} calls it.</p>
 */
public final class RemoteServiceIdentities {

    /** The prefix of the subject alternative name that names the binding. */
    public static final String BINDING_URN_PREFIX = "urn:rmm:binding:";

    /** A client identity: the binding its certificate names, the key and the certificate. */
    public record Identity(String bindingId, PrivateKey key, X509Certificate certificate) {

        /** The certificate as PEM, the form in which the operator pins it at the service. */
        public String certificatePem() {
            return pem(certificate);
        }

        /** Omits the key. */
        @Override
        public String toString() {
            return "Identity[binding " + bindingId + "]";
        }
    }

    private static final String PASSWORD = "changeit";
    private static volatile List<Identity> identities;

    private RemoteServiceIdentities() {
    }

    /** The identity of the first connection (binding A). */
    public static Identity first() {
        return all().get(0);
    }

    /** The identity of the second connection (binding B): another binding at the same service. */
    public static Identity second() {
        return all().get(1);
    }

    /** An identity the service has not pinned; it must refuse it. */
    public static Identity unpinned() {
        return all().get(2);
    }

    /**
     * An identity the service has not pinned, whose certificate names the
     * binding of {@link #first()}. The service must refuse it: the name in a
     * certificate grants nothing, only the pinned certificate does.
     */
    public static Identity forgedFirst() {
        return all().get(3);
    }

    /** The certificates the service under test pins: {@link #first()} and {@link #second()}. */
    public static List<X509Certificate> pinned() {
        return List.of(first().certificate(), second().certificate());
    }

    /** {@link #pinned()} as PEM, one certificate after the other. */
    public static String pinnedPem() {
        return first().certificatePem() + second().certificatePem();
    }

    private static List<Identity> all() {
        List<Identity> known = identities;
        if (known == null) {
            synchronized (RemoteServiceIdentities.class) {
                if (identities == null) {
                    Identity first = generate(UUID.randomUUID().toString());
                    identities = List.of(first, generate(UUID.randomUUID().toString()),
                            generate(UUID.randomUUID().toString()), generate(first.bindingId()));
                }
                known = identities;
            }
        }
        return known;
    }

    private static Identity generate(String bindingId) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("rmm-remote-tck-");
            Path store = directory.resolve("client.p12");
            keytool(store, "-genkeypair", "-alias", "client", "-dname", "CN=rmm-connection", "-ext",
                    "san=uri:" + BINDING_URN_PREFIX + bindingId, "-validity", "365", "-keyalg", "EC", "-groupname",
                    "secp256r1", "-sigalg", "SHA256withECDSA");
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) {
                keyStore.load(in, PASSWORD.toCharArray());
            }
            return new Identity(bindingId, (PrivateKey) keyStore.getKey("client", PASSWORD.toCharArray()),
                    (X509Certificate) keyStore.getCertificate("client"));
        } catch (Exception failed) {
            throw new IllegalStateException("the client identities of the remote TCK could not be generated",
                    failed);
        } finally {
            removeDirectory(directory);
        }
    }

    private static void keytool(Path store, String... arguments) throws Exception {
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        List<String> command = new ArrayList<>(List.of(keytool));
        command.addAll(List.of(arguments));
        command.addAll(List.of("-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", PASSWORD,
                "-keypass", PASSWORD));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool " + arguments[0] + " failed: " + output);
        }
    }

    private static void removeDirectory(Path directory) {
        if (directory != null) {
            try (Stream<Path> files = Files.walk(directory)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (Exception ignored) {
                //A temporary directory; a leftover file does no harm
            }
        }
    }

    private static String pem(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }
}
