package unit_tests;

import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.RedirectPolicy;
import com.rmm.adapter.http.spring.AdapterHttpConnector;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Spring facade of the host's HTTP path: {@code WebClient} and
 * {@code @HttpExchange} through {@link AdapterHttpConnector} against a JDK
 * stand-in for {@link AdapterHttp}. Checked here:
 * <ul>
 *   <li>Run: it travels in the Reactor context across {@code Mono.delay}
 *       and {@code retryWhen}, also through synchronous
 *       {@code @HttpExchange} methods.</li>
 *   <li>Requests: attributes per request, headers with several values, the
 *       exact status, headers set by the host; invalid cookies do not fail
 *       the request.</li>
 * </ul>
 * Threads and {@code close()} are checked in
 * {@link TestAdapterHttpConnectorThreads}, bodies in
 * {@link TestAdapterHttpConnectorBodies}, cancelling in
 * {@link TestAdapterHttpConnectorCancelling} and the handle from
 * {@code awaitingOwnRequests()} in {@link TestAdapterHttpConnectorAwaiting}.
 */
class TestAdapterHttpConnector extends AdapterHttpConnectorFixture {

    // --- Run in the Reactor context -------------------------------------------

    @Test
    void theRunTravelsInTheContextAcrossDelayAndRetry() {
        AtomicInteger calls = new AtomicInteger();
        http.handler = request -> calls.incrementAndGet() == 1
                ? http.response(503, Map.of(), InputStream.nullInputStream(), OptionalLong.of(0))
                : http.text(200, "ok");
        //Another run is bound: contextWrite takes precedence over it
        FakeHttp.BOUND.set(OTHER_RUN);

        String body = client(delayed()).get().uri("/rest/issue/1")
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.fixedDelay(2, Duration.ofMillis(10)))
                .contextWrite(AdapterHttpConnector.run(RUN))
                .block(TIMEOUT);

        assertEquals("ok", body);
        assertEquals(2, http.requests.size(), "the 503 was retried by retryWhen");
        for (AdapterRequest request : http.requests) {
            assertEquals(Optional.of(RUN), request.run(), "every attempt carries the run explicitly");
        }
        assertEquals(List.of(Optional.of(RUN), Optional.of(RUN)), http.resolvedRuns);
        assertEquals(0, http.currentRunCalls.get(), "a run in the context wins; the bound one is not read");
    }

    @Test
    void aSynchronousMethodCarriesTheBoundRunAcrossDelayAndRetry() {
        AtomicInteger calls = new AtomicInteger();
        http.handler = request -> calls.incrementAndGet() == 1
                ? http.response(503, Map.of(), InputStream.nullInputStream(), OptionalLong.of(0))
                : http.text(200, "issue " + request.uri().getPath());
        SyncToolApi api = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client(throttleWithRetry())))
                .build()
                .createClient(SyncToolApi.class);
        //The host binds the run to the thread of the hook; after Mono.delay that thread is gone
        FakeHttp.BOUND.set(RUN);

        ResponseEntity<String> answer = api.issue("REQ-7");

        assertEquals("issue /rest/issue/REQ-7", answer.getBody());
        assertEquals(2, http.requests.size(), "the 503 was retried inside the filter");
        for (AdapterRequest request : http.requests) {
            assertEquals(Optional.of(RUN), request.run(), "every attempt carries the bound run explicitly");
        }
        for (String thread : http.blockingThreads) {
            assertTrue(onConnectorThread(thread), "sent on " + thread + ", where no run is bound");
        }
    }

    @Test
    void withoutABoundRunTheRequestGoesExplicitlyWithoutARun() {
        http.handler = request -> http.text(200, "ok");

        assertEquals("ok", client(delayed()).get().uri("/rest/serverInfo").retrieve().bodyToMono(String.class)
                .block(TIMEOUT));
        assertEquals(Optional.empty(), http.requests.get(0).run());
        assertTrue(http.currentRunCalls.get() >= 1, "the bound run was looked up when subscribing");
    }

    @Test
    void withoutConfigureAndWithoutARunTheRequestFailsInsteadOfGoingSilentlyWithoutARun() {
        http.handler = request -> http.text(200, "ok");
        AdapterHttpConnector connector = AdapterHttpConnector.of(http);
        connectors.add(connector);
        WebClient plain = WebClient.builder().clientConnector(connector).baseUrl(TOOL.toString()).build();
        FakeHttp.BOUND.set(RUN);

        WebClientRequestException failure = assertThrows(WebClientRequestException.class,
                () -> plain.get().uri("/rest/x").retrieve().toBodilessEntity().block(TIMEOUT));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("AdapterHttpConnector.configure"),
                failure.getCause().getMessage());
        assertEquals(List.of(), http.requests, "nothing was sent without a run");
        assertEquals("ok", plain.get().uri("/rest/x").retrieve().bodyToMono(String.class)
                .contextWrite(AdapterHttpConnector.run(RUN)).block(TIMEOUT));
        assertEquals(Optional.of(RUN), http.requests.get(0).run());
    }

    interface ToolApi {
        @GetExchange("/rest/issue/{key}")
        Mono<String> issue(@PathVariable("key") String key);
    }

    @Test
    void httpExchangeInterfacesStayUnchanged() {
        http.handler = request -> http.text(200, "issue " + request.uri().getPath());
        ToolApi api = HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client(delayed())))
                .build()
                .createClient(ToolApi.class);

        String answer = api.issue("REQ-7").contextWrite(AdapterHttpConnector.run(RUN)).block(TIMEOUT);

        assertEquals("issue /rest/issue/REQ-7", answer);
        assertEquals(Optional.of(RUN), http.requests.get(0).run());
        assertEquals("GET", http.requests.get(0).method());
    }

    // --- Headers and status ----------------------------------------------------

    @Test
    void multipleValuesPerHeaderAreKept() {
        http.handler = request -> http.response(200, Map.of(
                        "Link", List.of("<https://tool.test/p2>; rel=next", "<https://tool.test/p9>; rel=last"),
                        "Set-Cookie", List.of("s1=x; Path=/; HttpOnly", "s2=y; SameSite=Lax")),
                InputStream.nullInputStream(), OptionalLong.of(0));

        Seen seen = client().get().uri("/rest/items")
                .header("X-Multi", "a", "b")
                .cookie("c1", "v1")
                .cookie("c2", "v2")
                .exchangeToMono(response -> response.releaseBody().thenReturn(
                        new Seen(response.headers().header("Link"), response.cookies())))
                .block(TIMEOUT);

        AdapterRequest sent = http.requests.get(0);
        assertEquals(List.of("a", "b"), sent.headers().get("X-Multi"));
        assertEquals(Optional.of("c1=v1; c2=v2"), sent.header("Cookie"));
        assertTrue(sent.sensitiveHeaders().contains("Cookie"), "the host sends cookies to the origin only");
        assertNotNull(seen);
        assertEquals(List.of("<https://tool.test/p2>; rel=next", "<https://tool.test/p9>; rel=last"), seen.header());
        ResponseCookie s1 = seen.cookies().getFirst("s1");
        assertNotNull(s1);
        assertEquals("x", s1.getValue());
        assertTrue(s1.isHttpOnly());
        assertEquals("Lax", seen.cookies().getFirst("s2").getSameSite());
    }

    private record Seen(List<String> header, MultiValueMap<String, ResponseCookie> cookies) {
    }

    @Test
    void theStatusArrivesExactlyAndRedirectsAreNotFollowed() {
        List<Integer> statuses = List.of(200, 201, 204, 299, 301, 302, 304, 400, 404, 418, 429, 499, 503, 599);
        List<Integer> seen = new ArrayList<>();
        for (int status : statuses) {
            http.handler = request -> http.response(status, Map.of("Location", List.of("https://other.test/x")),
                    InputStream.nullInputStream(), OptionalLong.of(0));
            seen.add(client().get().uri("/rest/status")
                    .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value()))
                    .block(TIMEOUT));
        }

        assertEquals(statuses, seen);
        assertEquals(statuses.size(), http.requests.size(), "a 3xx comes back as a response, never followed");
        for (AdapterRequest request : http.requests) {
            assertSame(RedirectPolicy.never(), request.redirects());
        }
    }

    @Test
    void theHostSetsTheTransportHeaders() {
        http.handler = request -> http.response(200, Map.of(
                        "Content-Encoding", List.of("gzip"),
                        "Content-Length", List.of("999"),
                        "Content-Type", List.of("text/plain")),
                new ByteArrayInputStream("unpacked".getBytes(StandardCharsets.UTF_8)), OptionalLong.empty());

        HttpHeaders answer = client().post().uri("/rest/search")
                .header("Accept-Encoding", "gzip")
                .header("Connection", "keep-alive")
                .header("Expect", "100-continue")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.headers().asHttpHeaders()))
                .block(TIMEOUT);

        AdapterRequest sent = http.requests.get(0);
        for (String name : List.of("Accept-Encoding", "Connection", "Expect", "Content-Length", "Content-Type")) {
            assertFalse(sent.headers().containsKey(name), name + " is set by the host or comes from the body");
        }
        assertEquals(Optional.of("application/json"), sent.body().orElseThrow().contentType());
        assertNotNull(answer);
        assertEquals(List.of(), answer.getOrEmpty("Content-Encoding"), "the body always arrives unpacked");
        assertEquals(-1, answer.getContentLength(), "length unknown after unpacking");
        assertEquals("text/plain", answer.getFirst("Content-Type"));

        http.handler = request -> http.text(200, "abc");
        HttpHeaders known = client().get().uri("/rest/x")
                .exchangeToMono(response -> response.releaseBody().thenReturn(response.headers().asHttpHeaders()))
                .block(TIMEOUT);
        assertNotNull(known);
        assertEquals(3, known.getContentLength());
    }

    @Test
    void failuresOfTheHttpPathArriveAsTheCause() {
        http.handler = request -> {
            throw new AdapterException("tool not reachable", null, true);
        };

        WebClientRequestException failure = assertThrows(WebClientRequestException.class,
                () -> client().get().uri("/rest/x").retrieve().toBodilessEntity().block(TIMEOUT));

        AdapterException cause = assertInstanceOf(AdapterException.class, failure.getCause());
        assertTrue(cause.isRetryable());

        http.handler = request -> {
            throw new IllegalStateException("run is not active in this binding");
        };
        WebClientRequestException notActive = assertThrows(WebClientRequestException.class,
                () -> client().get().uri("/rest/x").retrieve().toBodilessEntity()
                        .contextWrite(AdapterHttpConnector.run(RUN)).block(TIMEOUT));
        assertInstanceOf(IllegalStateException.class, notActive.getCause());
    }

    // --- Cookies and attributes -------------------------------------------------

    /** The samples are {@code s=a b}, {@code s=a,b} and {@code Domain=tool_host}. */
    @Test
    void invalidCookiesAreOnlyMissingAndTheResponseIsClosed() throws InterruptedException {
        TrackedStream body = http.tracked(new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8)));
        List<String> setCookies = List.of("s=a b", "s=a,b", "u=1; Domain=tool_host",
                "ok=1; Path=/; Max-Age=60; SameSite=Strict", "lax=2; SameSite=Lax; HttpOnly", "plain=3");
        http.handler = request -> http.response(200, Map.of("Set-Cookie", setCookies), body, OptionalLong.of(2));

        Seen seen = client().get().uri("/rest/login")
                .exchangeToMono(response -> response.bodyToMono(String.class).thenReturn(
                        new Seen(response.headers().header("Set-Cookie"), response.cookies())))
                .block(TIMEOUT);

        assertNotNull(seen);
        assertEquals(setCookies, seen.header(), "the raw headers stay");
        assertEquals(List.of("ok", "lax", "plain"), List.copyOf(seen.cookies().keySet()));
        ResponseCookie ok = seen.cookies().getFirst("ok");
        assertEquals("Strict", ok.getSameSite());
        assertEquals(Duration.ofSeconds(60), ok.getMaxAge());
        assertEquals("/", ok.getPath());
        assertEquals("Lax", seen.cookies().getFirst("lax").getSameSite(), "SameSite per cookie");
        assertTrue(seen.cookies().getFirst("lax").isHttpOnly());
        assertEquals(null, seen.cookies().getFirst("plain").getSameSite());
        assertTrue(body.closed.await(5, TimeUnit.SECONDS));
    }

    /** Attributes per request instead of a context key. */
    @Test
    void attributesMarkAReadOnlyPostAndSetTheTimeout() {
        http.handler = request -> http.text(200, "ok");
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("file", "hello");

        client().post().uri("/rest/api/2/search").contentType(MediaType.APPLICATION_JSON)
                .attribute(AdapterHttpConnector.IDEMPOTENT, true)
                .attribute(AdapterHttpConnector.TIMEOUT, Duration.ofSeconds(7))
                .bodyValue("{\"jql\":\"project=REQ\"}").retrieve().toBodilessEntity().block(TIMEOUT);
        client().post().uri("/rest/api/2/issue").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").retrieve().toBodilessEntity().block(TIMEOUT);
        client().post().uri("/rest/api/2/attachments").attribute(AdapterHttpConnector.IDEMPOTENT, true)
                .body(BodyInserters.fromMultipartData(parts.build())).retrieve().toBodilessEntity().block(TIMEOUT);
        client().put().uri("/rest/api/2/counter").attribute(AdapterHttpConnector.IDEMPOTENT, false)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").retrieve().toBodilessEntity().block(TIMEOUT);

        AdapterRequest search = http.requests.get(0);
        assertTrue(search.idempotent());
        assertTrue(search.repeatable(), "a marked read-only POST may be repeated");
        assertEquals(Optional.of(Duration.ofSeconds(7)), search.timeout());
        AdapterRequest create = http.requests.get(1);
        assertFalse(create.idempotent(), "an unmarked POST is never repeated");
        assertEquals(Optional.empty(), create.timeout());
        AdapterRequest upload = http.requests.get(2);
        assertFalse(upload.idempotent(), "an upload stays non-idempotent even when marked");
        assertFalse(upload.repeatable());
        assertFalse(http.requests.get(3).idempotent(), "false takes back the default of PUT");

        WebClientRequestException wrongType = assertThrows(WebClientRequestException.class, () -> client().get()
                .uri("/rest/x").attribute(AdapterHttpConnector.TIMEOUT, "7s").retrieve().toBodilessEntity()
                .block(TIMEOUT));
        assertInstanceOf(IllegalArgumentException.class, wrongType.getCause());
        assertEquals(4, http.requests.size());
    }
}
