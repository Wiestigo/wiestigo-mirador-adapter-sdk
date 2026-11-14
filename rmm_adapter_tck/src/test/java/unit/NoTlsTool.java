package unit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A peer without TLS on {@code 127.0.0.1}: it reads the start of the handshake and answers as {@code http},
 * with bytes that are no TLS record, or resets the connection. Counts the connections.
 */
final class NoTlsTool implements AutoCloseable {

    /** What the peer answers to the start of a handshake. */
    enum Answer { PLAIN_HTTP, GARBAGE, RESET }

    private final ServerSocket server;
    private final AtomicInteger connections = new AtomicInteger();
    private final Thread acceptor;

    NoTlsTool(Answer answer) throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    connections.incrementAndGet();
                    socket.getInputStream().read(new byte[64]);
                    OutputStream out = socket.getOutputStream();
                    switch (answer) {
                        case PLAIN_HTTP -> out.write(("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n"
                                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        case GARBAGE -> out.write(new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
                        case RESET -> socket.setSoLinger(true, 0);
                    }
                    out.flush();
                } catch (IOException closedOrBroken) {
                    //closed: the end; broken: on to the next connection
                }
            }
        }, "tck-raw-tool");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    URI httpsUri() {
        return URI.create("https://127.0.0.1:" + server.getLocalPort() + "/");
    }

    int connections() {
        return connections.get();
    }

    @Override
    public void close() throws Exception {
        server.close();
        acceptor.join(TimeUnit.SECONDS.toMillis(10));
    }
}
