package com.rmm.remote.tck;

import com.rmm.remote.tck.RemoteServiceContractTest.Probe;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLException;

import static com.rmm.remote.tck.WireJson.abbreviated;

/**
 * The checks of cases 1 and 2 on the paths of a service
 * ({@code docs/REMOTE_PROTOCOL.md}, "Transport"): a request without a pinned
 * client certificate is refused, and a request whose binding header does not
 * name the binding of its certificate is answered with {@code 403}.
 */
final class TransportChecks {

    private TransportChecks() {
    }

    /**
     * Case 1: on every probe the handshake of {@code client} fails or the
     * service answers {@code 401}/{@code 403}; {@code how} names the
     * certificate of the client in the message.
     */
    static void requireRefused(ServiceClient client, String how, String binding, List<Probe> probes) {
        for (Probe probe : probes) {
            ServiceClient.Answer answer;
            try {
                answer = probe.send(client, List.of(binding));
            } catch (IOException failed) {
                if (isRefusal(failed)) {
                    //The handshake failed, as required
                    continue;
                }
                throw new ServiceContractViolation("Case 1: the service did not answer " + probe.path() + " " + how
                        + " (" + failed + ") — a timeout or a refused connection is no refusal; expected a failed"
                        + " handshake (a TLS alert or a reset connection) or 401/403 (docs/REMOTE_PROTOCOL.md,"
                        + " \"Transport\")");
            }
            if (answer.status() != 401 && answer.status() != 403) {
                throw new ServiceContractViolation("Case 1: the service answered " + probe.path() + " " + how
                        + " with " + answer.status() + " — it MUST require a client certificate and accept only the"
                        + " certificates its operator has pinned, on every path (docs/REMOTE_PROTOCOL.md,"
                        + " \"Transport\"); expected a failed handshake or 401/403. Body: "
                        + abbreviated(answer.body()));
            }
        }
    }

    /**
     * Whether the request failed at the handshake: a TLS error, or the peer
     * resets the connection or closes it without a byte of the answer. Under
     * TLS 1.3 a rejected client certificate often ends that way, because the
     * client no longer reads the alert. Never a timeout and never a refused
     * connection: they prove nothing about the certificate.
     */
    static boolean isRefusal(IOException failed) {
        boolean refused = false;
        for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof ConnectException) {
                return false;
            }
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            if (cause instanceof SSLException || cause instanceof SocketException || cause instanceof EOFException
                    || message.contains("connection reset") || message.contains("received no bytes")) {
                refused = true;
            }
        }
        return refused;
    }

    /**
     * Case 2: the service answers the probe of {@code client}, which presents
     * the certificate of {@code identity}, with {@code 403} when it carries
     * the binding headers {@code headers}; {@code how} names what is wrong
     * with them.
     */
    static void requireForbidden(ServiceClient client, RemoteServiceIdentities.Identity identity, Probe probe,
                                 List<String> headers, String how) throws IOException {
        ServiceClient.Answer answer = probe.send(client, headers);
        if (answer.status() != 403) {
            throw new ServiceContractViolation("Case 2: the service answered " + probe.path() + " with the header "
                    + RemoteServiceContractTest.BINDING_HEADER + " " + how + " (" + headers
                    + ", certificate of binding " + identity.bindingId() + ") with " + answer.status()
                    + " — it MUST answer 403 when the header"
                    + " is missing, repeated or names another binding than the client certificate"
                    + " (docs/REMOTE_PROTOCOL.md, \"Transport\"). Body: " + abbreviated(answer.body()));
        }
    }
}
