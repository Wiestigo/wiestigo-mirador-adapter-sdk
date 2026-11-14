package unit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;

/**
 * A tool over {@code https} (the server of the JDK) on {@code 127.0.0.1} that presents one certificate and,
 * if asked to, requires a client certificate; counts the requests and keeps the last client certificate.
 */
final class TlsTool implements AutoCloseable {

    /** The status of the answer to a request. */
    interface Status {
        int of(HttpExchange exchange) throws Exception;
    }

    private final HttpsServer server;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final AtomicInteger requests = new AtomicInteger();
    private volatile X509Certificate lastClient;

    /**
     * Presents {@code served} (with its chain); with {@code clientTrust} the tool requires a client
     * certificate and trusts only that one. {@code status} chooses the status of every answer.
     */
    TlsTool(TckTlsFixtures.Pair served, X509Certificate clientTrust, Status status) throws Exception {
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(TckTlsFixtures.serverContext(served, clientTrust)) {
            @Override
            public void configure(HttpsParameters parameters) {
                SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setNeedClientAuth(clientTrust != null);
                parameters.setSSLParameters(ssl);
            }
        });
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try {
                lastClient = (X509Certificate) ((HttpsExchange) exchange).getSSLSession()
                        .getPeerCertificates()[0];
            } catch (SSLPeerUnverifiedException none) {
                lastClient = null;
            }
            exchange.getRequestBody().readAllBytes();
            int code;
            try {
                code = status.of(exchange);
            } catch (Exception failed) {
                code = 500;
            }
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    URI uri() {
        return URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    int requests() {
        return requests.get();
    }

    X509Certificate lastClient() {
        return lastClient;
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}
