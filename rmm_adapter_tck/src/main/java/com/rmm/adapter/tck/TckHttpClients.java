package com.rmm.adapter.tck;

import com.rmm.adapter.api.RedirectPolicy;

import java.net.http.HttpClient;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;

/**
 * The clients of the HTTP path: the TCK's one client for all bindings without TLS material ({@link #shared}) and
 * the clients of the bindings with material ({@link #with}), all on the same threads. No proxy (system properties
 * such as {@code https.proxyHost} do not apply), no redirects of their own (the HTTP path follows them itself, per
 * {@link RedirectPolicy}), HTTP/1.1, TLS 1.3 and 1.2 only, with the host's cipher suites.
 */
final class TckHttpClients {

    /** The prefix of the thread names of the clients; case 4 attributes them to no binding. */
    static final String THREAD_PREFIX = "rmm-tck-http";

    private static final AtomicInteger THREADS = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(
            runnable -> {
                Thread thread = new Thread(runnable, THREAD_PREFIX + "-" + THREADS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
    /** The {@code SelectorManager} threads of the HTTP path's clients, by name. */
    private static final Set<String> SELECTORS = ConcurrentHashMap.newKeySet();

    private static final HttpClient SHARED = with(null);

    private TckHttpClients() {
    }

    /** The one client of all bindings without TLS material, with the JDK's trust (the public authorities). */
    static HttpClient shared() {
        return SHARED;
    }

    /** A client of the HTTP path; {@code context} {@code null}: the JDK's trust (the public authorities). */
    static HttpClient with(SSLContext context) {
        SSLContext tls = context;
        if (tls == null) {
            try {
                tls = SSLContext.getDefault();
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("the JDK has no default TLS context", impossible);
            }
        }
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(HttpClient.Builder.NO_PROXY)
                .connectTimeout(TckAdapterHttp.Settings.CONNECT_TIMEOUT)
                .sslContext(tls)
                .sslParameters(TckTlsMaterial.parameters(tls))
                .executor(EXECUTOR)
                .build();
        String selector = selectorOf(client);
        if (selector != null) {
            SELECTORS.add(selector);
        }
        return client;
    }

    /** Whether a thread with this name is the {@code SelectorManager} thread of one of the clients. */
    static boolean ownsSelector(String name) {
        return SELECTORS.contains(name);
    }

    /** The JDK client states its id at the end of {@code toString()}; its selector thread is named after it. */
    private static String selectorOf(HttpClient client) {
        Matcher id = Pattern.compile("\\((\\d+)\\)$").matcher(client.toString());
        return id.find() ? "HttpClient-" + id.group(1) + "-SelectorManager" : null;
    }
}
