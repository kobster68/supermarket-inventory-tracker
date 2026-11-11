package edu.rit.swen755.inventory.store;

import edu.rit.swen755.inventory.protocol.LineCodec;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.Sale;
import edu.rit.swen755.inventory.protocol.SaleRecorded;
import edu.rit.swen755.inventory.protocol.net.Endpoints;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import java.io.EOFException;
import java.net.Socket;

/** A test point-of-sale feed on the store's sales port: one line out, one reply back. */
final class PointOfSale implements AutoCloseable {

    private final LineConnection connection;

    PointOfSale(int salesPort) throws Exception {
        Socket socket = Endpoints.connect(salesPort, 2_000);
        socket.setSoTimeout(5_000);
        connection = new LineConnection(socket);
    }

    /** Sends a raw line and returns the decoded reply. */
    Message send(String line) throws Exception {
        connection.writeLine(line);
        String reply = connection.readLine();
        if (reply == null) {
            throw new EOFException("the store closed the sales connection");
        }
        return LineCodec.decode(reply);
    }

    /** Sells and expects the store to record the sale. */
    SaleRecorded sell(Product product, int quantity) throws Exception {
        Message reply = send(LineCodec.encode(new Sale(product, quantity)));
        if (reply instanceof SaleRecorded recorded) {
            return recorded;
        }
        throw new AssertionError("expected OK|eventId|sequence, got " + LineCodec.encode(reply));
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}
