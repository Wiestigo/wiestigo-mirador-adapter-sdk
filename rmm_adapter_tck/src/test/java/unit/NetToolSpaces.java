package unit;

import com.rmm.adapter.tck.FakeHttpTool;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The server side of the network tool that {@link NetTool} reads: one data set per space, pages via
 * {@code /rows?offset&limit} (total in {@code X-Total}), labels via {@code /labels} and search sessions via
 * {@code /search}. {@code DELETE .../rows} empties a space.
 */
final class NetToolSpaces {

    /** One data set per space of the network tool; {@code DELETE .../rows} empties it. */
    private final Map<String, List<String>> rowsBySpace = new ConcurrentHashMap<>();
    /** The search sessions of the network tool: identifier to the rows not yet delivered. */
    private final Map<String, List<String>> sessions = new ConcurrentHashMap<>();

    /** Fills the space {@code space} with {@code rows} and answers its requests under {@code /<space>/}. */
    void serve(FakeHttpTool tool, String space, List<String> rows) {
        rowsBySpace.put(space, new CopyOnWriteArrayList<>(rows));
        tool.route("/" + space + "/", exchange -> answer(space, exchange));
    }

    /** Forgets every search session, as a tool does after a long pause. */
    void expireSessions() {
        sessions.clear();
    }

    private void answer(String space, HttpExchange exchange) throws IOException {
        List<String> rows = rowsBySpace.get(space);
        String path = exchange.getRequestURI().getPath().substring(space.length() + 1);
        Map<String, String> query = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        for (String pair : raw == null ? new String[0] : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            query.put(parts[0], parts.length > 1 ? parts[1] : "");
        }
        int limit = Integer.parseInt(query.getOrDefault("limit", "1000"));
        String method = exchange.getRequestMethod();
        if (method.equals("DELETE")) {
            if (path.equals("/rows")) {
                rows.clear();
            }
            FakeHttpTool.respond(exchange, 204, null, new byte[0]);
        } else if (path.equals("/rows")) {
            int offset = Math.min(Integer.parseInt(query.getOrDefault("offset", "0")), rows.size());
            exchange.getResponseHeaders().set("X-Total", String.valueOf(rows.size()));
            respondLines(exchange, rows.subList(offset, Math.min(offset + limit, rows.size())));
        } else if (path.equals("/labels")) {
            respondLines(exchange, rows.stream().map(row -> row + "=Label " + row).toList());
        } else if (path.equals("/search") || path.equals("/search/next")) {
            String session = path.equals("/search") ? "s-" + UUID.randomUUID() : query.get("session");
            if (path.equals("/search")) {
                sessions.put(session, new CopyOnWriteArrayList<>(rows));
            }
            List<String> remaining = sessions.get(session);
            if (remaining == null) {
                FakeHttpTool.respond(exchange, 410, "text/plain", "search session expired".getBytes(StandardCharsets.UTF_8));
                return;
            }
            List<String> page = new ArrayList<>(remaining.subList(0, Math.min(limit, remaining.size())));
            remaining.removeAll(page);
            if (!remaining.isEmpty()) {
                exchange.getResponseHeaders().set("X-Session", session);
            }
            respondLines(exchange, page);
        } else {
            FakeHttpTool.respond(exchange, 404, "text/plain", new byte[0]);
        }
    }

    private static void respondLines(HttpExchange exchange, List<String> lines) throws IOException {
        FakeHttpTool.respond(exchange, 200, "text/plain; charset=utf-8",
                String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }
}
