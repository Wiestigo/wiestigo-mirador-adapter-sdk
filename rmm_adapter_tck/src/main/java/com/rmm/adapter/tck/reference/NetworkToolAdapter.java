package com.rmm.adapter.tck.reference;

import com.io_elements.layer_infrastructure.ElementId;
import com.io_elements.layer_infrastructure.ElementLabel;
import com.io_elements.layer_infrastructure.ElementName;
import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifact;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldString;
import com.rmm.adapter.api.AdapterApiVersion;
import com.rmm.adapter.api.AdapterCapabilities;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterDescriptor;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.Auth;
import com.rmm.adapter.api.ConfigKey;
import com.rmm.adapter.api.ConfigType;
import com.rmm.adapter.api.DocumentCollection;
import com.rmm.adapter.api.HierarchyModel;
import com.rmm.adapter.api.PageCursor;
import com.rmm.adapter.api.ReadScope;
import com.rmm.adapter.api.SecretValue;
import com.rmm.adapter.api.SourcePage;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.StreamResponse;
import com.rmm.adapter.api.SyncDirection;
import com.rmm.adapter.api.SyncPhase;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.ToolAdapter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reference implementation of a read-only adapter over the network. It uses
 * what the contract gives an adapter the way the template
 * ({@code rmm_adapter_template}) recommends, against the {@link NetworkTool}:
 *
 * <ul>
 *   <li>every request goes through {@code context.http()}, authenticated
 *       with the connection's token ({@code tool.token}) for its origin;</li>
 *   <li>every page is read in a task from {@code executor().submit} and
 *       awaited through its {@code Future};</li>
 *   <li>the labels live in {@code caches().collection("labels")}; if they
 *       are missing (first bind, after the caches were cleared), the adapter
 *       reloads them as a stream from {@code http().open(...)}, which it
 *       closes on every path;</li>
 *   <li>per run, the pages read (offset and row count) are noted in
 *       {@code runStore()}. Nothing is filtered by them: a reader delivers
 *       what the tool has, also when the same page is read twice in one
 *       run;</li>
 *   <li>the cursor is an offset and survives any pause;</li>
 *   <li>{@code purge()} unregisters only the connection's own webhook.</li>
 * </ul>
 *
 * <p>Like {@link InMemoryToolAdapter} it serves two purposes: the TCK tests
 * itself with it, and a host can run the TCK cases with it against its real
 * context (HTTP path, stores, runs and threads).</p>
 */
public final class NetworkToolAdapter implements ToolAdapter {

    /** A pair {@code "id":"label"} in the cached JSON, with escapes in both strings. */
    private static final Pattern LABEL = Pattern.compile(
            "\"((?:[^\"\\\\]|\\\\.)+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Override
    public AdapterDescriptor descriptor() {
        return new AdapterDescriptor("NETWORKTOOL", "Network Reference Tool", "1.0.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
                        false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null),
                List.of(ConfigKey.required("tool.url", ConfigType.URL, "The address of the space in the tool"),
                        ConfigKey.optional("tool.token", ConfigType.SECRET, "", "The token of the space"),
                        ConfigKey.optional("page.size", ConfigType.INTEGER, "2",
                                "Rows per page — small, so that tests see several pages")),
                List.of());
    }

    @Override
    public AdapterInstance bind(AdapterContext context) {
        URI base = context.config().url("tool.url");
        int pageSize = Math.max(1, context.config().limit("page.size"));
        AdapterHttp http = context.http();
        String token = context.config().string("tool.token");
        if (!token.isBlank()) {
            //Credentials of this connection, registered for its origin only
            http.auth(URI.create(base.getScheme() + "://" + base.getRawAuthority()),
                    Auth.bearer(SecretValue.of(token)));
        }
        Reader reader = new Reader(context, http, base, pageSize);
        return new AdapterInstance() {
            @Override
            public Optional<SourceReader> reader() {
                return Optional.of(reader);
            }

            @Override
            public Optional<TargetWriter> writer() {
                return Optional.empty();
            }

            @Override
            public void checkHealth() throws AdapterException {
                reader.lines("/labels");
            }

            /** Removes only the connection's own webhook, never rows of the tool (case 7). */
            @Override
            public void purge() throws AdapterException {
                reader.send(AdapterRequest.delete(URI.create(base + "/hooks/" + context.binding().bindingId())));
            }
        };
    }

    /** Reads the rows of a space. */
    private static final class Reader implements SourceReader {

        private final AdapterContext context;
        private final AdapterHttp http;
        private final URI base;
        private final int pageSize;

        private Reader(AdapterContext context, AdapterHttp http, URI base, int pageSize) {
            this.context = context;
            this.http = http;
            this.base = base;
            this.pageSize = pageSize;
        }

        /**
         * The offset a cursor of this reader carries. A cursor that is damaged or follows another scheme
         * fails the read like any other failure, never with an unchecked exception.
         */
        private static int offsetOf(PageCursor cursor) throws AdapterException {
            try {
                int offset = Integer.parseInt(cursor.value());
                if (offset >= 0) {
                    return offset;
                }
            } catch (NumberFormatException notANumber) {
                //Reported below, like a negative offset
            }
            throw new AdapterException("The cursor <" + cursor.value() + "> is not an offset of this tool");
        }

        @Override
        public SourcePage<ElementArtifact> readArtifacts(SyncRunContext run, ReadScope scope, PageCursor cursor)
                throws AdapterException {
            int offset = cursor == null ? 0 : offsetOf(cursor);
            Page page = submitted(() -> page("/rows?offset=" + offset + "&limit=" + pageSize));
            int total = page.total();
            int next = offset + page.ids().size();
            Map<String, String> labels = labels();
            //What this run has read; reading the same page again only overwrites the entry
            context.runStore().collection("pages").put(String.valueOf(offset),
                    "{\"rows\":" + page.ids().size() + ",\"total\":" + total + "}");
            List<ElementArtifact> artifacts = new ArrayList<>();
            for (String id : page.ids()) {
                if (scope.isExplicit() && !scope.elementIds().contains(id)) {
                    continue;
                }
                ElementArtifact artifact = new ElementArtifact();
                artifact.setId(new ElementId(id));
                artifact.addField(new ElementFieldString(new ElementName("label"), new ElementLabel("Label"),
                        String.valueOf(labels.get(id))));
                artifacts.add(artifact);
            }
            boolean last = next >= total || page.ids().isEmpty();
            return new SourcePage<>(artifacts, List.of(), page.ids().size(),
                    last ? null : new PageCursor(String.valueOf(next)), last);
        }

        /** The labels from {@code caches()}; reloaded from the tool if they are missing. */
        private Map<String, String> labels() throws AdapterException {
            DocumentCollection cache = context.caches().collection("labels");
            Optional<String> known = cache.get("all");
            if (known.isEmpty()) {
                StringBuilder json = new StringBuilder("{");
                for (String line : streamedLines("/labels")) {
                    String[] pair = line.split("=", 2);
                    json.append(json.length() > 1 ? "," : "").append(quoted(pair[0])).append(':')
                            .append(quoted(pair.length > 1 ? pair[1] : ""));
                }
                String loaded = json.append('}').toString();
                cache.put("all", loaded);
                //At a limit the cache does not save: use what was just loaded
                known = Optional.of(cache.get("all").orElse(loaded));
            }
            Map<String, String> labels = new HashMap<>();
            //Parse the JSON, do not compare the written text: the store returns it as MongoDB reads it
            Matcher pair = LABEL.matcher(known.get());
            while (pair.find()) {
                labels.put(unquoted(pair.group(1)), unquoted(pair.group(2)));
            }
            return labels;
        }

        /**
         * The lines of a response, read as a stream ({@code http().open}).
         * The stream is closed on every path; otherwise it would hold one of
         * the binding's connections in the host until the run's deadline.
         */
        private List<String> streamedLines(String path) throws AdapterException {
            try (StreamResponse response = http.open(AdapterRequest.get(URI.create(base + path)))) {
                if (!response.successful()) {
                    throw refused(response.status());
                }
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                return body.isEmpty() ? List.of() : List.of(body.split("\n"));
            } catch (IOException failed) {
                throw new AdapterException("Reading " + path + " failed: " + failed.getMessage(), failed, true);
            }
        }

        /** Runs the work in a task from {@code executor().submit} and waits for its future. */
        private <T> T submitted(Callable<T> work) throws AdapterException {
            try {
                return context.executor().submit(work).get();
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof AdapterException adapterFailure) {
                    throw adapterFailure;
                }
                throw new AdapterException("Reading a page failed: " + failed.getCause(), failed.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AdapterException("Reading a page was interrupted", interrupted);
            }
        }

        private Page page(String path) throws AdapterException {
            AdapterResponse response = require(send(AdapterRequest.get(URI.create(base + path))));
            return new Page(lines(response), totalOf(response.header("X-Total").orElse("0")));
        }

        /** The total of the rows as the tool states it in {@code X-Total}: a whole number from 0. */
        private static int totalOf(String header) throws AdapterException {
            try {
                int total = Integer.parseInt(header.trim());
                if (total >= 0) {
                    return total;
                }
            } catch (NumberFormatException notANumber) {
                //Reported below, like a negative total
            }
            throw new AdapterException("The tool answered with a total that is not a count: X-Total <" + header
                    + ">");
        }

        private List<String> lines(String path) throws AdapterException {
            return lines(require(send(AdapterRequest.get(URI.create(base + path)))));
        }

        private AdapterResponse send(AdapterRequest request) throws AdapterException {
            return http.send(request);
        }

        private static AdapterResponse require(AdapterResponse response) throws AdapterException {
            if (!response.successful()) {
                throw refused(response.status());
            }
            return response;
        }

        /** The failure for a status that is no success, whether the body was read at once or as a stream. */
        private static AdapterException refused(int status) {
            //Overloaded or failing (429, 5xx): transient; everything else is permanent
            boolean transientFailure = status == 429 || status >= 500;
            return new AdapterException("The tool answered " + status, null, transientFailure, status);
        }

        private static List<String> lines(AdapterResponse response) {
            String body = response.bodyAsString();
            return body.isEmpty() ? List.of() : List.of(body.split("\n"));
        }
    }

    /** {@code text} as a JSON string, with quotation marks, backslashes and control characters escaped. */
    static String quoted(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    /**
     * The content of a JSON string without its quotation marks, with escapes resolved.
     *
     * @throws AdapterException if a Unicode escape does not carry exactly four hexadecimal digits
     */
    static String unquoted(String json) throws AdapterException {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c != '\\' || i + 1 >= json.length()) {
                text.append(c);
                continue;
            }
            char escaped = json.charAt(++i);
            switch (escaped) {
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 'u' -> {
                    int codeUnit = fourHexDigits(json, i + 1);
                    if (codeUnit < 0) {
                        throw new AdapterException("Not a JSON string: \\u needs four hexadecimal digits (at index "
                                + (i - 1) + ")");
                    }
                    text.append((char) codeUnit);
                    i += 4;
                }
                default -> text.append(escaped);
            }
        }
        return text.toString();
    }

    /**
     * The value of the four hexadecimal digits ({@code 0-9}, {@code a-f}, {@code A-F}) that start at
     * {@code start}; -1 if there are not four. Stricter than {@code Integer.parseInt(text, 16)}, which also
     * takes a sign and the digits of other scripts.
     */
    private static int fourHexDigits(String text, int start) {
        if (start + 4 > text.length()) {
            return -1;
        }
        int value = 0;
        for (int index = start; index < start + 4; index++) {
            char c = text.charAt(index);
            int digit = c >= '0' && c <= '9' ? c - '0'
                    : c >= 'a' && c <= 'f' ? c - 'a' + 10
                    : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (digit < 0) {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
    }

    /** One page of the tool: the row ids and the total. */
    private record Page(List<String> ids, int total) {
    }
}
