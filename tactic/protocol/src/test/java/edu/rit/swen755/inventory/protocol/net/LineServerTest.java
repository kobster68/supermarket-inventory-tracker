package edu.rit.swen755.inventory.protocol.net;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A closed LineServer has released its port, so a restart can bind it at once. */
class LineServerTest {

    private static final int ROUNDS = 200;

    @Test
    void closeReleasesThePortBeforeItReturns() throws Exception {
        int port = 0;
        for (int i = 0; i < ROUNDS; i++) {
            LineServer server = LineServer.control("line-server-test", port,
                    request -> new StatusReply(Map.of("round", "1")));
            port = server.port();
            // One served request leaves the accept thread blocked in accept() again, the state a
            // crash finds a listener in.
            assertInstanceOf(StatusReply.class, ControlClient.request(port, new StatusRequest()));
            server.close();
            try (ServerSocket rebound = Endpoints.listen(port)) {
                // bound: the closed server no longer holds the port
            }
        }
    }
}
