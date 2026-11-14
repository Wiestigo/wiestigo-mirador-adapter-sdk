package unit;

/**
 * Certificates for the tests of {@code CERTIFICATE} values in the TCK: a trust anchor (a certificate
 * authority of its own, {@code CN=TCK Test CA}, certificate only) and, separately, a client certificate
 * ({@code CN=tool.test}) with its private key (EC P-256, PKCS#8). Both are generated on first use
 * ({@link TckTlsFixtures#selfSigned}), so no private key sits in the source for a secret scan to flag.
 */
final class TckPemFixtures {

    private static String anchor;
    private static String client;

    private TckPemFixtures() {
    }

    /** The certificate of the certificate authority, without a key. */
    static synchronized String anchor() {
        if (anchor == null) {
            anchor = TckTlsFixtures.selfSigned("-dname", "CN=TCK Test CA", "-validity", "36500", "-ext", "bc:c")
                    .certificatePem();
        }
        return anchor;
    }

    /** A client certificate and its private key in one value, the form in which a client certificate is stored. */
    static synchronized String clientWithKey() {
        if (client == null) {
            client = TckTlsFixtures.selfSigned("-dname", "CN=tool.test", "-validity", "36500").withKeyPem();
        }
        return client;
    }
}
