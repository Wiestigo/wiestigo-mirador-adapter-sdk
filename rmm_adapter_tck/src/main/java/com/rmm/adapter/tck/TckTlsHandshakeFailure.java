package com.rmm.adapter.tck;

import java.io.IOException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.PKIXReason;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

/**
 * Tells a failed TLS handshake that no retry fixes from a transient failure and says what failed, with the host's
 * classification and wording.
 */
final class TckTlsHandshakeFailure {

    /** The maximum number of characters of a JDK message quoted in a handshake message; same as the host. */
    static final int MAX_DETAIL = 200;
    /** The start of the JDK's message for an alert from the peer. */
    private static final String FATAL_ALERT = "Received fatal alert: ";
    /** The alerts with which a peer rejects a client certificate; same as the host. */
    private static final Set<String> CLIENT_CERTIFICATE_ALERTS = Set.of("bad_certificate",
            "unsupported_certificate", "certificate_revoked", "certificate_expired", "certificate_unknown",
            "unknown_ca", "access_denied", "certificate_required");
    /**
     * The starts of the messages of the JDK's {@code SSLEngine} when the peer does not speak TLS (Java 17:
     * "Unrecognized SSL message, plaintext connection?" for a response without a TLS record).
     */
    private static final List<String> NOT_TLS = List.of("Unrecognized SSL message",
            "Unsupported or unrecognized SSL message");

    private TckTlsHandshakeFailure() {
    }

    /**
     * Whether a failure is a failed handshake that no retry fixes; the classification is the host's. That is a
     * {@link SSLHandshakeException} (certificate not trusted, not for the name, expired; no common protocol;
     * rejected by the peer), a certificate validation failure in the cause chain, a peer without TLS
     * ({@link #speaksNoTls}) or a rejected client certificate ({@link #refusesTheClientCertificate}). Transient,
     * and therefore not included: a handshake interrupted by a network error (an {@link IOException} that is not
     * an {@link SSLException}, below the {@link SSLHandshakeException}) and every other {@link SSLException}.
     */
    static boolean matches(Throwable failure) {
        boolean handshake = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof CertificateException || cause instanceof CertPathValidatorException
                    || speaksNoTls(cause) || refusesTheClientCertificate(cause)) {
                return true;
            }
            if (cause instanceof SSLHandshakeException) {
                handshake = true;
            } else if (handshake && cause instanceof IOException && !(cause instanceof SSLException)) {
                //The handshake broke off because the connection broke off: transient
                return false;
            }
        }
        return handshake;
    }

    /**
     * Whether the peer does not speak TLS. The JDK's {@code SSLEngine} reports that only in the message text.
     * Final, as in the host.
     */
    private static boolean speaksNoTls(Throwable cause) {
        return cause instanceof SSLException && cause.getMessage() != null
                && NOT_TLS.stream().anyMatch(cause.getMessage()::startsWith);
    }

    /**
     * Whether the peer rejected the client certificate. TLS 1.3 reports that only after the client's handshake,
     * as a certificate alert ({@link #CLIENT_CERTIFICATE_ALERTS}) that the JDK names only in the message text;
     * as in the host this is final as well. Java 17 puts the alert in parentheses in front of the text
     * ("(certificate_required) Received fatal alert: ...").
     */
    private static boolean refusesTheClientCertificate(Throwable cause) {
        String text = cause instanceof SSLException ? cause.getMessage() : null;
        int at = text == null ? -1 : text.indexOf(FATAL_ALERT);
        return at >= 0 && CLIENT_CERTIFICATE_ALERTS.contains(text.substring(at + FATAL_ALERT.length()).trim());
    }

    /**
     * What failed in the handshake, with the host's advice and wording and at most {@link #MAX_DETAIL} characters
     * from the JDK; none for a peer without TLS.
     */
    static String describe(Throwable failure) {
        Throwable innermost = failure;
        boolean notTls = false;
        boolean expired = false;
        boolean notYetValid = false;
        boolean revoked = false;
        boolean untrusted = false;
        boolean otherName = false;
        boolean certificate = false;
        boolean clientRefused = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            innermost = cause;
            clientRefused |= refusesTheClientCertificate(cause);
            notTls |= speaksNoTls(cause);
            expired |= cause instanceof CertificateExpiredException;
            notYetValid |= cause instanceof CertificateNotYetValidException;
            if (cause instanceof CertPathValidatorException invalid) {
                expired |= invalid.getReason() == CertPathValidatorException.BasicReason.EXPIRED;
                notYetValid |= invalid.getReason() == CertPathValidatorException.BasicReason.NOT_YET_VALID;
                revoked |= invalid.getReason() == CertPathValidatorException.BasicReason.REVOKED;
                untrusted |= invalid.getReason() == PKIXReason.NO_TRUST_ANCHOR;
            }
            untrusted |= cause instanceof CertPathBuilderException;
            if (cause instanceof CertificateException) {
                certificate = true;
                String text = cause.getMessage();
                otherName |= text != null && (text.startsWith("No subject alternative")
                        || text.startsWith("No name matching"));
            }
        }
        if (notTls) {
            return "the tool does not speak TLS on this port (does the address say https where the tool offers"
                    + " only http?)";
        }
        String text = innermost.getMessage() == null ? innermost.getClass().getSimpleName() : innermost.getMessage();
        String detail = " (" + (text.length() <= MAX_DETAIL ? text : text.substring(0, MAX_DETAIL) + "…") + ")";
        if (revoked) {
            return "the certificate of the tool has been revoked" + detail;
        }
        if (expired) {
            return "the certificate of the tool has expired - the tool needs a new certificate" + detail;
        }
        if (notYetValid) {
            return "the certificate of the tool is not valid yet - check the clock of the tool and of this host"
                    + detail;
        }
        if (untrusted) {
            return "the certificate of the tool is not trusted by this connection - add its certification authority"
                    + " as a trust anchor of the connection (with trust anchors, only they are trusted; without,"
                    + " the public certification authorities)" + detail;
        }
        if (otherName) {
            return "the certificate of the tool was refused: it is not issued for the host of the address" + detail;
        }
        if (certificate) {
            return "the certificate of the tool was refused" + detail;
        }
        if (clientRefused) {
            return "the tool refused the client certificate of this connection" + detail;
        }
        return "the tool refused the handshake or offers neither TLS 1.3 nor TLS 1.2" + detail;
    }
}
