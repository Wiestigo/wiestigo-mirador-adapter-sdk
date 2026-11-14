package com.rmm.adapter.api;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Redirect policy of an {@link AdapterRequest}: "never" ({@link #never()}, the
 * default) or "follow with re-check" over at most {@code maxHops} hops
 * ({@link #follow(int)}).
 *
 * <p>Before every hop the host checks {@link #refusal} and then checks the
 * target like a new request (address protection, pinned IP). Independently of
 * the policy:
 * <ul>
 *   <li><b>Uploads never follow:</b> a request with a body follows no redirect
 *       (301, 302, 303, 307, 308), not even with a replayable body and not to
 *       the same origin. The adapter receives the 3xx response and decides
 *       itself, for example with a GET to {@code Location}.</li>
 *   <li>The host never changes the method. It therefore follows a 303 ("see
 *       other") only for GET and HEAD.</li>
 *   <li>Never from {@code https} to {@code http}.</li>
 *   <li>If a hop leaves the origin of the request ({@link #sameOrigin}), then
 *       for it and the rest of the chain, including a hop back, the host sends
 *       no {@link AdapterRequest#sensitiveHeaders()} of the request, no
 *       authentication from {@link AdapterHttp#auth} (not even the one
 *       registered for the new origin) and no cookies of the cookie store for
 *       any origin; it does not store cookies from the responses of such hops.
 *       If an adapter needs authentication at the target (for example after a
 *       hop to a second tool), it does not follow and sends a request of its
 *       own to {@code Location}.</li>
 * </ul>
 * If the policy refuses a hop, the 3xx response goes to the adapter like any
 * other response.
 */
public final class RedirectPolicy {

    /** Maximum number of hops an adapter can allow. */
    public static final int MAX_HOPS = 10;

    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final RedirectPolicy NEVER = new RedirectPolicy(0);

    private final int maxHops;

    private RedirectPolicy(int maxHops) {
        this.maxHops = maxHops;
    }

    /** Do not follow redirects; the 3xx response goes to the adapter. */
    public static RedirectPolicy never() {
        return NEVER;
    }

    /** Follow at most {@code maxHops} hops (1 to {@link #MAX_HOPS}), each one checked anew. */
    public static RedirectPolicy follow(int maxHops) {
        if (maxHops < 1 || maxHops > MAX_HOPS) {
            throw new IllegalArgumentException("maxHops must be between 1 and " + MAX_HOPS + ": " + maxHops);
        }
        return new RedirectPolicy(maxHops);
    }

    /** Allowed hops; 0 = never follow. */
    public int maxHops() {
        return maxHops;
    }

    public boolean follows() {
        return maxHops > 0;
    }

    /**
     * Whether the host may follow the hop: the minimum rule of the contract
     * (see the class) for the policy of the request
     * ({@link AdapterRequest#redirects()}). It takes the whole request so that
     * the host can tighten it without changing the signature: a later host may
     * refuse more, never less. An adapter does not need this method and must
     * not rely on a hop allowed here being followed.
     *
     * @param request the original request (method, body, policy)
     * @param status  status of the response (301, 302, 303, 307 or 308)
     * @param from    address that was redirected (for the first hop, that of
     *                the request)
     * @param to      target of the hop, already resolved against {@code from}
     *                ({@code Location} may be relative)
     * @param hop     number of this hop, starting at 1
     * @return empty = follow; otherwise the reason why not
     */
    public static Optional<String> refusal(AdapterRequest request, int status, URI from, URI to, int hop) {
        Objects.requireNonNull(request, "request");
        if (!REDIRECTS.contains(status)) {
            throw new IllegalArgumentException("status " + status + " is not a redirect");
        }
        if (hop < 1) {
            throw new IllegalArgumentException("hop must start at 1: " + hop);
        }
        int allowed = request.redirects().maxHops;
        if (allowed == 0) {
            return Optional.of("redirects are not followed for this request");
        }
        if (hop > allowed) {
            return Optional.of("too many redirects (more than " + allowed + ")");
        }
        if (request.body().isPresent()) {
            return Optional.of("a request with a body does not follow a " + status + " redirect");
        }
        if (status == 303 && !request.method().equals("GET") && !request.method().equals("HEAD")) {
            return Optional.of("a 303 redirect would change the method " + request.method());
        }
        Optional<String> target = HttpRules.httpUriProblem(to);
        if (target.isPresent()) {
            return Optional.of("redirect target " + target.get());
        }
        if (HttpRules.isHttps(from) && !HttpRules.isHttps(to)) {
            return Optional.of("redirect from https to http is refused");
        }
        return Optional.empty();
    }

    /**
     * Whether two addresses have the same origin: scheme, host and port (80 or
     * 443 if not given), case-insensitive; also for host names with an
     * underscore.
     */
    public static boolean sameOrigin(URI a, URI b) {
        if (a == null || b == null || a.getScheme() == null || b.getScheme() == null) {
            return false;
        }
        String hostA = HttpRules.hostOf(a);
        String hostB = HttpRules.hostOf(b);
        if (hostA == null || hostB == null) {
            return false;
        }
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && hostA.equals(hostB)
                && HttpRules.portOf(a) == HttpRules.portOf(b);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RedirectPolicy policy && policy.maxHops == maxHops;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(maxHops);
    }

    @Override
    public String toString() {
        return maxHops == 0 ? "RedirectPolicy[never]" : "RedirectPolicy[follow " + maxHops + "]";
    }
}
