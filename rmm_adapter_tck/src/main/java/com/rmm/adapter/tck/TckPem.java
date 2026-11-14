package com.rmm.adapter.tck;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A value of type {@code CERTIFICATE} in the TCK, parsed by the same rules as
 * in the host: PEM with one or more certificates and at most one unencrypted
 * private key in PKCS#8 ({@code PRIVATE KEY}; RSA, EC, Ed25519, Ed448), which
 * must belong to the first certificate. The key stays inside the TCK; the
 * adapter gets only the certificates through
 * {@code config().certificate(key)}. Text between the blocks is skipped.
 * Neither a message nor a cause shows the value.
 *
 * <p><b>The host's rule</b> ({@code AdapterConfig.certificate}): every
 * declared {@code CERTIFICATE} key is TLS material of the HTTP path. Without
 * a key it is a trust anchor of the connection, with a PKCS#8 key its client
 * certificate. With anchors the host trusts only them, without anchors the
 * public CAs. Two values with a key make every {@code https} request fail.
 * The TCK's HTTP path applies the same rule ({@link TckTlsMaterial}),
 * including client identity and pinning, so an adapter that declares a
 * certificate for another purpose notices it at its {@code https}
 * requests.</p>
 */
final class TckPem {

    /** A longer text is no trust anchor but a mistake. */
    static final int MAX_CHARS = 256 * 1024;
    /** No chain or bundle of a connection has more certificates. */
    static final int MAX_CERTIFICATES = 64;

    private static final Pattern BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]*)-----END \\1-----");
    private static final List<String> KEY_ALGORITHMS = List.of("RSA", "EC", "Ed25519", "Ed448");

    /**
     * The certificates and the optional private key of a value.
     * {@link #toString()} never shows the key.
     */
    record Material(List<X509Certificate> certificates, Optional<PrivateKey> privateKey) {

        public Material {
            certificates = List.copyOf(certificates);
        }

        @Override
        public String toString() {
            return "TckPem.Material[" + certificates.size() + " certificate(s)"
                    + (privateKey.isPresent() ? ", private key ****" : "") + "]";
        }
    }

    private TckPem() {
    }

    /**
     * Parses a value; {@code what} names it in messages (for example
     * {@code Configuration value <tls.anchor>}).
     *
     * @throws IllegalArgumentException if the value is not usable PEM
     */
    static Material parse(String pem, String what) {
        if (pem == null || pem.isBlank()) {
            throw invalid(what, "it is empty");
        }
        if (pem.length() > MAX_CHARS) {
            throw invalid(what, "it is longer than " + MAX_CHARS + " characters");
        }
        String text = pem.replace("\r", "");
        Matcher block = BLOCK.matcher(text);
        List<X509Certificate> certificates = new ArrayList<>();
        PrivateKey privateKey = null;
        int blocks = 0;
        while (block.find()) {
            blocks++;
            String type = block.group(1);
            byte[] derBytes = decode(block.group(2), what, type);
            switch (type) {
                case "CERTIFICATE" -> {
                    if (certificates.size() >= MAX_CERTIFICATES) {
                        throw invalid(what, "it holds more than " + MAX_CERTIFICATES + " certificates");
                    }
                    certificates.add(certificateOf(derBytes, what, certificates.size() + 1));
                }
                case "PRIVATE KEY" -> {
                    if (privateKey != null) {
                        throw invalid(what, "it holds more than one private key");
                    }
                    privateKey = privateKeyOf(derBytes, what);
                }
                case "RSA PRIVATE KEY", "EC PRIVATE KEY", "ENCRYPTED PRIVATE KEY" -> throw invalid(what,
                        "a " + type + " block is not supported — convert the key to unencrypted PKCS#8"
                                + " (BEGIN PRIVATE KEY)");
                default -> throw invalid(what, "a " + type + " block is not supported — only CERTIFICATE and"
                        + " PRIVATE KEY");
            }
        }
        if (countOf(text, "-----BEGIN ") != blocks) {
            throw invalid(what, "a PEM block is incomplete or malformed");
        }
        if (certificates.isEmpty()) {
            throw invalid(what, "it holds no certificate");
        }
        if (privateKey != null) {
            requireMatching(privateKey, certificates.get(0), what);
        }
        return new Material(certificates, Optional.ofNullable(privateKey));
    }

    /** Certificates as PEM, without a key; this is how the host shows a client identity to an adapter. */
    static String certificatesPem(List<X509Certificate> certificates) {
        StringBuilder pem = new StringBuilder();
        for (X509Certificate certificate : certificates) {
            try {
                pem.append("-----BEGIN CERTIFICATE-----\n")
                        .append(Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                                .encodeToString(certificate.getEncoded()))
                        .append("\n-----END CERTIFICATE-----\n");
            } catch (CertificateException unreadable) {
                throw new IllegalArgumentException("a certificate of the client identity cannot be encoded");
            }
        }
        return pem.toString();
    }

    private static byte[] decode(String base64, String what, String type) {
        byte[] derBytes;
        try {
            derBytes = Base64.getMimeDecoder().decode(base64.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException notBase64) {
            //Without the cause: its message can show characters of the value
            throw invalid(what, "a " + type + " block is not valid base64");
        }
        if (derBytes.length == 0) {
            throw invalid(what, "a " + type + " block is empty");
        }
        return derBytes;
    }

    private static X509Certificate certificateOf(byte[] derBytes, String what, int position) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(derBytes));
        } catch (CertificateException | ClassCastException unreadable) {
            throw invalid(what, "certificate " + position + " cannot be read");
        }
    }

    private static PrivateKey privateKeyOf(byte[] derBytes, String what) {
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(derBytes);
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (GeneralSecurityException | IllegalArgumentException notThisOne) {
                //try the next algorithm
            }
        }
        throw invalid(what, "the private key cannot be read (RSA, EC, Ed25519 or Ed448 in PKCS#8)");
    }

    /** Rejects a key that does not belong to the first certificate: the connection could never be established. */
    private static void requireMatching(PrivateKey privateKey, X509Certificate certificate, String what) {
        String algorithm = switch (privateKey.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            default -> privateKey.getAlgorithm();
        };
        try {
            byte[] probe = new byte[32];
            new SecureRandom().nextBytes(probe);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(probe);
            if (verifier.verify(signature)) {
                return;
            }
        } catch (GeneralSecurityException | RuntimeException mismatch) {
            //reported below as a mismatch
        }
        throw invalid(what, "the private key does not belong to the first certificate");
    }

    private static int countOf(String text, String marker) {
        int count = 0;
        for (int at = text.indexOf(marker); at >= 0; at = text.indexOf(marker, at + marker.length())) {
            count++;
        }
        return count;
    }

    private static IllegalArgumentException invalid(String what, String why) {
        return new IllegalArgumentException(what + " is not a valid PEM certificate: " + why);
    }
}
