package edu.rit.swen755.inventory.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.Publish;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.SaleRecorded;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.ControlClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Outbox over the wire: the store sends its events to a fake CFE on its link port and resends
 * them until they are accepted. Retry timings are compressed; assertions are about outcomes (every
 * event accepted once with its original identity, link state changes), never exact retry counts.
 */
class StoreAgentOutboxTest {

    /** Window 100, 100 ms acknowledgement timeout, 10 to 50 ms backoff, 20% jitter, down after 3. */
    private static final RetrySettings FAST = new RetrySettings(100, Duration.ofMillis(100),
            Duration.ofMillis(10), Duration.ofMillis(50), 20, 3);
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @TempDir
    Path data;

    private FakeCfe cfe;
    private StoreAgent store;

    @AfterEach
    void stop() {
        if (store != null) {
            store.close();
        }
        if (cfe != null) {
            cfe.close();
        }
    }

    @Test
    void everyEventReachesTheCfeInSequenceOrderWithTheIdentityTheSaleWasGiven() throws Exception {
        cfe = FakeCfe.start(0);
        store = start(cfe.port(), FAST);

        Map<Long, UUID> sold = sell(store, 25);

        StatusReply status = await(store, s -> s.number("pending") == 0);
        assertEquals(25, status.number("acceptedThrough"));
        assertEquals(sold, cfe.log());
        Publish first = cfe.received().get(0);
        assertEquals(1, first.sequence());
        assertEquals("store-1", first.source());
        assertEquals(Product.values()[0], first.product());
        assertEquals(-1, first.delta());
        assertEquals("UP", status.value("linkState"));
    }

    @Test
    void salesAreRecordedWhileTheCfeIsUnreachableAndDeliveredOnceItReturns() throws Exception {
        int linkPort = TestPorts.unused();
        store = start(linkPort, FAST);

        Map<Long, UUID> sold = new LinkedHashMap<>(sell(store, 5));
        StatusReply down = await(store, s -> s.value("linkState").equals("DOWN"));
        assertTrue(down.number("linkDownEpisodes") >= 1);
        sold.putAll(sell(store, 5));
        StatusReply stillDown = status(store);
        assertEquals(10, stillDown.number("pending"), "every event is kept while the link is down");
        assertTrue(stillDown.number("salesWhileDown") >= 5);

        cfe = FakeCfe.start(linkPort);
        StatusReply up = await(store, s -> s.number("pending") == 0);
        assertEquals("UP", up.value("linkState"));
        assertEquals(10, up.number("acceptedThrough"));
        assertEquals(sold, cfe.log());
        assertEquals(List.of(), cfe.identityConflicts());
    }

    @Test
    void aLostEventIsResentWithItsOriginalIdentityUntilTheCfeAcceptsIt() throws Exception {
        cfe = FakeCfe.start(0);
        cfe.dropFirstDeliveryOf(2);
        store = start(cfe.port(), FAST);

        Map<Long, UUID> sold = sell(store, 10);

        StatusReply status = await(store, s -> s.number("pending") == 0);
        assertEquals(sold, cfe.log());
        assertEquals(List.of(), cfe.identityConflicts());
        for (Publish event : cfe.received()) {
            assertEquals(sold.get(event.sequence()), event.eventId(), "a resend keeps its event ID");
        }
        assertTrue(status.number("retriesSent") > 0);
    }

    @Test
    void anAcknowledgementLostOnTheWayBackIsCoveredByAResendThatTheCfeDoesNotStoreTwice() throws Exception {
        cfe = FakeCfe.start(0);
        cfe.behave(FakeCfe.Behaviour.DROP_ACKS);
        store = start(cfe.port(), FAST);

        Map<Long, UUID> sold = sell(store, 10);
        awaitStored(cfe, 10);
        assertEquals(10, status(store).number("pending"), "no acknowledgement has reached the store");
        cfe.behave(FakeCfe.Behaviour.ACK);

        StatusReply status = await(store, s -> s.number("pending") == 0);
        assertEquals(10, status.number("acceptedThrough"));
        assertTrue(status.number("retriesSent") > 0);
        assertTrue(cfe.received().size() > 10, "the store resent events the CFE already held");
        assertEquals(sold, cfe.log(), "every event stored once, with its original identity");
        assertEquals(List.of(), cfe.identityConflicts());
    }

    @Test
    void eventsSentToAnUnresponsiveCfeAreResentAndDeliveredOnceItAnswers() throws Exception {
        cfe = FakeCfe.start(0);
        cfe.behave(FakeCfe.Behaviour.SILENT);
        store = start(cfe.port(), FAST);

        Map<Long, UUID> sold = sell(store, 10);
        awaitReceived(cfe, 10);
        cfe.behave(FakeCfe.Behaviour.ACK);

        await(store, s -> s.number("pending") == 0);
        assertEquals(sold, cfe.log());
        assertEquals(List.of(), cfe.identityConflicts());
    }

    @Test
    void aCfeThatAnswersButRefusesEveryEventIsMarkedDownAndDeliveredToOnceItAccepts() throws Exception {
        cfe = FakeCfe.start(0);
        cfe.acceptOnlyThrough(0);
        store = start(cfe.port(), FAST);

        Map<Long, UUID> sold = sell(store, 10);
        StatusReply down = await(store, s -> s.value("linkState").equals("DOWN"));
        assertTrue(down.number("linkDownEpisodes") >= 1);
        assertEquals(10, down.number("pending"), "every event is kept while the CFE refuses it");
        assertEquals(0, down.number("acceptedThrough"));
        assertTrue(cfe.received().size() >= 10, "the CFE was reachable and answered");
        assertEquals(Map.of(), cfe.log());

        cfe.acceptOnlyThrough(Long.MAX_VALUE);
        StatusReply up = await(store, s -> s.number("pending") == 0);
        assertEquals("UP", up.value("linkState"));
        assertEquals(10, up.number("acceptedThrough"));
        assertEquals(sold, cfe.log());
        assertEquals(List.of(), cfe.identityConflicts());
    }

    @Test
    void duplicateAndStaleAcknowledgementsNeverMoveTheStoreBackwards() throws Exception {
        cfe = FakeCfe.start(0);
        cfe.behave(FakeCfe.Behaviour.NOISY_ACKS);
        store = start(cfe.port(), FAST);

        Map<Long, UUID> sold = new LinkedHashMap<>();
        long highest = 0;
        for (int round = 0; round < 5; round++) {
            sold.putAll(sell(store, 10));
            long acceptedThrough = status(store).number("acceptedThrough");
            assertTrue(acceptedThrough >= highest, "acceptedThrough went from " + highest + " to " + acceptedThrough);
            highest = acceptedThrough;
        }

        StatusReply status = await(store, s -> s.number("pending") == 0);
        assertEquals(50, status.number("acceptedThrough"));
        assertEquals(sold, cfe.log());
    }

    @Test
    void noMoreThanTheWindowIsInFlightWithoutAnAcknowledgement() throws Exception {
        RetrySettings windowOfFive = new RetrySettings(5, FAST.ackTimeout(), FAST.initialBackoff(),
                FAST.maxBackoff(), FAST.jitterPercent(), FAST.linkDownAfter());
        cfe = FakeCfe.start(0);
        cfe.behave(FakeCfe.Behaviour.SILENT);
        store = start(cfe.port(), windowOfFive);

        Map<Long, UUID> sold = sell(store, 20);
        await(store, s -> s.value("linkState").equals("DOWN"));
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                cfe.received().stream().map(Publish::sequence).distinct().sorted().toList());

        cfe.behave(FakeCfe.Behaviour.ACK);
        await(store, s -> s.number("pending") == 0);
        assertEquals(sold, cfe.log());
    }

    @Test
    void aRestartedStoreResumesAfterItsLastAcceptedSequence() throws Exception {
        cfe = FakeCfe.start(0);
        cfe.acceptOnlyThrough(3);
        store = start(cfe.port(), FAST);
        Map<Long, UUID> sold = new LinkedHashMap<>(sell(store, 6));
        await(store, s -> s.number("acceptedThrough") == 3);
        store.crash();
        store = null;

        cfe.behave(FakeCfe.Behaviour.SILENT);
        cfe.forgetReceived();
        store = start(cfe.port(), FAST);
        StatusReply reopened = status(store);
        assertEquals(6, reopened.number("created"));
        assertEquals(3, reopened.number("acceptedThrough"));
        assertEquals(3, reopened.number("pending"));
        awaitReceived(cfe, 1);
        assertEquals(4, cfe.received().get(0).sequence(), "the first event sent after a restart");

        cfe.acceptOnlyThrough(Long.MAX_VALUE);
        cfe.behave(FakeCfe.Behaviour.ACK);
        sold.putAll(sell(store, 2));
        await(store, s -> s.number("pending") == 0);
        assertEquals(sold, cfe.log());
        assertTrue(cfe.received().stream().allMatch(e -> e.sequence() >= 4), "nothing accepted is resent");
    }

    private StoreAgent start(int linkPort, RetrySettings retry) throws Exception {
        return StoreAgent.start(new StoreAgentConfig(0, 0, linkPort, data.resolve("store"), "store-1"), retry);
    }

    /** Sells {@code count} times, cycling P1..P5 with quantity 1..3; returns sequence to event ID. */
    private static Map<Long, UUID> sell(StoreAgent store, int count) throws Exception {
        Map<Long, UUID> sold = new LinkedHashMap<>();
        try (PointOfSale pos = new PointOfSale(store.boundPorts().get("sales"))) {
            for (int i = 0; i < count; i++) {
                SaleRecorded recorded = pos.sell(Product.values()[i % 5], 1 + i % 3);
                sold.put(recorded.sequence(), recorded.eventId());
            }
        }
        return sold;
    }

    private static StatusReply status(StoreAgent store) throws Exception {
        return assertInstanceOf(StatusReply.class,
                ControlClient.request(store.boundPorts().get("control"), new StatusRequest()));
    }

    /** Polls STATUS until {@code condition} holds; fails with the last reply after {@link #PATIENCE}. */
    private static StatusReply await(StoreAgent store, Predicate<StatusReply> condition) throws Exception {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        StatusReply last = status(store);
        while (!condition.test(last)) {
            assertTrue(System.nanoTime() < deadline, "condition not reached; last STATUS " + last.values());
            Thread.sleep(20);
            last = status(store);
        }
        return last;
    }

    /** Waits until the fake CFE has stored at least {@code count} events. */
    private static void awaitStored(FakeCfe cfe, int count) throws Exception {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (cfe.log().size() < count) {
            assertTrue(System.nanoTime() < deadline, "the CFE stored only " + cfe.log().size());
            Thread.sleep(10);
        }
    }

    /** Waits until the fake CFE has received at least {@code count} events. */
    private static void awaitReceived(FakeCfe cfe, int count) throws Exception {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (cfe.received().size() < count) {
            assertTrue(System.nanoTime() < deadline, "the CFE received only " + cfe.received().size());
            Thread.sleep(10);
        }
    }
}
