package edu.rit.swen755.inventory.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.SaleRecorded;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.ControlClient;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Store Agent over its real ports: starts, reports what it bound, records sales, answers STATUS. */
class StoreAgentWireTest {

    @TempDir
    Path data;

    /** A free port with nothing listening, so the store's sender never reaches a real fault link. */
    private int noLink;

    @BeforeEach
    void pickAnUnusedLinkPort() throws Exception {
        noLink = TestPorts.unused();
    }

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
        try (StoreAgent store = StoreAgent.start(config(0, 0), RetrySettings.DEFAULTS);
                PointOfSale pos = new PointOfSale(store.boundPorts().get("sales"))) {
            pos.sell(Product.P4, 3);
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
    void aSaleChangesStockQueuesOneEventAndRepliesWithItsIdentity() throws Exception {
        try (StoreAgent store = StoreAgent.start(config(0, 0), RetrySettings.DEFAULTS);
                PointOfSale pos = new PointOfSale(store.boundPorts().get("sales"))) {
            SaleRecorded first = pos.sell(Product.P1, 2);
            SaleRecorded second = pos.sell(Product.P3, 1);

            assertEquals(1, first.sequence());
            assertEquals(2, second.sequence());
            assertNotEquals(first.eventId(), second.eventId());
            StatusReply status = status(store);
            assertEquals(2, status.number("created"));
            assertEquals(0, status.number("acceptedThrough"));
            assertEquals(2, status.number("pending"));
            assertEquals(999_998, status.number("stock.P1"));
            assertEquals(1_000_000, status.number("stock.P2"));
            assertEquals(999_999, status.number("stock.P3"));
        }
    }

    @Test
    void invalidSalesLinesAreRefusedWithAnErrorAndChangeNothing() throws Exception {
        try (StoreAgent store = StoreAgent.start(config(0, 0), RetrySettings.DEFAULTS);
                PointOfSale pos = new PointOfSale(store.boundPorts().get("sales"))) {
            for (String line : List.of("SALE|P9|1", "SALE|P1|0", "SALE|P1|4", "SALE|P1", "hello", "STATUS")) {
                assertInstanceOf(ErrorReply.class, pos.send(line), line);
            }
            assertEquals(1, pos.sell(Product.P2, 3).sequence(), "the connection still takes sales");
            StatusReply status = status(store);
            assertEquals(1, status.number("created"));
            assertEquals(1_000_000, status.number("stock.P1"));
            assertEquals(999_997, status.number("stock.P2"));
        }
    }

    @Test
    void restartAfterASaleDoesNotReseedStock() throws Exception {
        try (StoreAgent store = StoreAgent.start(config(0, 0), RetrySettings.DEFAULTS);
                PointOfSale pos = new PointOfSale(store.boundPorts().get("sales"))) {
            pos.sell(Product.P1, 2);
        }
        try (StoreAgent restarted = StoreAgent.start(config(0, 0), RetrySettings.DEFAULTS)) {
            StatusReply status = status(restarted);
            assertEquals(999_998, status.number("stock.P1"));
            assertEquals(1, status.number("created"));
            assertEquals(1, status.number("pending"));
        }
    }

    /**
     * The one test that reaches past the wire: the test-only crash hook fails the sale transaction
     * after the stock update and before the Outbox insert. Neither change may survive.
     */
    @Test
    void aSaleThatFailsBetweenTheStockUpdateAndTheOutboxInsertLeavesNeitherChange() throws Exception {
        StoreAgent store = StoreAgent.start(config(0, 0), RetrySettings.DEFAULTS, true);
        try (PointOfSale pos = new PointOfSale(store.boundPorts().get("sales"))) {
            assertInstanceOf(ErrorReply.class, pos.send("SALE|P1|2"));
            StatusReply status = status(store);
            assertEquals(0, status.number("created"));
            assertEquals(1_000_000, status.number("stock.P1"));
        } finally {
            store.crash();
        }
        try (StoreAgent reopened = StoreAgent.start(config(0, 0), RetrySettings.DEFAULTS);
                PointOfSale pos = new PointOfSale(reopened.boundPorts().get("sales"))) {
            StatusReply status = status(reopened);
            assertEquals(0, status.number("created"));
            assertEquals(0, status.number("pending"));
            assertEquals(1_000_000, status.number("stock.P1"));
            assertEquals(1, pos.sell(Product.P1, 2).sequence(), "no sequence number was used up");
        }
    }

    private StoreAgentConfig config(int sales, int control) {
        return new StoreAgentConfig(sales, control, noLink, data.resolve("store"), "store-1");
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
