package edu.rit.swen755.inventory.master;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import edu.rit.swen755.inventory.protocol.Product;
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

/** The Central Master Inventory over its real ports: starts, reports what it bound, answers STATUS. */
class MasterInventoryWireTest {

    @TempDir
    Path data;

    @Test
    void defaultsAreTheDocumentedPortsFolderAndSource() {
        assertEquals(new MasterInventoryConfig(7401, 7402, Path.of("demo-data", "master-inventory"), "store-1"),
                MasterInventoryConfig.parse(new String[0]));
    }

    @Test
    void portsFolderAndSourceCanBeOverriddenOnTheCommandLine() {
        assertEquals(new MasterInventoryConfig(0, 0, Path.of("x", "y"), "store-9"),
                MasterInventoryConfig.parse(new String[] {"--listen-port", "0", "--control-port", "0",
                    "--data-dir", "x/y", "--source", "store-9"}));
        assertThrows(IllegalArgumentException.class,
                () -> MasterInventoryConfig.parse(new String[] {"--source", "store.9"}));
    }

    @Test
    void startsOnEphemeralPortsAndAnswersStatusWithEveryKeyFromAFreshlySeededStore() throws Exception {
        try (MasterInventory master = MasterInventory.start(config(0, 0))) {
            Map<String, Integer> ports = master.boundPorts();
            assertEquals(List.of("listen", "control"), List.copyOf(ports.keySet()));
            assertTrue(ports.get("listen") > 0);
            assertNotEquals(ports.get("listen"), ports.get("control"));
            assertEquals("READY master-inventory listen=" + ports.get("listen") + " control="
                    + ports.get("control"), master.readyLine());

            StatusReply status = status(master);
            assertEquals(List.of(), StatusKeys.missing(StatusKeys.Master.all(List.of("store-1")), status));
            assertEquals(0, status.number("applied"));
            assertEquals(0, status.number("appliedTwice"));
            assertEquals(0, status.number("appliedThrough.store-1"));
            for (Product p : Product.values()) {
                assertEquals(1_000_000, status.number("stock.store-1." + p.name()));
            }
        }
    }

    @Test
    void durableStatusSurvivesAStopAndACrash() throws Exception {
        StatusReply first;
        try (MasterInventory master = MasterInventory.start(config(0, 0))) {
            first = status(master);
        }
        MasterInventory reopened = MasterInventory.start(config(0, 0));
        assertEquals(durable(first), durable(status(reopened)));
        Map<String, Integer> ports = reopened.boundPorts();
        reopened.crash();

        try (MasterInventory restarted = MasterInventory.start(config(ports.get("listen"), ports.get("control")))) {
            assertEquals(ports, restarted.boundPorts());
            assertEquals(durable(first), durable(status(restarted)));
        }
    }

    @Test
    @Disabled("TODO(Mike, #32): enable once the master applies events")
    void restartAfterApplyingAnEventDoesNotReseedStock() {
        // Apply one PUBLISH for store-1 (delta -2 on P1) from a fake CFE, stop the master, start
        // it again on the same folder: stock.store-1.P1 is still 999998 and appliedThrough is 1.
        fail("not written yet");
    }

    private MasterInventoryConfig config(int listen, int control) {
        return new MasterInventoryConfig(listen, control, data.resolve("master"), "store-1");
    }

    private static StatusReply status(MasterInventory master) throws Exception {
        return assertInstanceOf(StatusReply.class,
                ControlClient.request(master.boundPorts().get("control"), new StatusRequest()));
    }

    private static Map<String, String> durable(StatusReply status) {
        Map<String, String> durable = new LinkedHashMap<>();
        for (String key : StatusKeys.Master.durable(List.of("store-1"))) {
            durable.put(key, status.value(key));
        }
        return durable;
    }
}
