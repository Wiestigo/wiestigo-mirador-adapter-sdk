package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.Auth;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TokenProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.rmm.adapter.tck.TckHttpMessages.describe;

/**
 * The sign-ins of a binding per origin (Basic, Bearer, OAuth) and the OAuth tokens fetched for them. As in the
 * host, a token is fetched through a view without sign-ins, never concurrently for the same binding, and renewed
 * before it expires.
 */
final class TckHttpSignIns {

    /** The HTTP path fetches a new token this long before expiry, at most half the lifetime; same as the host. */
    static final Duration RENEW_AHEAD = Duration.ofSeconds(30);

    private final String bindingId;
    /** The longest time a request waits for another one that is fetching the token. */
    private final Duration longestWait;
    /** The view without sign-ins that a token provider fetches through. */
    private final AdapterHttp unauthenticated;
    private final Map<String, Auth> auths = new ConcurrentHashMap<>();
    private final Map<String, Held> tokens = new ConcurrentHashMap<>();
    /** The binding's providers never run concurrently; same as the host. */
    private final ReentrantLock tokenLock = new ReentrantLock();
    /** The end of the deadline of the request for which this thread is currently fetching a token. */
    private final ThreadLocal<Long> fetchDeadline = new ThreadLocal<>();
    /** The sign-in values (password, Basic value, token), for the check for foreign credentials (case 2). */
    private final Set<String> values = ConcurrentHashMap.newKeySet();

    TckHttpSignIns(String bindingId, Duration longestWait, AdapterHttp unauthenticated) {
        this.bindingId = bindingId;
        this.longestWait = longestWait;
        this.unauthenticated = unauthenticated;
    }

    /**
     * The sign-in of an attempt.
     *
     * @param value the {@code Authorization} value; {@code null} = none
     * @param token the OAuth token behind it; {@code null} for Basic, Bearer or no sign-in
     */
    record Signed(String value, Held token) {
        static final Signed NONE = new Signed(null, null);
    }

    /** A fetched token and when it was fetched. */
    record Held(TokenProvider.Token token, Instant fetchedAt) {

        /** Whether it must be fetched anew now: {@link #RENEW_AHEAD} before expiry, at most half the lifetime. */
        boolean expiresSoon() {
            Instant expiresAt = token.expiresAt();
            if (expiresAt == null) {
                return false;
            }
            Duration half = Duration.between(fetchedAt, expiresAt).dividedBy(2);
            Duration ahead = half.isNegative() ? Duration.ZERO : half.compareTo(RENEW_AHEAD) < 0 ? half : RENEW_AHEAD;
            return !Instant.now().isBefore(expiresAt.minus(ahead));
        }

        @Override
        public String toString() {
            return "Held[" + fetchedAt + "]";
        }
    }

    /** Sets the sign-in of an origin; a token fetched for an earlier sign-in is dropped. */
    void register(String origin, Auth auth) {
        notFromTheProvider(origin);
        if (auth instanceof Auth.Basic basic) {
            values.add(basic.password().reveal());
            values.add(basicValue(basic));
        } else if (auth instanceof Auth.Bearer bearer) {
            values.add(bearer.token().reveal());
        }
        auths.put(origin, auth);
        tokens.remove(origin);
    }

    /** The sign-in values of this binding, including fetched tokens. */
    Set<String> values() {
        return Set.copyOf(values);
    }

    /**
     * The end of the deadline ({@link System#nanoTime()}) of the request for which this thread is currently
     * fetching a token; {@code null} if it is fetching none.
     */
    Long fetchDeadline() {
        return fetchDeadline.get();
    }

    /** Whether the sign-in of the origin is OAuth, so that a new token can be fetched after a 401. */
    boolean usesOAuth(String origin) {
        return auths.get(origin) instanceof Auth.OAuth;
    }

    /** Drops the token of the origin if it still is this one; the next request fetches a new one. */
    void drop(String origin, Held token) {
        tokens.remove(origin, token);
    }

    /** The {@code Authorization} value at the origin; {@link Signed#NONE} without a sign-in. */
    Signed authorization(String origin, SyncRunContext run, long deadline) throws AdapterException {
        Auth auth = auths.get(origin);
        if (auth instanceof Auth.Basic basic) {
            return new Signed("Basic " + basicValue(basic), null);
        }
        if (auth instanceof Auth.Bearer bearer) {
            return new Signed("Bearer " + bearer.token().reveal(), null);
        }
        if (auth instanceof Auth.OAuth oauth) {
            Held token = token(origin, oauth.provider(), run, deadline);
            return new Signed("Bearer " + token.token().accessToken().reveal(), token);
        }
        return Signed.NONE;
    }

    /** The Base64 form in the charset of the sign-in (default UTF-8, as in the host). */
    private static String basicValue(Auth.Basic basic) {
        return basic.token().reveal();
    }

    /**
     * The token of the origin; never fetched concurrently for the same binding. A valid token is returned without
     * the lock. Otherwise the thread waits for the lock at most until the end of the request's deadline and the
     * rest of the run's deadline, and the provider's requests end with the request's deadline at the latest, as
     * in the host.
     */
    private Held token(String origin, TokenProvider provider, SyncRunContext run, long deadline)
            throws AdapterException {
        Held known = tokens.get(origin);
        if (known != null && !known.expiresSoon()) {
            return known;
        }
        notFromTheProvider(origin);
        lockTokens(origin, run, deadline);
        try {
            known = tokens.get(origin);
            if (known != null && !known.expiresSoon()) {
                return known;
            }
            TokenProvider.Token fresh;
            Long before = fetchDeadline.get();
            fetchDeadline.set(deadline);
            try {
                fresh = provider.fetch(unauthenticated);
            } catch (AdapterException failed) {
                throw new AdapterException("The OAuth token for " + origin + " could not be fetched: "
                        + failed.getMessage(), failed, failed.isRetryable(), failed.httpStatus().orElse(0));
            } catch (RuntimeException failed) {
                throw new AdapterException("The OAuth token for " + origin + " could not be fetched ("
                        + describe(failed) + ")", failed, false);
            } finally {
                if (before == null) {
                    fetchDeadline.remove();
                } else {
                    fetchDeadline.set(before);
                }
            }
            if (fresh == null) {
                throw new AdapterException("The OAuth token for " + origin + " could not be fetched: the token"
                        + " provider returned no token", null, false);
            }
            values.add(fresh.accessToken().reveal());
            Held held = new Held(fresh, Instant.now());
            //Stored only while the origin still has the same provider (a newer auth wins)
            if (auths.get(origin) instanceof Auth.OAuth now && now.provider() == provider) {
                tokens.put(origin, held);
            }
            return held;
        } finally {
            tokenLock.unlock();
        }
    }

    /** Waits for the provider lock, at most until the request's deadline and the run's deadline. */
    private void lockTokens(String origin, SyncRunContext run, long deadline) throws AdapterException {
        long started = System.nanoTime();
        long own = Math.min(deadline - started, longestWait.toNanos());
        Duration runLeft = run == null || run.deadline() == null ? null
                : Duration.between(Instant.now(), run.deadline());
        boolean atRunDeadline = runLeft != null && runLeft.toNanos() < own;
        long wait = Math.max(0, atRunDeadline ? runLeft.toNanos() : own);
        try {
            if (tokenLock.tryLock(wait, TimeUnit.NANOSECONDS)) {
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AdapterException("Interrupted while waiting for the OAuth token for " + origin, null, false);
        }
        if (atRunDeadline) {
            throw new AdapterException("The run has reached its deadline while waiting for the OAuth token for "
                    + origin, null, false);
        }
        throw new AdapterException("Waiting for the OAuth token for " + origin + " of connection <" + bindingId
                + "> timed out after " + Duration.ofNanos(System.nanoTime() - started).toMillis()
                + " ms (another request is fetching it)", null, true);
    }

    /** A provider never uses this HTTP path instead of its view: it would wait for itself (as in the host). */
    private void notFromTheProvider(String origin) {
        if (tokenLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("The token provider for " + origin + " of connection <" + bindingId
                    + "> must fetch over the network path it is given (without auth), not over context.http()");
        }
    }
}
