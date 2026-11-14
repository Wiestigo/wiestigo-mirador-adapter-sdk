package unit;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reading adapter over HTTP, faithful except for its {@link Defect}: it reads pages through
 * {@code context.http()} in a task from {@code executor().submit}, keeps the labels in {@code caches()} and
 * reloads them when they are missing, and records the identifiers delivered per run in {@code runStore()}.
 */
final class NetTool implements ToolAdapter {

    /** The defects of the network tool, one per case. */
    enum Defect {
        NONE,
        /** Case 2: sends with its own {@code HttpClient} instead of {@code context.http()}. */
        OWN_CLIENT,
        /** Case 2: the type keeps the HTTP path of the first binding. */
        FIRST_HTTP,
        /** Case 4: retries a request that failed at the deadline until someone interrupts it. */
        IGNORES_DEADLINE,
        /** Case 4: remembers in the type that it loaded the labels; after the caches are cleared they are missing. */
        NO_CACHE_RELOAD,
        /** Case 4: keeps the delivered identifiers in a field instead of {@code runStore()}. */
        RUN_STATE_IN_FIELD,
        /** Case 4: reads pages with {@code http().open(...)} and never closes the stream. */
        LEAKS_STREAM,
        /** Pause: the cursor is only the identifier of a search session of the tool. */
        SERVER_SIDE_CURSOR,
        /** Case 7: {@code purge()} deletes the rows of the tool. */
        PURGE_DELETES,
        /** Case 7: {@code purge()} deletes the rows of another space of the tool. */
        PURGE_DELETES_ELSEWHERE,
        /** Case 2 across all cases: {@code purge()} sends with its own {@code HttpClient}. */
        PURGE_OWN_CLIENT,
        /** Case 2 across all cases: {@code bind()} queries the tool once with its own {@code HttpClient}. */
        BIND_OWN_CLIENT,
        /** Case 2: the type keeps the token of the first binding and signs every further binding in with it. */
        FOREIGN_TOKEN,
        /**
         * Case 2, like {@link #FOREIGN_TOKEN} but with Basic in ISO-8859-1 and a password containing an
         * umlaut: the header carries only the Base64 form, never the password itself.
         */
        FOREIGN_BASIC_LATIN,
        /** Like {@link #FOREIGN_BASIC_LATIN} but faithful: every binding signs in with its own password. */
        OWN_BASIC_LATIN
    }

    private final Defect defect;
    /** Receives the client of the "own client" defects, so that the test holds it until the case has counted. */
    private final List<HttpClient> clients;
    private volatile AdapterHttp firstHttp;
    private volatile boolean labelsLoaded;
    private final Set<String> delivered = ConcurrentHashMap.newKeySet();
    private volatile HttpClient ownClient;
    private volatile String firstToken;

    NetTool(Defect defect, List<HttpClient> clients) {
        this.defect = defect;
        this.clients = clients;
    }

    @Override
    public AdapterDescriptor descriptor() {
        return new AdapterDescriptor("NETTOOL", "Net Tool", "1.0.0", AdapterApiVersion.MAJOR,
                new AdapterCapabilities(Set.of(SyncDirection.TO_RMM), HierarchyModel.FLAT,
                        false, false, false, false, SyncPhase.artifactsOnly(), Set.of(), Set.of(), null),
                List.of(ConfigKey.required("tool.url", ConfigType.URL, "The address of the tool"),
                        ConfigKey.optional("page.size", ConfigType.INTEGER, "2", "Rows per page"),
                        ConfigKey.optional("tool.token", ConfigType.SECRET, "", "Access token")),
                List.of());
    }

    @Override
    public AdapterInstance bind(AdapterContext context) {
        URI base = context.config().url("tool.url");
        int pageSize = context.config().limit("page.size");
        AdapterHttp http = context.http();
        if (defect == Defect.FIRST_HTTP) {
            if (firstHttp == null) {
                firstHttp = http;
            }
            http = firstHttp;
        }
        String token = context.config().string("tool.token");
        if (defect == Defect.FOREIGN_TOKEN || defect == Defect.FOREIGN_BASIC_LATIN) {
            if (firstToken == null) {
                firstToken = token;
            }
            token = firstToken;
        }
        if (defect == Defect.FOREIGN_BASIC_LATIN || defect == Defect.OWN_BASIC_LATIN) {
            //Basic in ISO-8859-1, as some tools expect it; the password has an umlaut so that the charset matters
            http.auth(URI.create(base.getScheme() + "://" + base.getRawAuthority()),
                    Auth.basic("account", SecretValue.of(token + "-ä"), StandardCharsets.ISO_8859_1));
        } else if (!token.isBlank()) {
            //Faithful: the sign-in of its own connection, for its origin
            http.auth(URI.create(base.getScheme() + "://" + base.getRawAuthority()),
                    Auth.bearer(SecretValue.of(token)));
        }
        NetReader reader = new NetReader(context, http, base, pageSize);
        if (defect == Defect.BIND_OWN_CLIENT) {
            //The defect: a probe while binding that bypasses the host's HTTP path; it ignores the answer 403
            try {
                reader.ownClient(URI.create(base + "/labels"));
            } catch (AdapterException ignored) {
                //ignored
            }
        }
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
                reader.page("/labels");
            }

            @Override
            public void purge() throws AdapterException {
                if (defect == Defect.PURGE_OWN_CLIENT) {
                    //The defect: bypasses the host's HTTP path; it ignores the answer 403
                    try {
                        reader.ownClient(URI.create(base + "/hooks/" + context.binding().bindingId()));
                    } catch (AdapterException ignored) {
                        //ignored
                    }
                    return;
                }
                if (defect == Defect.PURGE_DELETES_ELSEWHERE) {
                    //The defect: the rows of another space of the same tool
                    reader.send(AdapterRequest.delete(base.resolve("/second/rows")));
                    return;
                }
                //Faithful: only its own webhook registration at the tool
                reader.delete(defect == Defect.PURGE_DELETES ? "/rows" : "/hooks/" + context.binding().bindingId());
            }
        };
    }

    /** A page of the tool: lines and headers. */
    private record Page(List<String> lines, Map<String, List<String>> headers) {
        String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().get(0)).findFirst().orElse(null);
        }
    }

    /** The reader of one binding; the defects that concern reading are those of its tool. */
    private final class NetReader implements SourceReader {

        private final AdapterContext context;
        private final AdapterHttp http;
        private final URI base;
        private final int pageSize;

        private NetReader(AdapterContext context, AdapterHttp http, URI base, int pageSize) {
            this.context = context;
            this.http = http;
            this.base = base;
            this.pageSize = pageSize;
        }

        @Override
        public SourcePage<ElementArtifact> readArtifacts(SyncRunContext run, ReadScope scope, PageCursor cursor)
                throws AdapterException {
            List<String> ids;
            String next;
            if (defect == Defect.SERVER_SIDE_CURSOR) {
                //The cursor is the session of the tool (the page number after it only makes it unique):
                //only the tool knows where to continue
                String session = cursor == null ? null : cursor.value().substring(0, cursor.value().indexOf('#'));
                int number = cursor == null ? 1 : Integer.parseInt(cursor.value().substring(
                        cursor.value().indexOf('#') + 1)) + 1;
                Page page = page(session == null ? "/search?limit=" + pageSize
                        : "/search/next?session=" + session + "&limit=" + pageSize);
                ids = page.lines();
                next = page.header("X-Session") == null ? null : page.header("X-Session") + "#" + number;
            } else {
                int offset = cursor == null ? 0 : Integer.parseInt(cursor.value());
                Page page = submitted(() -> page("/rows?offset=" + offset + "&limit=" + pageSize));
                ids = page.lines();
                int total = Integer.parseInt(page.header("X-Total"));
                next = offset + ids.size() < total ? String.valueOf(offset + ids.size()) : null;
            }
            Map<String, String> labels = labels();
            List<ElementArtifact> artifacts = new ArrayList<>();
            for (String id : ids) {
                if (!firstDelivery(id)) {
                    continue;
                }
                ElementArtifact artifact = new ElementArtifact();
                artifact.setId(new ElementId(id));
                artifact.addField(new ElementFieldString(new ElementName("label"), new ElementLabel("Label"),
                        String.valueOf(labels.get(id))));
                artifacts.add(artifact);
            }
            return new SourcePage<>(artifacts, List.of(), ids.size(), next == null ? null : new PageCursor(next),
                    next == null);
        }

        /** Every identifier once per run; a faithful adapter keeps this in the run store. */
        private boolean firstDelivery(String id) {
            if (defect == Defect.RUN_STATE_IN_FIELD) {
                return delivered.add(id);
            }
            DocumentCollection seen = context.runStore().collection("delivered");
            if (seen.get(id).isPresent()) {
                return false;
            }
            seen.put(id, "{\"seen\":true}");
            return true;
        }

        /** The labels from {@code caches()}; if they are missing, the adapter reloads them from the tool. */
        private Map<String, String> labels() throws AdapterException {
            DocumentCollection cache = context.caches().collection("labels");
            Optional<String> known = cache.get("all");
            if (known.isEmpty() && !(defect == Defect.NO_CACHE_RELOAD && labelsLoaded)) {
                StringBuilder json = new StringBuilder("{");
                for (String line : page("/labels").lines()) {
                    String[] pair = line.split("=", 2);
                    json.append(json.length() > 1 ? "," : "").append('"').append(pair[0]).append("\":\"")
                            .append(pair[1]).append('"');
                }
                cache.put("all", json.append('}').toString());
                labelsLoaded = true;
                known = cache.get("all");
            }
            Map<String, String> labels = new HashMap<>();
            //Parsed as JSON, not compared as text: a store may return a document formatted differently
            Matcher pair = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").matcher(known.orElse("{}"));
            while (pair.find()) {
                labels.put(pair.group(1), pair.group(2));
            }
            return labels;
        }

        /** Runs the work in a task from {@code executor().submit} and waits for it through the future. */
        private <T> T submitted(Callable<T> work) throws AdapterException {
            try {
                return context.executor().submit(work).get();
            } catch (ExecutionException failed) {
                if (failed.getCause() instanceof AdapterException adapterFailure) {
                    throw adapterFailure;
                }
                throw new AdapterException("reading failed: " + failed.getCause(), failed.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AdapterException("interrupted", interrupted);
            }
        }

        private Page page(String path) throws AdapterException {
            URI uri = URI.create(base + path);
            if (defect == Defect.OWN_CLIENT) {
                return ownClient(uri);
            }
            if (defect == Defect.LEAKS_STREAM) {
                StreamResponse response = http.open(AdapterRequest.get(uri));
                try {
                    //Read to the end, but never closed
                    return new Page(lines(new String(response.body().readAllBytes(), StandardCharsets.UTF_8)),
                            response.headers());
                } catch (IOException failed) {
                    throw new AdapterException("reading failed: " + failed, failed);
                }
            }
            AdapterResponse response = send(AdapterRequest.get(uri));
            if (!response.successful()) {
                throw new AdapterException("the tool answered " + response.status(), null, false);
            }
            return new Page(lines(response.bodyAsString()), response.headers());
        }

        private void delete(String path) throws AdapterException {
            send(AdapterRequest.delete(URI.create(base + path)));
        }

        private AdapterResponse send(AdapterRequest request) throws AdapterException {
            while (true) {
                try {
                    return http.send(request);
                } catch (AdapterException failed) {
                    if (defect != Defect.IGNORES_DEADLINE) {
                        throw failed;
                    }
                    //The defect: once more, without looking at the deadline, until someone interrupts it
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw failed;
                    }
                }
            }
        }

        /** The defect OWN_CLIENT: its own client, bypassing the host's HTTP path. */
        private Page ownClient(URI uri) throws AdapterException {
            if (ownClient == null) {
                ownClient = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
                clients.add(ownClient);
            }
            try {
                HttpResponse<String> response = ownClient.send(HttpRequest.newBuilder(uri).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new AdapterException("the tool answered " + response.statusCode(), null, false);
                }
                return new Page(lines(response.body()), response.headers().map());
            } catch (IOException failed) {
                throw new AdapterException("reading failed: " + failed, failed);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AdapterException("interrupted", interrupted);
            }
        }
    }

    private static List<String> lines(String body) {
        return body.isEmpty() ? List.of() : List.of(body.split("\n"));
    }
}
