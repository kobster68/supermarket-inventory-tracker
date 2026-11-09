package edu.rit.swen755.inventory.system;

import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.Sale;
import edu.rit.swen755.inventory.protocol.net.Endpoints;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;

/** A point-of-sale connection to the store's sales port: one {@code SALE} line, one reply, repeated. */
public final class SalesClient implements AutoCloseable {

    private static final int CONNECT_TIMEOUT_MILLIS = 2_000;
    private static final int READ_TIMEOUT_MILLIS = 5_000;

    private final LineConnection connection;

    private SalesClient(LineConnection connection) {
        this.connection = connection;
    }

    static SalesClient connect(int salesPort) throws IOException {
        Socket socket = Endpoints.connect(salesPort, CONNECT_TIMEOUT_MILLIS);
        try {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            return new SalesClient(new LineConnection(socket));
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * Sends one sale and returns the store's reply: {@code SaleRecorded} or {@code ErrorReply}.
     *
     * @throws IOException if the store does not answer (for example while it is crashed); the sale
     *                     is then not retried
     */
    public Message sell(Product product, int quantity) throws IOException, MalformedLineException {
        connection.send(new Sale(product, quantity));
        Message reply = connection.receive();
        if (reply == null) {
            throw new EOFException("the store closed the sales connection");
        }
        return reply;
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }
}
