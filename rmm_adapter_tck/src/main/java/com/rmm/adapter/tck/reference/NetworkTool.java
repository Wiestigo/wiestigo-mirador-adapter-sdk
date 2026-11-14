package com.rmm.adapter.tck.reference;

import com.rmm.adapter.tck.FakeHttpTool;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The fake tool of the {@link NetworkToolAdapter}: a {@link FakeHttpTool}
 * with two spaces ({@value #FIRST} with five rows, {@value #SECOND} with
 * three), each with its own token. It answers only requests sent through
 * {@code context.http()}.
 *
 * <ul>
 *   <li>{@code GET /<space>/rows?offset&limit}: the row ids, one per line,
 *       and the total in {@code X-Total}; 400 if {@code offset} or
 *       {@code limit} is not a whole number from 0;</li>
 *   <li>{@code GET /<space>/labels}: {@code <id>=<label>} per line; a label
 *       contains quotation marks and a backslash, as a tool may deliver
 *       them ({@link #label});</li>
 *   <li>{@code DELETE /<space>/hooks/<id>}: unregisters a webhook (204);</li>
 *   <li>{@code DELETE /<space>/rows}: empties the space, which
 *       {@code purge()} must never do (case 7).</li>
 * </ul>
 * <p>Without {@code Authorization: Bearer <token of the space>} a space
 * answers every request with 401, including unregistering a webhook. The
 * tool keeps no state between two requests that could expire (no search
 * session).</p>
 */
public final class NetworkTool implements AutoCloseable {

    /** The space of the first binding. */
    public static final String FIRST = "first";
    /** The space of the second binding, with a different data set. */
    public static final String SECOND = "second";

    private final FakeHttpTool http;
    private final Map<String, List<String>> rows = new ConcurrentHashMap<>();
    private final Map<String, String> tokens = Map.of(FIRST, "token-first-1234", SECOND, "token-second-5678");
    private final Set<String> removedHooks = ConcurrentHashMap.newKeySet();

    private NetworkTool(FakeHttpTool http) {
        rows.put(FIRST, new CopyOnWriteArrayList<>(List.of("F-1", "F-2", "F-3", "F-4", "F-5")));
        rows.put(SECOND, new CopyOnWriteArrayList<>(List.of("S-1", "S-2", "S-3")));
        this.http = http;
        for (String space : rows.keySet()) {
            http.route("/" + space + "/", exchange -> answer(space, exchange));
        }
    }

    /** Starts the tool on a free port of {@code 127.0.0.1}. */
    public static NetworkTool start() throws IOException {
        return new NetworkTool(FakeHttpTool.start());
    }

    /**
     * Like {@link #start()}, over https with a required client certificate
     * ({@link FakeHttpTool#startHttps}): for an adapter that authenticates
     * with the client identity of its connection.
     */
    public static NetworkTool startHttps(String toolIdentityPem, String trustedClientsPem) throws IOException {
        return new NetworkTool(FakeHttpTool.startHttps(toolIdentityPem, trustedClientsPem));
    }

    /** The underlying {@link FakeHttpTool}, for {@code networkTool()} of the TCK. */
    public FakeHttpTool http() {
        return http;
    }

    /** The address of a space ({@code tool.url}). */
    public URI address(String space) {
        return http.uri("/" + space);
    }

    /** The token of a space ({@code tool.token}). */
    public String token(String space) {
        return tokens.get(space);
    }

    /** The current row ids of a space. */
    public List<String> rows(String space) {
        return List.copyOf(rows.get(space));
    }

    /** The unregistered webhooks, as {@code <space>/<id>}. */
    public Set<String> removedHooks() {
        return Set.copyOf(removedHooks);
    }

    @Override
    public void close() {
        http.close();
    }

    @Override
    public String toString() {
        return "NetworkTool[" + http + "]";
    }

    private void answer(String space, HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring(space.length() + 1);
        String method = exchange.getRequestMethod();
        if (!("Bearer " + tokens.get(space)).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            FakeHttpTool.respond(exchange, 401, "text/plain", bytes("sign in with the token of this space"));
            return;
        }
        List<String> stored = rows.get(space);
        if (method.equals("DELETE") && path.startsWith("/hooks/")) {
            removedHooks.add(space + path.substring("/hooks".length()));
            FakeHttpTool.respond(exchange, 204, null, new byte[0]);
        } else if (method.equals("DELETE") && path.equals("/rows")) {
            stored.clear();
            FakeHttpTool.respond(exchange, 204, null, new byte[0]);
        } else if (method.equals("GET") && path.equals("/rows")) {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            int requestedOffset = count(query.getOrDefault("offset", "0"));
            int limit = count(query.getOrDefault("limit", "1000"));
            if (requestedOffset < 0 || limit < 0) {
                FakeHttpTool.respond(exchange, 400, "text/plain", bytes("offset and limit are whole numbers from 0"));
                return;
            }
            int offset = Math.min(requestedOffset, stored.size());
            exchange.getResponseHeaders().set("X-Total", String.valueOf(stored.size()));
            lines(exchange, stored.subList(offset, (int) Math.min((long) offset + limit, stored.size())));
        } else if (method.equals("GET") && path.equals("/labels")) {
            lines(exchange, stored.stream().map(row -> row + "=" + label(row)).toList());
        } else {
            FakeHttpTool.respond(exchange, 404, "text/plain", new byte[0]);
        }
    }

    /** The label of a row as {@code /labels} delivers it. */
    public static String label(String row) {
        return "Label \"" + row + "\" \\ part";
    }

    /** A query value as a whole number from 0; -1 if it is none. */
    private static int count(String value) {
        try {
            return Math.max(-1, Integer.parseInt(value));
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new HashMap<>();
        for (String pair : raw == null ? new String[0] : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            query.put(parts[0], parts.length > 1 ? parts[1] : "");
        }
        return query;
    }

    private static void lines(HttpExchange exchange, List<String> lines) throws IOException {
        FakeHttpTool.respond(exchange, 200, "text/plain; charset=utf-8", bytes(String.join("\n", lines)));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
