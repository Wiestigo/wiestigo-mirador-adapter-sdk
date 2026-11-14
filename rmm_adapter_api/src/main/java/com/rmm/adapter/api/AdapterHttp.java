package com.rmm.adapter.api;

import java.net.CookieStore;
import java.net.URI;
import java.util.Optional;

/**
 * The only network path of an adapter: the HTTP facade, built on JDK types
 * only. One host service per binding, obtained through
 * {@link AdapterContext#http()}.
 *
 * <p>For every request the host handles:
 * <ul>
 *   <li>address protection with IP pinning for the request and every redirect;
 *       in multi-tenant operation only {@code https};</li>
 *   <li>TLS with the trust anchors and the client certificate of the
 *       connection (its {@code CERTIFICATE} values, see
 *       {@link AdapterConfig#certificate}); the private key stays in the
 *       host;</li>
 *   <li>the rate limit per binding, a cap per target host, and retries, only
 *       for {@link AdapterRequest#repeatable()} requests: after a 429 (with or
 *       without {@code Retry-After}), a 503 and transient errors of the HTTP
 *       path (connection not established in time, unreachable, name
 *       temporarily not resolvable, connection pool exhausted, "not pinned").
 *       Creates and uploads are never retried. Attempts, waits and delays
 *       share the one deadline of the request, so a response that does not
 *       arrive within it is not retried but fails as transient;</li>
 *   <li>deadlines per request ({@link AdapterRequest}) and per run, stopping a
 *       run, and the metrics of the run;</li>
 *   <li>compression: the host sets {@code Accept-Encoding}, decompresses and
 *       counts the decompressed bytes against the maximum size;</li>
 *   <li>redirects according to {@link RedirectPolicy}, which also keeps
 *       authentication, {@link AdapterRequest#sensitiveHeaders()} and cookies
 *       at the origin of the request;</li>
 *   <li>OAuth ({@link Auth#oauth}): after a 401, at most one token renewal per
 *       request; the request is sent again only if it is
 *       {@link AdapterRequest#repeatable()}, otherwise the adapter receives
 *       the 401.</li>
 * </ul>
 *
 * <p>Transport errors (unreachable, not allowed, deadline, maximum size)
 * arrive as {@link AdapterException}, transient ones with
 * {@link AdapterException#isRetryable()}. A stop of the run is not a
 * transport error: the host interrupts the thread and may throw an unchecked
 * exception of its own, which the adapter must let pass. Every HTTP status arrives as a
 * response, including a 3xx the host does not follow. Naming a run in
 * {@link AdapterRequest#run()} that is not active in this binding is a
 * programming error ({@link IllegalStateException}).
 */
public interface AdapterHttp {

    /**
     * Sends the request and buffers the response up to the host's maximum size
     * (beyond it: {@link ResponseTooLargeException}). The deadline of the
     * request covers the whole exchange; for an upload it is an idle timeout
     * instead (see {@link AdapterRequest}).
     */
    AdapterResponse send(AdapterRequest request) throws AdapterException;

    /**
     * Sends the request and returns the response as a stream as soon as the
     * headers have arrived. The deadline of the request applies until then and
     * afterwards as an idle timeout between two reads (see
     * {@link AdapterRequest}). The caller closes the response.
     */
    StreamResponse open(AdapterRequest request) throws AdapterException;

    /**
     * Registers the authentication for an origin (scheme, host, port; the path
     * does not count). The host sets {@code Authorization} only for requests to
     * exactly this origin, and never after a redirect has left the origin of
     * the request ({@link RedirectPolicy}). A second call for the same origin
     * replaces the first.
     *
     * <p>For {@link Auth#oauth} the host fetches the token through
     * {@link TokenProvider#fetch(AdapterHttp)} with an HTTP path that carries
     * none of these registrations.
     *
     * @param origin absolute {@code http} or {@code https} address
     */
    void auth(URI origin, Auth auth);

    /**
     * The cookie store of this binding (for form logins). The host stores set
     * cookies here and sends them only to the origin that set them, never after
     * a redirect has left the origin of the request. It lives as long as the
     * binding.
     */
    CookieStore cookies();

    /**
     * The run the calling thread is bound to (a hook call or a task from
     * {@link AdapterExecutor#submit}); empty outside a run. It is the run the
     * host uses for a request without {@link AdapterRequest#run()}.
     *
     * <p>Facades that send on another thread read it on the thread that
     * triggers the request and pass it as {@link AdapterRequest#run()}. The
     * default for implementations other than the host is empty.
     */
    default Optional<SyncRunContext> currentRun() {
        return Optional.empty();
    }

    /**
     * Marks the calling thread as waiting for its own requests. For facades
     * that send a request on another thread while the triggering thread waits
     * (such as the Spring facade). Until {@link Awaiting#close()} the run's
     * call on this thread does not count as working, only the request itself:
     * while the request waits at the rate limit, at the cap per target host or
     * after a 429, the deadline clock stands still, as for a request sent
     * directly through this HTTP path.
     *
     * <ul>
     *   <li>Take the handle on the waiting thread, never on a non-blocking one
     *       (event loop, timer).</li>
     *   <li>It applies to the innermost run call the thread holds, and only if
     *       {@link #currentRun()} names a run there; otherwise it does
     *       nothing.</li>
     *   <li>Several handles on the same call add up, also with a wait on a
     *       {@link java.util.concurrent.Future} from
     *       {@link AdapterExecutor#submit}: the call counts as working again
     *       only when the last handle is closed and the wait is over.</li>
     *   <li>The handle may be closed on any thread; further {@code close()}
     *       calls do nothing.</li>
     *   <li>Before returning, the host holds a task from
     *       {@link AdapterExecutor#submit} while its run is paused, as before
     *       every {@link #send} and {@link #open}.</li>
     * </ul>
     *
     * <p>The default for implementations other than the host is a handle that
     * does nothing.
     *
     * @throws RuntimeException from the host if the run of the waiting task is
     *         stopped or finished, or the wait during a pause was interrupted
     *         (the thread's interrupt flag is then set). The facade then fails
     *         the request without sending it and takes no handle.
     */
    default Awaiting awaitingOwnRequests() {
        return Awaiting.NOTHING;
    }

    /** The handle from {@link #awaitingOwnRequests()}; its {@link #close()} does not throw. */
    interface Awaiting extends AutoCloseable {

        /** The handle that does nothing. */
        Awaiting NOTHING = () -> { };

        @Override
        void close();
    }
}
