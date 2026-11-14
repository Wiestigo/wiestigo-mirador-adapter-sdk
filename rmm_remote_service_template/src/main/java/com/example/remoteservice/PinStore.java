package com.example.remoteservice;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * The pinned client certificates of the service. The operator puts the
 * certificate of each connection, downloaded from the Mirador administration,
 * as a PEM file into a directory ({@code *.pem}, {@code *.crt} or
 * {@code *.cer}, one or more certificates per file). The service accepts only
 * a client certificate that equals a pinned one byte for byte and is valid.
 * The name in it grants nothing: anyone can issue a certificate with
 * {@code urn:rmm:binding:<id>}.
 *
 * <p>The directory is checked at every handshake and every request and read
 * again whenever something in it has changed. This allows certificate
 * rotation without a restart: add the new certificate (both are accepted) and
 * remove the old one only when the administration shows it as
 * {@code RETIRED}. Because {@link RemoteService} checks every request against
 * the pin store again, a removed certificate stops working with the next
 * request, even on an open or resumed TLS session.</p>
 */
public class PinStore {

    /** The prefix of the subject alternative name with which Mirador names the binding. */
    public static final String BINDING_URN_PREFIX = "urn:rmm:binding:";

    private static final System.Logger LOG = System.getLogger(PinStore.class.getName());
    private static final int SAN_URI = 6;

    /** What was read last: the directory state it was read from and the certificates. */
    private record Snapshot(String state, List<X509Certificate> certificates) {
    }

    private final Path directory;
    private volatile Snapshot snapshot;

    /** The certificates in {@code directory}, read again after every change. */
    public static PinStore ofDirectory(Path directory) {
        PinStore store = new PinStore(directory, List.of());
        store.current();
        return store;
    }

    /** Exactly these certificates (no directory), for example for a test. */
    public static PinStore of(Collection<X509Certificate> certificates) {
        return new PinStore(null, List.copyOf(certificates));
    }

    PinStore(Path directory, List<X509Certificate> fixed) {
        this.directory = directory;
        this.snapshot = new Snapshot(null, fixed);
    }

    /** The certificates pinned right now. */
    public List<X509Certificate> pinned() {
        return current();
    }

    /**
     * Whether {@code leaf} is pinned (byte for byte) and valid today. The
     * service does not accept an expired certificate; Mirador sends nothing
     * with an expired one anyway.
     */
    public boolean isPinned(X509Certificate leaf) {
        if (leaf == null) {
            return false;
        }
        try {
            byte[] presented = leaf.getEncoded();
            for (X509Certificate pin : current()) {
                if (Arrays.equals(presented, pin.getEncoded())) {
                    leaf.checkValidity();
                    return true;
                }
            }
            return false;
        } catch (CertificateException notUsable) {
            return false;
        }
    }

    /**
     * The bindings {@code certificate} names in its subject alternative name
     * ({@code URI:urn:rmm:binding:<bindingId>}); Mirador issues exactly one.
     */
    public static List<String> bindingsOf(X509Certificate certificate) {
        List<String> bindings = new ArrayList<>();
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names != null) {
                for (List<?> name : names) {
                    if (Integer.valueOf(SAN_URI).equals(name.get(0)) && name.get(1) instanceof String uri
                            && uri.startsWith(BINDING_URN_PREFIX)) {
                        bindings.add(uri.substring(BINDING_URN_PREFIX.length()));
                    }
                }
            }
        } catch (CertificateParsingException unreadable) {
            return List.of();
        }
        return bindings;
    }

    /**
     * The trust manager for the handshake: accepts exactly the pinned
     * certificates as clients ({@link #isPinned}). The service never acts as
     * a client, so it trusts no server.
     */
    X509ExtendedTrustManager trustManager() {
        return new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                check(chain);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                check(chain);
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                check(chain);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                    throws CertificateException {
                throw new CertificateException("the remote service is no client");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                    throws CertificateException {
                throw new CertificateException("the remote service is no client");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("the remote service is no client");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                //No names in the certificate request: otherwise every peer could read the bindings of all
                //connections before any check, and from about a thousand certificates on the list would
                //exceed the TLS field. Mirador presents its certificate without the list; check() verifies it
                return new X509Certificate[0];
            }

            private void check(X509Certificate[] chain) throws CertificateException {
                if (chain == null || chain.length == 0 || !isPinned(chain[0])) {
                    throw new CertificateException("the client certificate is not pinned at this service");
                }
            }
        };
    }

    /** The certificates in force; the directory is read again only after a change. */
    private List<X509Certificate> current() {
        if (directory == null) {
            return snapshot.certificates();
        }
        String state = stateOf(directory);
        Snapshot known = snapshot;
        if (state.equals(known.state())) {
            return known.certificates();
        }
        synchronized (this) {
            if (!state.equals(snapshot.state())) {
                snapshot = new Snapshot(state, read(directory));
                LOG.log(System.Logger.Level.INFO, "pinned client certificates read from {0}: {1}", directory,
                        snapshot.certificates().size());
            }
            return snapshot.certificates();
        }
    }

    /** Name, size and modification time of every file in the directory. */
    private static String stateOf(Path directory) {
        StringBuilder state = new StringBuilder();
        for (Path file : filesOf(directory)) {
            try {
                state.append(file.getFileName()).append('|').append(Files.size(file)).append('|')
                        .append(Files.getLastModifiedTime(file).toMillis()).append('\n');
            } catch (IOException gone) {
                state.append(file.getFileName()).append("|gone\n");
            }
        }
        return state.toString();
    }

    private static List<X509Certificate> read(Path directory) {
        List<X509Certificate> certificates = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            LOG.log(System.Logger.Level.WARNING, "the pin directory {0} does not exist; no client is accepted",
                    directory);
        }
        for (Path file : filesOf(directory)) {
            try (InputStream in = Files.newInputStream(file)) {
                for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    certificates.add((X509Certificate) certificate);
                }
            } catch (IOException | CertificateException unreadable) {
                //A broken file excludes only itself; the others stay in force
                LOG.log(System.Logger.Level.WARNING, "pinned certificate file {0} is not readable: {1}", file,
                        unreadable.getMessage());
            }
        }
        return List.copyOf(certificates);
    }

    private static List<Path> filesOf(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).filter(PinStore::isCertificateFile).sorted().toList();
        } catch (IOException unreadable) {
            //Not readable: no client is accepted (the next state is read again)
            return List.of();
        }
    }

    private static boolean isCertificateFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pem") || name.endsWith(".crt") || name.endsWith(".cer");
    }
}
