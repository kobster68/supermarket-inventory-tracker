package edu.rit.swen755.inventory.master;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import edu.rit.swen755.inventory.protocol.Ack;
import edu.rit.swen755.inventory.protocol.AckKind;
import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.Publish;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.ControlClient;
import edu.rit.swen755.inventory.protocol.net.Endpoints;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The master over its real ports, with a fake CFE sending publications and reading replies. */
@Timeout(30)
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
    void restartAfterApplyingAnEventDoesNotReseedStock() throws Exception {
        try (MasterInventory master = MasterInventory.start(config(0, 0));
                LineConnection cfe = connect(master)) {
            assertEquals(applied(1), publish(cfe, event(1, -2)));
        }

        try (MasterInventory master = MasterInventory.start(config(0, 0))) {
            StatusReply status = status(master);
            assertEquals(1, status.number("applied"));
            assertEquals(0, status.number("appliedTwice"));
            assertEquals(1, status.number("appliedThrough.store-1"));
            assertEquals(999_998, status.number("stock.store-1.P1"));
        }
    }

    @Test
    void aBatchUpdatesEveryProductAndReturnsAppliedAcknowledgements() throws Exception {
        try (MasterInventory master = MasterInventory.start(config(0, 0));
                LineConnection cfe = connect(master)) {
            long sequence = 0;
            for (Product product : Product.values()) {
                cfe.send(new Publish(UUID.randomUUID(), "store-1", ++sequence,
                        product, -2, Instant.EPOCH));
            }
            for (long through = 1; through <= sequence; through++) {
                assertEquals(applied(through), cfe.receive());
            }
            assertEquals(applied(6), publish(cfe,
                    new Publish(UUID.randomUUID(), "store-1", 6, Product.P3, 3, Instant.EPOCH)));

            StatusReply status = status(master);
            assertEquals(6, status.number("applied"));
            assertEquals(6, status.number("appliedThrough.store-1"));
            assertEquals(0, status.number("appliedTwice"));
            assertEquals(0, status.number("duplicatesIgnored"));
            for (Product product : Product.values()) {
                long expected = product == Product.P3 ? 1_000_001 : 999_998;
                assertEquals(expected, status.number("stock.store-1." + product));
            }
        }
    }

    @Test
    void duplicatesByIdAndSequenceReturnTheCurrentPositionWithoutChangingStock() throws Exception {
        Publish first = event(1, -2);
        try (MasterInventory master = MasterInventory.start(config(0, 0));
                LineConnection cfe = connect(master)) {
            assertEquals(applied(1), publish(cfe, first));
            assertEquals(applied(2), publish(cfe, event(2, -3)));
            Map<String, String> before = durable(status(master));

            assertEquals(applied(2), publish(cfe, first));
            assertEquals(applied(2), publish(cfe, event(2, -99)));
            Publish repeatedId = new Publish(first.eventId(), "store-1", 3,
                    Product.P2, -50, Instant.EPOCH);
            assertEquals(applied(2), publish(cfe, repeatedId));

            StatusReply status = status(master);
            assertEquals(before, durable(status));
            assertEquals(3, status.number("duplicatesIgnored"));
        }
    }

    @Test
    void gapsReturnCurrentProgressUntilTheMissingEventsAreResent() throws Exception {
        Publish third = event(3, -4);
        try (MasterInventory master = MasterInventory.start(config(0, 0));
                LineConnection cfe = connect(master)) {
            Map<String, String> before = durable(status(master));
            assertEquals(applied(0), publish(cfe, third));
            assertEquals(before, durable(status(master)));

            assertEquals(applied(1), publish(cfe, event(1, -2)));
            assertEquals(applied(1), publish(cfe, third));
            assertEquals(1, status(master).number("applied"));
            assertEquals(applied(2), publish(cfe, event(2, -3)));
            assertEquals(applied(3), publish(cfe, third));

            StatusReply status = status(master);
            assertEquals(3, status.number("applied"));
            assertEquals(3, status.number("appliedThrough.store-1"));
            assertEquals(999_991, status.number("stock.store-1.P1"));
            assertEquals(0, status.number("duplicatesIgnored"));
        }
    }

    @Test
    void anUnreadAcknowledgementIsCoveredByAnUnchangedRetryOnANewConnection() throws Exception {
        Publish first = event(1, -2);
        try (MasterInventory master = MasterInventory.start(config(0, 0))) {
            try (LineConnection cfe = connect(master)) {
                cfe.send(first);
                awaitApplied(master, 1);
                // The fake CFE closes without reading the acknowledgement.
            }
            try (LineConnection retry = connect(master)) {
                assertEquals(applied(1), publish(retry, first));
            }
            StatusReply status = status(master);
            assertEquals(1, status.number("applied"));
            assertEquals(0, status.number("appliedTwice"));
            assertEquals(999_998, status.number("stock.store-1.P1"));
            assertEquals(1, status.number("duplicatesIgnored"));
        }
    }

    @Test
    void aCrashImmediatelyAfterAcknowledgementPreservesTheApplyAndItsDuplicateRecord() throws Exception {
        Publish first = event(1, -2);
        Map<String, Integer> ports;
        try (MasterInventory master = MasterInventory.start(config(0, 0));
                LineConnection cfe = connect(master)) {
            assertEquals(applied(1), publish(cfe, first));
            ports = master.boundPorts();
            // No STATUS read occurs before the crash, so it cannot commit an unfinished apply.
            master.crash();
        }
        try (MasterInventory restarted = MasterInventory.start(config(ports.get("listen"), ports.get("control")));
                LineConnection cfe = connect(restarted)) {
            assertEquals(ports, restarted.boundPorts());
            assertEquals(applied(1), publish(cfe, first));
            StatusReply status = status(restarted);
            assertEquals(1, status.number("applied"));
            assertEquals(0, status.number("appliedTwice"));
            assertEquals(1, status.number("appliedThrough.store-1"));
            assertEquals(999_998, status.number("stock.store-1.P1"));
            assertEquals(1, status.number("duplicatesIgnored"));
            assertEquals(applied(2), publish(cfe, event(2, -3)));
        }
    }

    @Test
    void aFailureDuringApplyReturnsAnErrorAndTheEventCanBeRetriedAfterRestart() throws Exception {
        Publish first = event(1, -2);
        try (MasterInventory master = MasterInventory.start(config(0, 0), true);
                LineConnection cfe = connect(master)) {
            Map<String, String> before = durable(status(master));
            cfe.send(first);
            ErrorReply error = assertInstanceOf(ErrorReply.class, cfe.receive());
            assertTrue(error.reason().contains("test crash hook"));
            assertEquals(before, durable(status(master)));
            assertEquals(0, status(master).number("duplicatesIgnored"));
        }
        try (MasterInventory master = MasterInventory.start(config(0, 0));
                LineConnection cfe = connect(master)) {
            assertEquals(applied(1), publish(cfe, first));
            StatusReply status = status(master);
            assertEquals(1, status.number("applied"));
            assertEquals(0, status.number("appliedTwice"));
            assertEquals(1, status.number("appliedThrough.store-1"));
            assertEquals(999_998, status.number("stock.store-1.P1"));
        }
    }

    @Test
    void malformedMessagesAndUnexpectedSourcesReturnErrorsAndTheConnectionRemainsUsable() throws Exception {
        try (MasterInventory master = MasterInventory.start(config(0, 0));
                LineConnection cfe = connect(master)) {
            Map<String, String> before = durable(status(master));
            cfe.writeLine("PUBLISH|bad");
            assertInstanceOf(ErrorReply.class, cfe.receive());
            cfe.send(new StatusRequest());
            assertInstanceOf(ErrorReply.class, cfe.receive());
            cfe.send(new Publish(UUID.randomUUID(), "store-9", 1, Product.P1, -2, Instant.EPOCH));
            assertInstanceOf(ErrorReply.class, cfe.receive());
            assertEquals(before, durable(status(master)));
            assertEquals(0, status(master).number("duplicatesIgnored"));

            assertEquals(applied(1), publish(cfe, event(1, -2)));
        }
    }

    @Test
    void aStockOverflowReturnsAnErrorAndTheNextValidEventStillApplies() throws Exception {
        try (MasterInventory master = MasterInventory.start(config(0, 0));
                LineConnection cfe = connect(master)) {
            Map<String, String> before = durable(status(master));
            cfe.send(event(1, Long.MAX_VALUE));
            assertInstanceOf(ErrorReply.class, cfe.receive());
            assertEquals(before, durable(status(master)));
            assertEquals(applied(1), publish(cfe, event(1, -2)));
        }
    }

    @Test
    void simultaneousConnectionsAndStatusReadsKeepCommittedStateConsistent() throws Exception {
        List<Publish> events = new ArrayList<>();
        for (int sequence = 1; sequence <= 50; sequence++) {
            events.add(event(sequence, -2));
        }
        try (MasterInventory master = MasterInventory.start(config(0, 0));
                var workers = Executors.newFixedThreadPool(3)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Void> publish = () -> {
                try (LineConnection cfe = connect(master)) {
                    start.await();
                    for (Publish event : events) {
                        Ack ack = publish(cfe, event);
                        assertEquals(AckKind.APPLIED, ack.kind());
                        assertEquals("store-1", ack.source());
                        assertTrue(ack.through() >= event.sequence());
                    }
                }
                return null;
            };
            var firstSender = workers.submit(publish);
            var secondSender = workers.submit(publish);
            var reader = workers.submit(() -> {
                start.await();
                for (int read = 0; read < 100; read++) {
                    StatusReply status = status(master);
                    long applied = status.number("applied");
                    assertEquals(applied, status.number("appliedThrough.store-1"));
                    assertEquals(Product.INITIAL_STOCK - 2 * applied, status.number("stock.store-1.P1"));
                    assertEquals(0, status.number("appliedTwice"));
                }
                return null;
            });
            start.countDown();
            firstSender.get(20, TimeUnit.SECONDS);
            secondSender.get(20, TimeUnit.SECONDS);
            reader.get(20, TimeUnit.SECONDS);

            StatusReply status = status(master);
            assertEquals(50, status.number("applied"));
            assertEquals(50, status.number("appliedThrough.store-1"));
            assertEquals(999_900, status.number("stock.store-1.P1"));
            assertEquals(0, status.number("appliedTwice"));
            assertEquals(50, status.number("duplicatesIgnored"));
        }
    }

    private MasterInventoryConfig config(int listen, int control) {
        return new MasterInventoryConfig(listen, control, data.resolve("master"), "store-1");
    }

    private static LineConnection connect(MasterInventory master) throws Exception {
        Socket socket = Endpoints.connect(master.boundPorts().get("listen"), 2_000);
        socket.setSoTimeout(5_000);
        return new LineConnection(socket);
    }

    private static Ack publish(LineConnection cfe, Publish event) throws Exception {
        cfe.send(event);
        return assertInstanceOf(Ack.class, cfe.receive());
    }

    private static Publish event(long sequence, long delta) {
        return new Publish(UUID.randomUUID(), "store-1", sequence, Product.P1, delta, Instant.EPOCH);
    }

    private static Ack applied(long through) {
        return new Ack(AckKind.APPLIED, "store-1", through);
    }

    private static void awaitApplied(MasterInventory master, long count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (status(master).number("applied") == count) {
                return;
            }
            Thread.sleep(10);
        }
        fail("master did not apply " + count + " events within the timeout");
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
