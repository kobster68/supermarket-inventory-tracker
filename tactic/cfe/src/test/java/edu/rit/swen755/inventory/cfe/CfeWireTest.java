package edu.rit.swen755.inventory.cfe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.ControlClient;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CFE over its real ports: starts, reports what it bound, answers STATUS. */
class CfeWireTest {

    @TempDir
    Path data;

    @Test
    void defaultsAreTheDocumentedPortsFolderAndSource() {
        assertEquals(new CfeConfig(7301, 7302, 7401, Path.of("demo-data", "cfe"), "store-1"),
                CfeConfig.parse(new String[0]));
    }

    @Test
    void portsFolderAndSourceCanBeOverriddenOnTheCommandLine() {
        assertEquals(new CfeConfig(0, 0, 9401, Path.of("x"), "store-9"),
                CfeConfig.parse(new String[] {"--listen-port", "0", "--control-port", "0",
                    "--master-port", "9401", "--data-dir", "x", "--source", "store-9"}));
        assertThrows(IllegalArgumentException.class,
                () -> CfeConfig.parse(new String[] {"--master-port", "-1"}));
    }

    @Test
    void startsOnEphemeralPortsAndAnswersStatusWithEveryKeyFromAnEmptyLog() throws Exception {
        try (Cfe cfe = Cfe.start(config(0, 0), RetrySettings.DEFAULTS)) {
            Map<String, Integer> ports = cfe.boundPorts();
            assertEquals(List.of("listen", "control"), List.copyOf(ports.keySet()));
            assertTrue(ports.get("listen") > 0);
            assertNotEquals(ports.get("listen"), ports.get("control"));
            assertEquals("READY cfe listen=" + ports.get("listen") + " control=" + ports.get("control"),
                    cfe.readyLine());

            StatusReply status = status(cfe);
            assertEquals(List.of(), StatusKeys.missing(StatusKeys.Cfe.all(List.of("store-1")), status));
            assertEquals(0, status.number("stored"));
            assertEquals(0, status.number("acceptedThrough.store-1"));
            assertEquals(0, status.number("deliveredThrough.store-1"));
        }
    }

    @Test
    void durableStatusSurvivesAStopAndACrash() throws Exception {
        StatusReply first;
        try (Cfe cfe = Cfe.start(config(0, 0), RetrySettings.DEFAULTS)) {
            first = status(cfe);
        }
        Cfe reopened = Cfe.start(config(0, 0), RetrySettings.DEFAULTS);
        assertEquals(durable(first), durable(status(reopened)));
        Map<String, Integer> ports = reopened.boundPorts();
        reopened.crash();

        try (Cfe restarted = Cfe.start(config(ports.get("listen"), ports.get("control")),
                RetrySettings.DEFAULTS)) {
            assertEquals(ports, restarted.boundPorts());
            assertEquals(durable(first), durable(status(restarted)));
        }
    }

    private CfeConfig config(int listen, int control) {
        return new CfeConfig(listen, control, 7401, data.resolve("cfe"), "store-1");
    }

    private static StatusReply status(Cfe cfe) throws Exception {
        return assertInstanceOf(StatusReply.class,
                ControlClient.request(cfe.boundPorts().get("control"), new StatusRequest()));
    }

    private static Map<String, String> durable(StatusReply status) {
        Map<String, String> durable = new LinkedHashMap<>();
        for (String key : StatusKeys.Cfe.durable(List.of("store-1"))) {
            durable.put(key, status.value(key));
        }
        return durable;
    }
}
