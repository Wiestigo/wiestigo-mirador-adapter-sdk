package com.rmm.adapter.http.spring;

import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.Auth;
import com.rmm.adapter.api.StreamResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The threads of a connector cost something only while it works: once built
 * it starts none, with work only threads of its own name
 * ({@code rmm-http-N-M}, no evictor thread as with the {@code boundedElastic}
 * scheduler of Reactor), without work they end after the idle time, and
 * {@code close()} ends them at once. An idle binding therefore holds no
 * thread. Requests sent one after the other reuse the idle threads instead
 * of starting new ones up to the maximum. The tests set a short idle time
 * through the constructor.
 */
class TestConnectorThreads {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final List<AdapterHttpConnector> connectors = new CopyOnWriteArrayList<>();
    private final List<Thread> sentOn = new CopyOnWriteArrayList<>();
    private final Set<Thread> readOn = ConcurrentHashMap.newKeySet();

    @AfterEach
    void close() {
        connectors.forEach(AdapterHttpConnector::close);
    }

    @Test
    void aConnectorStartsThreadsOnlyForWorkAndTheyEndWhenIdle() throws Exception {
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        WebClient client = client(200);

        assertEquals(Set.of(), newThreads(before), "built, the connector starts no thread");

        for (int i = 0; i < 3; i++) {
            assertEquals("ok", client.get().uri("/x").retrieve().bodyToMono(String.class).block(TIMEOUT));
        }
        Set<String> working = newThreads(before);
        assertFalse(working.isEmpty(), "it worked on threads of its own");
        assertTrue(working.stream().allMatch(name -> name.startsWith("rmm-http-")),
                "only threads of the connector, no evictor: " + working);
        assertTrue(working.size() <= 2, "one request at a time needs at most two threads (open, body): " + working);

        for (Thread thread : sentOn) {
            thread.join(TIMEOUT.toMillis());
            assertFalse(thread.isAlive(), thread.getName() + " ended after the idle time");
        }
        assertEquals(Set.of(), newThreads(before), "idle, the connector holds no thread");

        assertEquals("ok", client.get().uri("/x").retrieve().bodyToMono(String.class).block(TIMEOUT),
                "after the idle time it starts threads again");
    }

    @Test
    void requestsOneAfterTheOtherReuseTheTwoThreadsOfTheFirst() throws Exception {
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        WebClient client = client(AdapterHttpConnector.DEFAULT_THREADS, 60_000);

        for (int i = 0; i < 12; i++) {
            assertEquals("ok", client.get().uri("/x").retrieve().bodyToMono(String.class).block(TIMEOUT));
            //One after the other: the thread that read the body is idle again (otherwise it would still count as busy)
            awaitIdle(before);
        }

        Set<String> working = newThreads(before);
        assertEquals(2, working.size(), "12 requests one after the other: one thread opens, one reads the body,"
                + " both taken again for every further request (not one more each up to "
                + AdapterHttpConnector.DEFAULT_THREADS + "): " + working);
        assertTrue(readOn.size() <= 2 && sentOn.stream().distinct().count() <= 2, "sent on " + sentOn
                + ", read on " + readOn);
    }

    @Test
    void closeEndsTheThreadsAtOnce() throws Exception {
        WebClient client = client(60_000);
        assertEquals("ok", client.get().uri("/x").retrieve().bodyToMono(String.class).block(TIMEOUT));
        assertFalse(sentOn.isEmpty());

        connectors.get(0).close();

        for (Thread thread : sentOn) {
            thread.join(5_000);
            assertFalse(thread.isAlive(), thread.getName() + " ended with close, long before its idle time");
        }
    }

    private WebClient client(long idleMillis) {
        return client(2, idleMillis);
    }

    private WebClient client(int threads, long idleMillis) {
        WebClient.Builder builder = WebClient.builder().baseUrl("https://tool.test");
        AdapterHttpConnector connector = new AdapterHttpConnector(new Answering(), threads, idleMillis);
        connectors.add(connector);
        builder.filter(connector.captureRun()).clientConnector(connector);
        return builder.build();
    }

    /** Waits until every new connector thread is waiting, that is idle (none is still finishing). */
    private static void awaitIdle(Set<Thread> before) throws InterruptedException {
        long until = System.nanoTime() + TIMEOUT.toNanos();
        while (Thread.getAllStackTraces().keySet().stream().filter(thread -> !before.contains(thread))
                .filter(thread -> thread.getName().startsWith("rmm-http-"))
                .anyMatch(thread -> thread.isAlive() && thread.getState() != Thread.State.WAITING
                        && thread.getState() != Thread.State.TIMED_WAITING)) {
            assertTrue(System.nanoTime() < until, "the threads of the connector came to rest");
            Thread.sleep(1);
        }
    }

    private static Set<String> newThreads(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream().filter(thread -> !before.contains(thread))
                .filter(Thread::isAlive).map(Thread::getName)
                .filter(name -> name.startsWith("rmm-http-") || name.contains("evictor"))
                .collect(Collectors.toSet());
    }

    /** Answers every request with {@code ok} and records the thread. */
    private final class Answering implements AdapterHttp {

        private final CookieStore cookies = new CookieManager().getCookieStore();

        @Override
        public AdapterResponse send(AdapterRequest request) {
            throw new UnsupportedOperationException("the facade opens");
        }

        @Override
        public StreamResponse open(AdapterRequest request) {
            sentOn.add(Thread.currentThread());
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            return new StreamResponse(200, request.uri(), Map.of("Content-Type", List.of("text/plain")),
                    OptionalLong.of(body.length), new ByteArrayInputStream(body) {
                        @Override
                        public synchronized int read(byte[] into, int offset, int length) {
                            readOn.add(Thread.currentThread());
                            return super.read(into, offset, length);
                        }
                    });
        }

        @Override
        public void auth(URI origin, Auth auth) {
        }

        @Override
        public CookieStore cookies() {
            return cookies;
        }
    }
}
