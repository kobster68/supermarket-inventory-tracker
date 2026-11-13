package edu.rit.swen755.inventory.cfe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import edu.rit.swen755.inventory.protocol.Ack;
import edu.rit.swen755.inventory.protocol.AckKind;
import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.Publish;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.ControlClient;
import edu.rit.swen755.inventory.protocol.net.Endpoints;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real TCP peers exercise persistence, acceptance and both halves of the second retry leg. */
class CfeDeliveryTest {
    private static final RetrySettings FAST = new RetrySettings(100, Duration.ofMillis(150),
            Duration.ofMillis(10), Duration.ofMillis(50), 20, 3);
    @TempDir Path data;
    private Cfe cfe;
    private FakeMaster master;

    @AfterEach
    void stop() {
        if (cfe != null) cfe.close();
        if (master != null) master.close();
    }

    @Test
    void acknowledgedEventsAreAlreadyOnDiskAndSurviveCrashWithAllFields() throws Exception {
        master = new FakeMaster(0);
        master.silent = true;
        start(master.port(), FAST);
        Publish event = event("store-1", 1);
        assertEquals(new Ack(AckKind.ACCEPTED, "store-1", 1), publish(event));
        // A separate connection sees only committed data, while the CFE is still running.
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("cfe.db"));
                var s = db.createStatement();
                var rs = s.executeQuery("SELECT e.event_id, p.accepted_through FROM event_log e"
                        + " JOIN position p ON e.source = p.source")) {
            assertTrue(rs.next());
            assertEquals(event.eventId().toString(), rs.getString(1));
            assertEquals(1, rs.getLong(2));
        }
        Map<String, String> before = durable(status());
        cfe.crash();
        cfe = null;
        start(master.port(), FAST);
        assertEquals(before, durable(status()));
        master.silent = false;
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 1);
        assertEquals(event, master.applied("store-1").get(1L));
    }

    @Test
    void duplicatesAreReackedAndGapsAreRefusedIndependentlyPerSource() throws Exception {
        start(unusedPort(), FAST);
        Publish one = event("store-1", 1);
        assertEquals(1, publish(one).through());
        assertEquals(1, publish(one).through());
        assertEquals(1, publish(event("store-1", 1)).through());
        assertEquals(1, publish(event("store-1", 3)).through());
        assertEquals(0, publish(event("store-2", 2)).through());
        // A known event ID must not be inserted under a new source or sequence either.
        try (LineConnection store = connect()) {
            store.send(new Publish(one.eventId(), "store-2", 1,
                    one.product(), one.delta(), one.timestamp()));
            assertInstanceOf(ErrorReply.class, store.receive());
        }
        assertEquals(1, publish(event("store-2", 1)).through());
        assertEquals(2, publish(event("store-1", 2)).through());
        StatusReply s = status();
        assertEquals(3, s.number("stored"));
        assertEquals(2, s.number("acceptedThrough.store-1"));
        assertEquals(1, s.number("acceptedThrough.store-2"));
        assertEquals(2, s.number("duplicatesReacked"));
    }

    @Test
    void reusedEventIdAtTheNextSequenceReturnsAnErrorWithoutAdvancing() throws Exception {
        start(unusedPort(), FAST);
        Publish one = event("store-1", 1);
        publish(one);
        try (LineConnection store = connect()) {
            store.send(new Publish(one.eventId(), "store-1", 2,
                    one.product(), one.delta(), one.timestamp()));
            assertInstanceOf(ErrorReply.class, store.receive());
        }
        assertEquals(1, status().number("stored"));
        assertEquals(1, status().number("acceptedThrough.store-1"));
        assertEquals(0, status().number("duplicatesReacked"));
        assertEquals(2, publish(event("store-1", 2)).through());
    }

    @Test
    void failureBetweenLogInsertAndCursorUpdateRollsBackBothAndNeverAcks() throws Exception {
        start(unusedPort(), FAST);
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("cfe.db"));
                var s = db.createStatement()) {
            s.execute("CREATE TRIGGER fail_position BEFORE INSERT ON position BEGIN"
                    + " SELECT RAISE(ABORT, 'test failure'); END");
        }
        try (LineConnection store = connect()) {
            store.send(event("store-1", 1));
            assertInstanceOf(ErrorReply.class, store.receive());
        }
        cfe.close();
        cfe = null;
        start(unusedPort(), FAST);
        assertEquals(0, status().number("stored"));
        assertEquals(0, status().number("acceptedThrough.store-1"));
    }

    @Test
    void forwardsOriginalEventsInOrderAndToleratesDuplicateAndStaleAppliedAcks() throws Exception {
        master = new FakeMaster(0);
        master.noisy = true;
        start(master.port(), FAST);
        List<Publish> events = send("store-1", 120);
        List<Publish> second = send("store-2", 8);
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 120
                && s.number("deliveredThrough.store-2") == 8);
        assertEquals(events, List.copyOf(master.applied("store-1").values()));
        assertEquals(second, List.copyOf(master.applied("store-2").values()));
        assertEquals(List.of(), master.conflicts());
    }

    @Test
    void aLostEventIsResentWithItsOriginalIdentityAfterTheGap() throws Exception {
        master = new FakeMaster(0);
        master.dropOnce = 2;
        start(master.port(), FAST);
        List<Publish> events = send("store-1", 10);
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 10);
        assertEquals(events, List.copyOf(master.applied("store-1").values()));
        assertTrue(status().number("retriesSent") > 0);
        assertEquals(List.of(), master.conflicts());
    }

    @Test
    void aLostAppliedAckCausesResendWithoutStoringTheEventTwice() throws Exception {
        master = new FakeMaster(0);
        master.dropAcks = true;
        start(master.port(), FAST);
        List<Publish> events = send("store-1", 10);
        await(() -> master.applied("store-1").size() == 10);
        assertEquals(0, status().number("deliveredThrough.store-1"));
        master.dropAcks = false;
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 10);
        assertEquals(events, List.copyOf(master.applied("store-1").values()));
        assertTrue(status().number("retriesSent") > 0);
        assertEquals(List.of(), master.conflicts());
    }

    @Test
    void anUnresponsiveMasterGoesDownWithoutLosingEventsAndRecovers() throws Exception {
        master = new FakeMaster(0);
        master.silent = true;
        start(master.port(), FAST);
        List<Publish> events = send("store-1", 10);
        awaitStatus(s -> s.value("linkState").equals("DOWN"));
        assertEquals(10, status().number("stored"));
        assertEquals(0, status().number("deliveredThrough.store-1"));
        master.silent = false;
        StatusReply up = awaitStatus(s -> s.number("deliveredThrough.store-1") == 10);
        assertEquals("UP", up.value("linkState"));
        assertEquals(events, List.copyOf(master.applied("store-1").values()));
    }

    @Test
    void aMasterThatRefusesEverythingAlsoGoesDownAndCanRecover() throws Exception {
        master = new FakeMaster(0);
        master.acceptLimit = 0;
        start(master.port(), FAST);
        send("store-1", 5);
        awaitStatus(s -> s.value("linkState").equals("DOWN"));
        assertEquals(0, status().number("deliveredThrough.store-1"));
        master.acceptLimit = Long.MAX_VALUE;
        assertEquals("UP", awaitStatus(s -> s.number("deliveredThrough.store-1") == 5).value("linkState"));
    }

    @Test
    void acceptsWhileMasterIsUnreachableAndRetriesAfterReconnect() throws Exception {
        int port = unusedPort();
        start(port, FAST);
        List<Publish> events = send("store-1", 5);
        awaitStatus(s -> s.value("linkState").equals("DOWN"));
        master = new FakeMaster(port);
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 5);
        assertEquals(events, List.copyOf(master.applied("store-1").values()));
    }

    @Test
    void neverExceedsTheUnacknowledgedWindow() throws Exception {
        master = new FakeMaster(0);
        master.silent = true;
        start(master.port(), new RetrySettings(5, FAST.ackTimeout(), FAST.initialBackoff(),
                FAST.maxBackoff(), FAST.jitterPercent(), FAST.linkDownAfter()));
        List<Publish> events = send("store-1", 20);
        awaitStatus(s -> s.value("linkState").equals("DOWN"));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                master.received().stream().map(Publish::sequence).distinct().sorted().toList());
        master.silent = false;
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 20);
        assertEquals(events, List.copyOf(master.applied("store-1").values()));
    }

    @Test
    void restartResumesAfterTheDurableDeliveredPositionAndAcceptsMoreTraffic() throws Exception {
        master = new FakeMaster(0);
        master.acceptLimit = 3;
        start(master.port(), FAST);
        List<Publish> events = send("store-1", 6);
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 3);
        Map<String, String> before = durable(status());
        cfe.crash();
        cfe = null;
        master.clearReceived();
        start(master.port(), FAST);
        assertEquals(before, durable(status()));
        await(() -> !master.received().isEmpty());
        assertEquals(4, master.received().getFirst().sequence());
        master.acceptLimit = Long.MAX_VALUE;
        Publish seventh = event("store-1", 7);
        publish(seventh);
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 7);
        events.add(seventh);
        assertEquals(events, List.copyOf(master.applied("store-1").values()));
        assertTrue(master.received().stream().allMatch(e -> e.sequence() >= 4));
    }

    @Test
    void reconnectsAfterMasterDropsTheConnectionBeforeAcknowledging() throws Exception {
        master = new FakeMaster(0);
        master.disconnectOnce = true;
        start(master.port(), FAST);
        List<Publish> events = send("store-1", 5);
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 5);
        assertEquals(events, List.copyOf(master.applied("store-1").values()));
        assertTrue(master.connections.get() >= 2);
        assertTrue(status().number("retriesSent") > 0);
        assertEquals(List.of(), master.conflicts());
    }

    @Test
    void wrongKindUnknownSourceAndAheadOfSentAcksCannotAdvanceDelivery() throws Exception {
        master = new FakeMaster(0);
        master.invalidAcks = true;
        start(master.port(), FAST);
        send("store-1", 5);
        awaitStatus(s -> s.value("linkState").equals("DOWN"));
        assertEquals(0, status().number("deliveredThrough.store-1"));
        master.invalidAcks = false;
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 5);
    }

    @Test
    void anEventAfterIdleGetsAFullAckTimeout() throws Exception {
        master = new FakeMaster(0);
        RetrySettings timing = new RetrySettings(100, Duration.ofMillis(500),
                FAST.initialBackoff(), FAST.maxBackoff(), 0, 3);
        start(master.port(), timing);
        publish(event("store-1", 1));
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 1);
        Thread.sleep(350);
        master.ackDelayMillis = 300;
        publish(event("store-1", 2));
        awaitStatus(s -> s.number("deliveredThrough.store-1") == 2);
        assertEquals(1, master.connections.get(), "an ACK within the timeout should keep the connection");
        assertEquals(0, status().number("retriesSent"));
    }

    private void start(int port, RetrySettings retry) throws Exception {
        cfe = Cfe.start(new CfeConfig(0, 0, port, data, "store-1"), retry);
    }

    private LineConnection connect() throws Exception {
        LineConnection store = new LineConnection(Endpoints.connect(cfe.boundPorts().get("listen"), 2_000));
        store.socket().setSoTimeout(2_000);
        return store;
    }

    private Ack publish(Publish event) throws Exception {
        try (LineConnection store = connect()) {
            store.send(event);
            return assertInstanceOf(Ack.class, store.receive());
        }
    }

    private List<Publish> send(String source, int count) throws Exception {
        List<Publish> events = new ArrayList<>();
        try (LineConnection store = connect()) {
            for (int i = 1; i <= count; i++) {
                Publish event = event(source, i);
                events.add(event);
                store.send(event);
                assertEquals(new Ack(AckKind.ACCEPTED, source, i), store.receive());
            }
        }
        return events;
    }

    private static Publish event(String source, long sequence) {
        return new Publish(UUID.randomUUID(), source, sequence, Product.P2, -2, Instant.now());
    }

    private StatusReply status() throws Exception {
        return assertInstanceOf(StatusReply.class,
                ControlClient.request(cfe.boundPorts().get("control"), new StatusRequest()));
    }

    private StatusReply awaitStatus(Predicate<StatusReply> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        StatusReply last;
        do {
            last = status();
            if (condition.test(last)) return last;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("condition not reached: " + last.values());
        return last;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "peer condition not reached");
            Thread.sleep(20);
        }
    }

    private static Map<String, String> durable(StatusReply status) {
        var values = new java.util.LinkedHashMap<>(status.values());
        StatusKeys.Cfe.informational().forEach(values::remove);
        return values;
    }

    private static int unusedPort() throws Exception {
        try (var socket = Endpoints.listen(0)) { return socket.getLocalPort(); }
    }
}
