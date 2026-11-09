package edu.rit.swen755.inventory.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.ControlClient;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Store Agent over its real ports: starts, reports what it bound, answers STATUS. */
class StoreAgentWireTest {

    @TempDir
    Path data;

    @Test
    void defaultsAreTheDocumentedPortsFolderAndSource() {
        assertEquals(new StoreAgentConfig(7101, 7102, 7201, Path.of("demo-data", "store-agent"), "store-1"),
                StoreAgentConfig.parse(new String[0]));
    }

    @Test
    void portsFolderAndSourceCanBeOverriddenOnTheCommandLine() {
        assertEquals(new StoreAgentConfig(0, 0, 9201, Path.of("x"), "store-9"),
                StoreAgentConfig.parse(new String[] {"--sales-port", "0", "--control-port", "0",
                    "--link-port", "9201", "--data-dir", "x", "--source", "store-9"}));
        assertThrows(IllegalArgumentException.class,
                () -> StoreAgentConfig.parse(new String[] {"--sales-port", "seven"}));
    }

    @Test
    void startsOnEphemeralPortsAndAnswersStatusWithEveryKeyFromAFreshlySeededStore() throws Exception {
        try (StoreAgent store = StoreAgent.start(config(0, 0), RetrySettings.DEFAULTS)) {
            Map<String, Integer> ports = store.boundPorts();
            assertEquals(List.of("sales", "control"), List.copyOf(ports.keySet()));
            assertTrue(ports.get("sales") > 0);
            assertNotEquals(ports.get("sales"), ports.get("control"));
            assertEquals("READY store-agent sales=" + ports.get("sales") + " control="
                    + ports.get("control"), store.readyLine());

            StatusReply status = status(store);
            assertEquals(List.of(), StatusKeys.missing(StatusKeys.Store.all(), status));
            assertEquals(0, status.number("created"));
            assertEquals(0, status.number("acceptedThrough"));
            assertEquals(0, status.number("pending"));
            for (Product p : Product.values()) {
                assertEquals(1_000_000, status.number("stock." + p.name()));
            }
            assertEquals("UP", status.value("linkState"));
        }
    }

    @Test
    void durableStatusSurvivesAStopAndACrash() throws Exception {
        StatusReply first;
        try (StoreAgent store = StoreAgent.start(config(0, 0), RetrySettings.DEFAULTS)) {
            first = status(store);
        }
        StoreAgent reopened = StoreAgent.start(config(0, 0), RetrySettings.DEFAULTS);
        assertEquals(durable(first), durable(status(reopened)));
        Map<String, Integer> ports = reopened.boundPorts();
        reopened.crash();

        try (StoreAgent restarted = StoreAgent.start(config(ports.get("sales"), ports.get("control")),
                RetrySettings.DEFAULTS)) {
            assertEquals(ports, restarted.boundPorts());
            assertEquals(durable(first), durable(status(restarted)));
        }
    }

    @Test
    @Disabled("TODO(Godson, #30): enable once the store records sales")
    void restartAfterASaleDoesNotReseedStock() {
        // Send SALE|P1|2 to the sales port and read OK, stop the store, start it again on the same
        // folder: stock.P1 is still 999998 and created is 1.
        fail("not written yet");
    }

    private StoreAgentConfig config(int sales, int control) {
        return new StoreAgentConfig(sales, control, 7201, data.resolve("store"), "store-1");
    }

    private static StatusReply status(StoreAgent store) throws Exception {
        return assertInstanceOf(StatusReply.class,
                ControlClient.request(store.boundPorts().get("control"), new StatusRequest()));
    }

    private static Map<String, String> durable(StatusReply status) {
        Map<String, String> durable = new LinkedHashMap<>();
        for (String key : StatusKeys.Store.durable()) {
            durable.put(key, status.value(key));
        }
        return durable;
    }
}
