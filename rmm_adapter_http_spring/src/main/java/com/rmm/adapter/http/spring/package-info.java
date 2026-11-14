/**
 * The Spring facade over the host's HTTP path: a Spring
 * {@code ClientHttpConnector} on top of {@link com.rmm.adapter.api.AdapterHttp},
 * so that {@code WebClient} and {@code @HttpExchange} code of a plugin sends
 * through the host. The entry point is
 * {@link com.rmm.adapter.http.spring.AdapterHttpConnector#configure}: one
 * connector per binding, closed in {@code AdapterInstance.close()}. The run
 * travels in the Reactor context, taken over from the hook thread or set with
 * {@link com.rmm.adapter.http.spring.AdapterHttpConnector#run}.
 *
 * <p><b>Rules for adapters that use the facade.</b> The facade cannot enforce
 * them:</p>
 * <ul>
 *   <li><b>No throttle and no {@code retryWhen} of the adapter's own.</b> The
 *       deadline clock of the run does not see a wait with {@code Mono.delay}
 *       as throttle wait time, and a {@code retryWhen} also repeats a POST
 *       after a 429; every retry subscribes to the body again, so the facade
 *       builds a new upload. The host throttles and retries.</li>
 *   <li><b>Bearer authentication through {@code http.auth(origin, …)}</b>
 *       instead of a filter of the adapter's own; only then does the host
 *       send it to that origin only and never after a redirect to another
 *       one.</li>
 *   <li><b>Reading POSTs</b> (for example a search) are marked with
 *       {@link com.rmm.adapter.http.spring.AdapterHttpConnector#IDEMPOTENT};
 *       deadlines for attachments are set with
 *       {@link com.rmm.adapter.http.spring.AdapterHttpConnector#TIMEOUT}.</li>
 *   <li><b>Multipart above 4 MiB goes through the HTTP facade with a known
 *       length.</b> A multipart body of the {@code WebClient} has no
 *       {@code Content-Length}; above
 *       {@link com.rmm.adapter.http.spring.AdapterHttpConnector#BUFFERED_BODY_LIMIT}
 *       it fails. Attachment uploads therefore go through
 *       {@link com.rmm.adapter.api.AdapterHttp#open} with
 *       {@link com.rmm.adapter.api.Multipart} and a known length.</li>
 *   <li><b>Cookies</b> of a form login belong in the host's cookie store
 *       ({@code http.cookies()}), not in {@code WebClient.cookie(…)}.</li>
 * </ul>
 *
 * The library ships inside the plugin jar; it is not a host object and
 * depends only on {@code rmm_adapter_api} and {@code spring-webflux}.
 */
package com.rmm.adapter.http.spring;
