package com.rmm.adapter.tck;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tools on a raw socket for the checks of early and aborted responses in {@link AdapterHttpSemantics}: starts a
 * {@link AdapterHttpSemantics.RawTool} for one connection and holds the tools those checks use.
 */
final class RawTools {

    private RawTools() {
    }

    /**
     * Starts {@code tool} on a free port of {@code 127.0.0.1}. It gets the first connection once the head of the
     * request has arrived; the connection is closed when the tool returns.
     */
    static ServerSocket start(AdapterHttpSemantics.RawTool tool) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread serving = new Thread(() -> {
            try (Socket socket = server.accept()) {
                InputStream in = socket.getInputStream();
                StringBuilder head = new StringBuilder();
                while (!head.toString().endsWith("\r\n\r\n")) {
                    int next = in.read();
                    if (next < 0) {
                        return;
                    }
                    head.append((char) next);
                }
                tool.serve(socket, head.toString());
            } catch (Exception ignored) {
                //The tool ends with the test
            }
        }, "raw-tool");
        serving.setDaemon(true);
        serving.start();
        return server;
    }

    /** Sends {@code text} to the peer at once. */
    static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    /** Announces a body of ten bytes, sends three of them and ends the response. */
    static AdapterHttpSemantics.RawTool endingBeforeItsLength() {
        return (socket, head) -> {
            write(socket, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 10\r\n\r\nabc");
            socket.shutdownOutput();
            Thread.sleep(200);
        };
    }

    /**
     * Reads part of an upload, then begins to answer: the headers with {@code framing} for the length of the
     * body, then {@code partial}. After that {@code answered} is counted down. The rest of the response never
     * comes; the tool keeps reading whatever arrives until {@code done}.
     */
    static AdapterHttpSemantics.RawTool answeringAnUploadPartly(String framing, String partial,
                                                                CountDownLatch answered, CountDownLatch done) {
        return (socket, head) -> {
            byte[] some = new byte[64 * 1024];
            socket.getInputStream().readNBytes(some, 0, some.length);
            write(socket, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" + framing + "\r\n" + partial);
            answered.countDown();
            byte[] rest = new byte[64 * 1024];
            while (done.getCount() > 0 && socket.getInputStream().read(rest) >= 0) {
                //keep reading
            }
        };
    }

    /**
     * Answers an upload with 413 as soon as the first byte of its body has arrived, then stops reading and keeps
     * the connection open until {@code done}. With the first byte the source of the upload is certainly open; if
     * the response came first, the HTTP path would be free not to open the source at all, and there would be
     * nothing to close (this happens on slow machines).
     */
    static AdapterHttpSemantics.RawTool refusingAnUploadEarly(CountDownLatch done) {
        return (socket, head) -> {
            if (socket.getInputStream().read() < 0) {
                return;
            }
            write(socket, "HTTP/1.1 413 Payload Too Large\r\nContent-Length: 0\r\n\r\n");
            done.await(20, TimeUnit.SECONDS);
        };
    }
}
