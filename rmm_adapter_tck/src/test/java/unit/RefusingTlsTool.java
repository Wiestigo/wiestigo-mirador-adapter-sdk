package unit;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * A tool over {@code https} on {@code 127.0.0.1} with an {@code SSLServerSocket} that requires a client
 * certificate and trusts only {@code clientTrust}; it refuses any other with the alert of the JDK. Counts
 * the requests after a successful handshake.
 */
final class RefusingTlsTool implements AutoCloseable {

    private final SSLServerSocket server;
    private final AtomicInteger requests = new AtomicInteger();
    private final Thread acceptor;

    RefusingTlsTool(TckTlsFixtures.Pair served, X509Certificate clientTrust) throws Exception {
        server = (SSLServerSocket) TckTlsFixtures.serverContext(served, clientTrust).getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        server.setNeedClientAuth(true);
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (SSLSocket socket = (SSLSocket) server.accept()) {
                    socket.startHandshake();
                    socket.getInputStream().read(new byte[64]);
                    requests.incrementAndGet();
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                } catch (IOException closedOrRefused) {
                    //closed: the end; refused: on to the next connection
                }
            }
        }, "tck-refusing-tls-tool");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    URI uri() {
        return URI.create("https://127.0.0.1:" + server.getLocalPort() + "/");
    }

    int requests() {
        return requests.get();
    }

    @Override
    public void close() throws Exception {
        server.close();
        acceptor.join(TimeUnit.SECONDS.toMillis(10));
    }
}
