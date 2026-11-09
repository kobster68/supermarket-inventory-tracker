package edu.rit.swen755.inventory.protocol.net;

import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Message;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;

/** Sends one control request ({@code STATUS}, {@code SET}, {@code CUT}, {@code RESTORE}) and reads the reply. */
public final class ControlClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 5_000;

    private ControlClient() {
    }

    /**
     * Connects to {@code 127.0.0.1:port}, sends {@code request}, and returns the decoded reply.
     *
     * @throws IOException            if the port does not answer or closes without a reply
     * @throws MalformedLineException if the reply is not a valid message
     */
    public static Message request(int port, Message request) throws IOException, MalformedLineException {
        Socket socket = Endpoints.connect(port, CONNECT_TIMEOUT_MILLIS);
        LineConnection opened;
        try {
            opened = new LineConnection(socket);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        try (LineConnection connection = opened) {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            connection.send(request);
            Message reply = connection.receive();
            if (reply == null) {
                throw new EOFException("port " + port + " closed without a reply");
            }
            return reply;
        }
    }
}
