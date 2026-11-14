package com.rmm.adapter.tck;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Case 2 for one binding: every request it sends to the fake tool goes
 * through the HTTP path of its own context and carries no credential of
 * another binding.
 */
final class OwnNetworkPath {

    private OwnNetworkPath() {
    }

    /** Every request that {@code work} sends to the tool carries the header of {@code own}. */
    static void require(String side, FakeHttpTool tool, ContractContext own, ContractContext other, Callable<?> work) {
        tool.forget();
        Throwable failure = null;
        try {
            work.call();
        } catch (Exception | AssertionError failed) {
            failure = failed;
        }
        if (tool.refused() > 0) {
            throw new TckViolation(tool.refused() + " request(s) of binding " + side + " reached networkTool()"
                    + " without the header that only context.http() sets — the adapter sends with its own HTTP"
                    + " client; every request goes through context.http() (address protection,"
                    + " TLS, throttle and deadline belong to the host)"
                    + (failure == null ? "" : "\nThe binding then failed: " + failure));
        }
        if (failure != null) {
            throw new TckViolation("Binding " + side + " failed while it worked against networkTool(): " + failure);
        }
        List<FakeHttpTool.Request> seen = tool.requests();
        if (seen.isEmpty()) {
            throw new TckViolation("Binding " + side + " sent no request to networkTool() — point configuration()"
                    + " at networkTool().uri(...), or return no networkTool() for an adapter without network");
        }
        Set<String> foreign = other == null ? Set.of() : foreignSecrets(other, own);
        for (FakeHttpTool.Request request : seen) {
            String carried = carriedSecret(request, foreign);
            if (carried != null) {
                throw new TckViolation("Binding " + side + " sent " + request + " with a credential of the other"
                        + " binding (in " + carried + ") — a foreign token: the ToolAdapter keeps a client, a token"
                        + " or a sign-in of an earlier binding; every binding signs in with its own config()");
            }
            if (other != null && other.networkStamp().equals(request.stamp())) {
                throw new TckViolation("Binding " + side + " sent " + request + " through the network path of the"
                        + " other binding — the ToolAdapter keeps the http() of an earlier binding; every"
                        + " binding takes http() of its own context");
            }
            if (!own.networkStamp().equals(request.stamp())) {
                throw new TckViolation("Binding " + side + " sent " + request + " with a binding header that is not"
                        + " its own — only context.http() of the binding's own context sets it");
            }
        }
    }

    /**
     * The secrets of {@code other} (from its {@code config()} and its
     * sign-ins) that are not also in the {@code config()} of {@code own} (two
     * connections with the same account are no cross-talk), from the length
     * on that the log masks too.
     */
    private static Set<String> foreignSecrets(ContractContext other, ContractContext own) {
        Set<String> foreign = new HashSet<>(other.revealedSecrets());
        foreign.removeAll(own.configSecrets());
        foreign.removeIf(secret -> secret.length() < MapConfig.MIN_MASKED_LENGTH);
        return foreign;
    }

    /** Where the request carries one of the secrets (header, address, body); {@code null} if nowhere. */
    private static String carriedSecret(FakeHttpTool.Request request, Set<String> secrets) {
        if (secrets.isEmpty()) {
            return null;
        }
        String address = request.path() + (request.query() == null ? "" : "?" + request.query());
        String body = new String(request.body(), StandardCharsets.UTF_8);
        for (String secret : secrets) {
            for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                if (header.getValue().stream().anyMatch(value -> value.contains(secret))) {
                    return "the header " + header.getKey();
                }
            }
            if (address.contains(secret) || URLDecoder.decode(address, StandardCharsets.UTF_8).contains(secret)) {
                return "the address";
            }
            if (body.contains(secret)) {
                return "the body";
            }
        }
        return null;
    }
}
