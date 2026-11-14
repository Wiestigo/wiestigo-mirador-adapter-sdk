package unit_tests;

import com.io_elements.layer_infrastructure.database_schema.element.ElementArtifactType;
import com.io_elements.layer_infrastructure.database_schema.element.ElementRelationshipType;
import com.io_elements.layer_infrastructure.database_schema.element.field.ElementFieldType;
import com.rmm.adapter.api.AdapterBinding;
import com.rmm.adapter.api.AdapterCaches;
import com.rmm.adapter.api.AdapterConfig;
import com.rmm.adapter.api.AdapterContext;
import com.rmm.adapter.api.AdapterException;
import com.rmm.adapter.api.AdapterExecutor;
import com.rmm.adapter.api.AdapterHttp;
import com.rmm.adapter.api.AdapterInstance;
import com.rmm.adapter.api.AdapterLogger;
import com.rmm.adapter.api.AdapterMetrics;
import com.rmm.adapter.api.AdapterRequest;
import com.rmm.adapter.api.AdapterResponse;
import com.rmm.adapter.api.AdapterStore;
import com.rmm.adapter.api.AdapterStoreLimitException;
import com.rmm.adapter.api.Auth;
import com.rmm.adapter.api.CommentFields;
import com.rmm.adapter.api.DocumentCollection;
import com.rmm.adapter.api.HostLimits;
import com.rmm.adapter.api.MetadataProvider;
import com.rmm.adapter.api.SecretValue;
import com.rmm.adapter.api.SourceReader;
import com.rmm.adapter.api.StreamResponse;
import com.rmm.adapter.api.SyncRunContext;
import com.rmm.adapter.api.TargetWriter;
import com.rmm.adapter.api.TokenProvider;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.CookieStore;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default methods of the context and its services: a context that
 * implements only the abstract methods still compiles; the services behind
 * default methods throw {@link UnsupportedOperationException} (never return
 * {@code null}), and the typed configuration accessors validate on top of
 * {@code string}/{@code integer}.
 */
class TestAdapterContextDefaults {

    /** A context that implements only the abstract methods, none of the default ones. */
    private static final AdapterContext LEGACY_CONTEXT = new AdapterContext() {
        @Override
        public AdapterBinding binding() {
            return new AdapterBinding("b-1");
        }

        @Override
        public AdapterConfig config() {
            return mapConfig(Map.of());
        }

        @Override
        public AdapterLogger logger() {
            return proxy(AdapterLogger.class);
        }

        @Override
        public AdapterStore store() {
            return LEGACY_STORE;
        }

        @Override
        public AdapterMetrics metrics() {
            return proxy(AdapterMetrics.class);
        }
    };

    private static final AdapterStore LEGACY_STORE = new AdapterStore() {
        @Override
        public Optional<String> read(String key) {
            return Optional.empty();
        }

        @Override
        public void write(String key, String value) {
        }

        @Override
        public void delete(String key) {
        }
    };

    /** A configuration that implements only the abstract methods, backed by a map. */
    private static AdapterConfig mapConfig(Map<String, String> values) {
        return new AdapterConfig() {
            @Override
            public String string(String key) {
                String value = values.get(key);
                if (value == null) {
                    throw new IllegalArgumentException("Configuration key <" + key + "> is not declared");
                }
                return value;
            }

            @Override
            public int integer(String key) {
                return Integer.parseInt(string(key).trim());
            }

            @Override
            public boolean bool(String key) {
                return Boolean.parseBoolean(string(key));
            }

            @Override
            public Path path(String key) {
                return Path.of(string(key));
            }
        };
    }

    /** An HTTP path that implements only the abstract methods, none of the default ones. */
    private static AdapterHttp httpWithoutDefaults() {
        return new AdapterHttp() {
            @Override
            public AdapterResponse send(AdapterRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public StreamResponse open(AdapterRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void auth(URI origin, Auth auth) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CookieStore cookies() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** The context a scheduled task receives, with fixed answers. */
    private static AdapterExecutor.TaskContext taskContext(String name, Instant deadline, boolean stopRequested) {
        return new AdapterExecutor.TaskContext() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Instant deadline() {
                return deadline;
            }

            @Override
            public boolean stopRequested() {
                return stopRequested;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        InvocationHandler nothing = (instance, method, args) -> null;
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, nothing);
    }

    @Test
    void theDefaultServicesThrowInsteadOfReturningNull() {
        SyncRunContext run = new SyncRunContext(UUID.randomUUID(), "PRJ-1");

        assertThrows(UnsupportedOperationException.class, LEGACY_CONTEXT::http);
        assertThrows(UnsupportedOperationException.class, LEGACY_CONTEXT::caches);
        assertThrows(UnsupportedOperationException.class, LEGACY_CONTEXT::runStore);
        assertThrows(UnsupportedOperationException.class, () -> LEGACY_CONTEXT.runStore(run));
        assertThrows(UnsupportedOperationException.class, LEGACY_CONTEXT::executor);
        assertThrows(UnsupportedOperationException.class, () -> LEGACY_STORE.collection("items"));
    }

    @Test
    void secretReturnsTheValueMasked() {
        AdapterConfig config = mapConfig(Map.of("api.token", "tok-123", "empty", " "));

        SecretValue secret = config.secret("api.token");
        assertEquals("tok-123", secret.reveal());
        assertFalse(secret.toString().contains("tok-123"), secret.toString());
        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class, () -> config.secret("empty"));
        assertTrue(empty.getMessage().contains("<empty>"), empty.getMessage());
        assertThrows(IllegalArgumentException.class, () -> config.secret("undeclared"));
    }

    @Test
    void urlAcceptsOnlyAbsoluteHttpUrlsWithoutCredentials() {
        AdapterConfig config = mapConfig(Map.of(
                "https", " https://jira.example.com/rest ",
                "container", "http://jira:8080",
                "relative", "/rest/api/2",
                "hostOnly", "jira.example.com",
                "ftp", "ftp://jira.example.com/",
                "credentials", "https://admin:hunter2@jira.example.com/",
                "credentialsContainer", "http://admin:hunter2@jira_server/",
                "brokenWithCredentials", "https://admin:hunter2@jira example.com/",
                "brokenQuery", "https://jira.example.com/?token=hunter2 x",
                "blank", ""));

        assertEquals(URI.create("https://jira.example.com/rest"), config.url("https"));
        assertEquals(URI.create("http://jira:8080"), config.url("container"));
        for (String key : List.of("relative", "hostOnly", "ftp", "credentials", "credentialsContainer",
                "brokenWithCredentials", "brokenQuery", "blank")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> config.url(key),
                    key);
            //Logs print the whole chain of causes: none of them may name the value
            for (Throwable link = failure; link != null; link = link.getCause()) {
                assertFalse(String.valueOf(link.getMessage()).contains("hunter2"), key + ": " + link);
                assertFalse(link.toString().contains("hunter2"), key + ": " + link);
                for (Throwable suppressed : link.getSuppressed()) {
                    assertFalse(suppressed.toString().contains("hunter2"), key + ": " + suppressed);
                }
            }
        }
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class,
                () -> config.url("brokenWithCredentials"));
        //The index depends on the URI parser of the JDK (17: authority, 21: path); only its presence is checked
        assertTrue(broken.getMessage().matches(".*\\bindex \\d+.*"),
                "reason and index instead of the value: " + broken.getMessage());
    }

    /** Container names with an underscore are accepted; a Unicode name comes back in punycode. */
    @Test
    void urlAcceptsContainerNamesAndInternationalNames() {
        AdapterConfig config = mapConfig(Map.of(
                "underscore", "http://jira_server:8080/rest",
                "unicode", "https://bücher.example:8443/a?b=c",
                "punycode", "https://xn--bcher-kva.example/"));

        assertEquals(URI.create("http://jira_server:8080/rest"), config.url("underscore"));
        assertEquals(URI.create("https://xn--bcher-kva.example:8443/a?b=c"), config.url("unicode"));
        assertEquals("xn--bcher-kva.example", config.url("punycode").getHost());
    }

    @Test
    void limitIsNeverNegative() {
        AdapterConfig config = mapConfig(Map.of("threads", "8", "zero", "0", "negative", "-1"));

        assertEquals(8, config.limit("threads"));
        assertEquals(0, config.limit("zero"));
        assertThrows(IllegalArgumentException.class, () -> config.limit("negative"));
    }

    @Test
    void certificateThrowsAndHostIsUnknown() {
        AdapterConfig config = mapConfig(Map.of("ca", "-----BEGIN CERTIFICATE-----"));

        assertThrows(UnsupportedOperationException.class, () -> config.certificate("ca"));
        assertEquals(HostLimits.unknown(), config.host());
        assertTrue(config.host().maxAttachmentBytes().isEmpty());
        assertTrue(config.host().publicUrl().isEmpty());
    }

    @Test
    void hostLimitsValidateTheirValues() {
        HostLimits limits = new HostLimits(OptionalLong.of(1024), Optional.of(URI.create("https://rmm.test")));
        assertEquals(1024, limits.maxAttachmentBytes().getAsLong());

        assertThrows(IllegalArgumentException.class, () -> new HostLimits(OptionalLong.of(0), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new HostLimits(OptionalLong.empty(), Optional.of(URI.create("/relative"))));
        assertThrows(IllegalArgumentException.class, () -> new HostLimits(null, Optional.empty()));
    }

    @Test
    void eventAndCommentFieldsAreEmptyByDefault() throws AdapterException {
        AdapterInstance instance = new AdapterInstance() {
            @Override
            public Optional<SourceReader> reader() {
                return Optional.empty();
            }

            @Override
            public Optional<TargetWriter> writer() {
                return Optional.empty();
            }
        };
        MetadataProvider metadata = new MetadataProvider() {
            @Override
            public List<ElementArtifactType> artifactTypes(String externalProjectId) {
                return List.of();
            }

            @Override
            public List<ElementFieldType> fieldTypes(String externalProjectId) {
                return List.of();
            }

            @Override
            public List<ElementRelationshipType> relationshipTypes(String externalProjectId) {
                return List.of();
            }
        };

        assertTrue(instance.parseEvent(Map.of(), InputStream.nullInputStream()).isEmpty());
        assertTrue(metadata.commentFields().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new CommentFields(" ", null));
        assertEquals("Body", new CommentFields("Body", null).bodyField());
    }

    @Test
    void theStoreLimitNamesTheReasonOfTheRun() {
        AdapterStoreLimitException failure = new AdapterStoreLimitException(
                AdapterStoreLimitException.Limit.DOCUMENT_SIZE, "4 MiB per document");

        assertTrue(failure.getMessage().startsWith(AdapterStoreLimitException.MESSAGE), failure.getMessage());
        assertEquals("adapter store limit reached", AdapterStoreLimitException.MESSAGE);
        assertEquals(AdapterStoreLimitException.Limit.DOCUMENT_SIZE, failure.limit());
        assertTrue(RuntimeException.class.isInstance(failure));
    }

    /**
     * Shapes that cannot change once the contract is frozen: a token is fetched
     * over an HTTP path without authentication, a scheduled task receives its
     * context and returns a handle, a document carries its id, and
     * {@code caches()} offers {@code collection}.
     */
    @Test
    void hooksAndStoresCarryTheirContext() throws Exception {
        AdapterHttp path = httpWithoutDefaults();
        List<AdapterHttp> fetchedOver = new ArrayList<>();
        TokenProvider provider = unauthenticated -> {
            fetchedOver.add(unauthenticated);
            return new TokenProvider.Token(SecretValue.of("t"), null);
        };
        AdapterExecutor.ScheduledTask task = context -> {
            if (context.stopRequested() || context.deadline() == null) {
                throw new IllegalStateException(context.name());
            }
        };
        //Both hooks are called with what they declare: the provider with the HTTP path, the task with its context
        assertEquals("t", provider.fetch(path).accessToken().reveal());
        assertEquals(List.of(path), fetchedOver, "the token is fetched over the HTTP path it was given");
        task.run(taskContext("reload", Instant.EPOCH, false));
        IllegalStateException stopped = assertThrows(IllegalStateException.class,
                () -> task.run(taskContext("reload", Instant.EPOCH, true)));
        assertEquals("reload", stopped.getMessage(), "the task reads the stop request and the name of its context");

        assertEquals(AdapterHttp.class, TokenProvider.class.getMethod("fetch", AdapterHttp.class).getParameterTypes()[0]);
        assertEquals(AdapterExecutor.Scheduled.class, AdapterExecutor.class
                .getMethod("schedule", String.class, Duration.class, AdapterExecutor.ScheduledTask.class).getReturnType());
        assertEquals(DocumentCollection.class, AdapterCaches.class.getMethod("collection", String.class).getReturnType());
        assertThrows(NoSuchMethodException.class, () -> AdapterCaches.class.getMethod("documents", String.class));

        DocumentCollection.Document document = new DocumentCollection.Document("PRJ-1", "{\"secret\":\"x\"}");
        assertFalse(document.toString().contains("secret"), document.toString());
        assertThrows(IllegalArgumentException.class, () -> new DocumentCollection.Document("", "{}"));
        assertEquals(1000, DocumentCollection.MAX_PAGE);
    }

    /**
     * {@code AdapterHttp.currentRun()} is a default method; an HTTP path that
     * does not implement it names no run. The host supplies the bound run.
     */
    @Test
    void theHttpPathNamesNoBoundRunByDefault() throws ReflectiveOperationException {
        AdapterHttp http = httpWithoutDefaults();

        assertEquals(Optional.empty(), http.currentRun());
        assertTrue(AdapterHttp.class.getMethod("currentRun").isDefault());
    }

    /**
     * {@code awaitingOwnRequests()} is a default method; an HTTP path that does
     * not implement it returns the handle {@link AdapterHttp.Awaiting#NOTHING},
     * which does nothing and may be closed any number of times, also from
     * another thread.
     */
    @Test
    void theHandleForOwnRequestsDoesNothingByDefault() throws Exception {
        AdapterHttp http = httpWithoutDefaults();

        AdapterHttp.Awaiting handle = http.awaitingOwnRequests();
        assertSame(AdapterHttp.Awaiting.NOTHING, handle);
        assertSame(AdapterHttp.Awaiting.NOTHING, http.awaitingOwnRequests(), "the same handle every time");
        handle.close();
        handle.close();
        Thread other = new Thread(handle::close);
        other.start();
        other.join();
        try (AdapterHttp.Awaiting nested = http.awaitingOwnRequests()) {
            assertSame(AdapterHttp.Awaiting.NOTHING, nested);
        }
        assertTrue(AdapterHttp.class.getMethod("awaitingOwnRequests").isDefault());
        assertEquals(void.class, AdapterHttp.Awaiting.class.getMethod("close").getReturnType());
        assertEquals(0, AdapterHttp.Awaiting.class.getMethod("close").getExceptionTypes().length,
                "close() throws no checked exception");
    }
}
