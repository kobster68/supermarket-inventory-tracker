package edu.rit.swen755.inventory.store;

import edu.rit.swen755.inventory.protocol.net.Endpoints;
import java.io.IOException;
import java.net.ServerSocket;

/** Ports for the store's tests. */
final class TestPorts {

    private TestPorts() {
    }

    /** A port with nothing listening on it (until a test starts something there). */
    static int unused() throws IOException {
        try (ServerSocket probe = Endpoints.listen(0)) {
            return probe.getLocalPort();
        }
    }
}
