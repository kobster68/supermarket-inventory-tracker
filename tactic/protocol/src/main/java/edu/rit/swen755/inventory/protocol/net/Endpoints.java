package edu.rit.swen755.inventory.protocol.net;

import edu.rit.swen755.inventory.protocol.Defaults;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** How every process binds and connects: loopback only, address reuse on every listener. */
public final class Endpoints {

    private static final int BACKLOG = 50;

    private Endpoints() {
    }

    /**
     * A listener on {@code 127.0.0.1:port} with address reuse set before binding, so a process that
     * was killed can be restarted on the same port at once. Port 0 binds a free port; read it back
     * with {@link ServerSocket#getLocalPort()}.
     */
    public static ServerSocket listen(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        try {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(loopback(), port), BACKLOG);
            return server;
        } catch (IOException e) {
            server.close();
            throw e;
        }
    }

    /** A connection to {@code 127.0.0.1:port}. */
    public static Socket connect(int port, int timeoutMillis) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(loopback(), port), timeoutMillis);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static InetAddress loopback() throws IOException {
        return InetAddress.getByName(Defaults.HOST);
    }
}
