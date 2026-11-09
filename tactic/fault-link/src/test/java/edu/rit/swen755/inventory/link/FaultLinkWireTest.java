package edu.rit.swen755.inventory.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.ControlClient;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The fault link over its real ports: starts, reports what it bound, answers STATUS. */
class FaultLinkWireTest {

    @Test
    void defaultsAreTheDocumentedPorts() {
        FaultLinkConfig config = FaultLinkConfig.parse(new String[0]);
        assertEquals(new FaultLinkConfig(7201, 7202, 7301), config);
    }

    @Test
    void everyPortCanBeOverriddenOnTheCommandLine() {
        FaultLinkConfig config = FaultLinkConfig.parse(new String[] {
            "--listen-port", "0", "--control-port", "9202", "--cfe-port", "9301"});
        assertEquals(new FaultLinkConfig(0, 9202, 9301), config);
    }

    @Test
    void unknownOptionsAndBadPortsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FaultLinkConfig.parse(new String[] {"--drop", "5"}));
        assertThrows(IllegalArgumentException.class,
                () -> FaultLinkConfig.parse(new String[] {"--listen-port", "70000"}));
        assertThrows(IllegalArgumentException.class,
                () -> FaultLinkConfig.parse(new String[] {"--listen-port"}));
    }

    @Test
    void startsOnEphemeralPortsReportsThemAndAnswersStatusWithEveryKey() throws Exception {
        try (FaultLink link = FaultLink.start(new FaultLinkConfig(0, 0, 7301))) {
            Map<String, Integer> ports = link.boundPorts();
            assertEquals(List.of("listen", "control"), List.copyOf(ports.keySet()));
            assertTrue(ports.get("listen") > 0);
            assertTrue(ports.get("control") > 0);
            assertNotEquals(ports.get("listen"), ports.get("control"));
            assertEquals("READY fault-link listen=" + ports.get("listen") + " control="
                    + ports.get("control"), link.readyLine());

            StatusReply status = assertInstanceOf(StatusReply.class,
                    ControlClient.request(ports.get("control"), new StatusRequest()));
            assertEquals(List.of(), StatusKeys.missing(StatusKeys.FaultLink.all(), status));
            assertEquals("0", status.value(StatusKeys.FaultLink.DROP));
            assertEquals("false", status.value(StatusKeys.FaultLink.CUT));
        }
    }

    @Test
    void bindsTheExactPortItIsGiven() throws Exception {
        int listen = freePort();
        int control = freePort();
        try (FaultLink link = FaultLink.start(new FaultLinkConfig(listen, control, 7301))) {
            assertEquals(Map.of("listen", listen, "control", control), link.boundPorts());
        }
    }

    @Test
    void aMalformedControlLineGetsAnErrorReplyAndTheLinkKeepsAnswering() throws Exception {
        try (FaultLink link = FaultLink.start(new FaultLinkConfig(0, 0, 7301));
                Socket socket = new Socket("127.0.0.1", link.boundPorts().get("control"))) {
            OutputStream out = socket.getOutputStream();
            out.write("NONSENSE|1\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            assertTrue(in.readLine().startsWith("ERR|"));
            assertEquals(null, in.readLine(), "a control connection closes after one reply");

            assertInstanceOf(StatusReply.class,
                    ControlClient.request(link.boundPorts().get("control"), new StatusRequest()));
        }
    }

    @Test
    void crashFreesThePortsForAnImmediateRestart() throws Exception {
        FaultLink first = FaultLink.start(new FaultLinkConfig(0, 0, 7301));
        Map<String, Integer> ports = first.boundPorts();
        first.crash();

        try (FaultLink second = FaultLink.start(
                new FaultLinkConfig(ports.get("listen"), ports.get("control"), 7301))) {
            assertEquals(ports, second.boundPorts());
            assertInstanceOf(StatusReply.class,
                    ControlClient.request(ports.get("control"), new StatusRequest()));
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
