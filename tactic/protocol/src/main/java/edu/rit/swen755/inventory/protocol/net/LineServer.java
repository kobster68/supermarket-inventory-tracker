package edu.rit.swen755.inventory.protocol.net;

import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Message;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A listener on {@code 127.0.0.1} that serves each accepted connection on its own thread.
 * {@link #close()} stops at once: it closes the listener and every open connection and interrupts
 * every thread it started. A process's crash and its clean stop both use it; they differ only in
 * whether the process then closes its database.
 */
public final class LineServer implements AutoCloseable {

    /** Serves one accepted connection until it returns or the connection fails. */
    @FunctionalInterface
    public interface Session {
        void serve(LineConnection connection) throws IOException;
    }

    /** Answers one control request. Any exception becomes an {@code ERR|reason} reply. */
    @FunctionalInterface
    public interface ControlHandler {
        Message handle(Message request) throws Exception;
    }

    /** How long a control connection may stay silent before it is closed. */
    private static final int CONTROL_READ_TIMEOUT_MILLIS = 5_000;

    private final String name;
    private final ServerSocket server;
    private final Session session;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connectionCount = new AtomicInteger();
    private volatile boolean closed;

    private LineServer(String name, ServerSocket server, Session session) {
        this.name = name;
        this.server = server;
        this.session = session;
    }

    /** Binds {@code 127.0.0.1:port} (0 for a free port) and serves every connection with {@code session}. */
    public static LineServer start(String name, int port, Session session) throws IOException {
        LineServer s = new LineServer(name, Endpoints.listen(port), session);
        s.spawn(name + "-accept", s::acceptLoop);
        return s;
    }

    /**
     * A control port: each connection carries one request line and gets one reply line, then
     * closes. A malformed request or a failing handler is answered with {@code ERR|reason}.
     */
    public static LineServer control(String name, int port, ControlHandler handler) throws IOException {
        return start(name, port, connection -> {
            connection.socket().setSoTimeout(CONTROL_READ_TIMEOUT_MILLIS);
            Message reply;
            try {
                Message request = connection.receive();
                if (request == null) {
                    return;
                }
                reply = handler.handle(request);
            } catch (MalformedLineException e) {
                reply = ErrorReply.of(e.getMessage());
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                reply = ErrorReply.of(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            connection.send(reply);
        });
    }

    /** The port actually bound. */
    public int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(server);
        for (Socket s : connections) {
            closeQuietly(s);
        }
        for (Thread t : threads) {
            t.interrupt();
        }
    }

    private void acceptLoop() {
        while (!closed) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (!closed) {
                    System.err.println(name + ": accept failed, listener stopped: " + e);
                }
                return;
            }
            connections.add(socket);
            if (closed) {
                closeQuietly(socket);
                return;
            }
            spawn(name + "-conn-" + connectionCount.incrementAndGet(), () -> serve(socket));
        }
    }

    private void serve(Socket socket) {
        try (LineConnection connection = new LineConnection(socket)) {
            session.serve(connection);
        } catch (SocketException | SocketTimeoutException e) {
            // The peer went away, stayed silent past a read timeout, or close() closed the
            // socket; there is nothing to answer.
        } catch (IOException e) {
            if (!closed) {
                System.err.println(name + ": connection failed: " + e);
            }
        } finally {
            connections.remove(socket);
        }
    }

    private void spawn(String threadName, Runnable body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } finally {
                threads.remove(Thread.currentThread());
            }
        }, threadName);
        t.setDaemon(true);
        threads.add(t);
        t.start();
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // Already closed or failing to close: either way it is no longer in use.
        }
    }
}
