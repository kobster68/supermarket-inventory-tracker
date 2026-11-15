package edu.rit.swen755.inventory.master;

import static edu.rit.swen755.inventory.master.MasterDatabase.ApplyOutcome.APPLIED;
import static edu.rit.swen755.inventory.master.MasterDatabase.ApplyOutcome.DUPLICATE;
import static edu.rit.swen755.inventory.master.MasterDatabase.ApplyOutcome.GAP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import edu.rit.swen755.inventory.master.MasterDatabase.ApplyResult;
import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.Publish;
import edu.rit.swen755.inventory.protocol.StatusReply;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
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

/** Database-level transaction and restart checks. */
class MasterDatabaseTest {

    private static final String SOURCE = "store-1";

    @TempDir
    Path data;

    @Test
    void appliesContiguousEventsAndAddsTheirSignedStockChanges() throws Exception {
        try (MasterDatabase database = MasterDatabase.open(data, SOURCE)) {
            assertEquals(new ApplyResult(APPLIED, 1), database.apply(event(1, -2)));
            assertEquals(new ApplyResult(APPLIED, 2), database.apply(event(2, 3)));

            StatusReply status = status(database);
            assertEquals(2, status.number("applied"));
            assertEquals(0, status.number("appliedTwice"));
            assertEquals(2, status.number("appliedThrough.store-1"));
            assertEquals(1_000_001, status.number("stock.store-1.P1"));
            for (Product product : List.of(Product.P2, Product.P3, Product.P4, Product.P5)) {
                assertEquals(Product.INITIAL_STOCK, status.number("stock.store-1." + product));
            }
        }
    }

    @Test
    void duplicatesByEventIdOrSequenceLeaveAllDurableStateUnchanged() throws Exception {
        Publish first = event(1, -2);
        try (MasterDatabase database = MasterDatabase.open(data, SOURCE)) {
            database.apply(first);
            Map<String, String> before = database.durableStatus(SOURCE);

            assertEquals(new ApplyResult(DUPLICATE, 1), database.apply(first));
            assertEquals(new ApplyResult(DUPLICATE, 1), database.apply(event(1, -99)));
            Publish repeatedId = new Publish(first.eventId(), SOURCE, 2, Product.P2, -50, Instant.EPOCH);
            assertEquals(new ApplyResult(DUPLICATE, 1), database.apply(repeatedId));
            assertEquals(before, database.durableStatus(SOURCE));

            assertEquals(new ApplyResult(APPLIED, 2), database.apply(event(2, -3)));
            assertEquals(2, status(database).number("applied"));
            assertEquals(999_995, status(database).number("stock.store-1.P1"));
        }
    }

    @Test
    void aGapReturnsTheCurrentPositionUntilTheMissingEventArrives() throws Exception {
        Publish second = event(2, -3);
        try (MasterDatabase database = MasterDatabase.open(data, SOURCE)) {
            Map<String, String> before = database.durableStatus(SOURCE);
            assertEquals(new ApplyResult(GAP, 0), database.apply(second));
            assertEquals(before, database.durableStatus(SOURCE));

            assertEquals(new ApplyResult(APPLIED, 1), database.apply(event(1, -2)));
            assertEquals(new ApplyResult(APPLIED, 2), database.apply(second));
            StatusReply status = status(database);
            assertEquals(2, status.number("applied"));
            assertEquals(2, status.number("appliedThrough.store-1"));
            assertEquals(999_995, status.number("stock.store-1.P1"));
        }
    }

    @Test
    void failureAfterChangingStockRollsBackAndTheEventCanBeRetriedAfterReopen() throws Exception {
        Publish first = event(1, -2);
        Map<String, String> before;
        try (MasterDatabase database = MasterDatabase.open(data, SOURCE, true)) {
            before = database.durableStatus(SOURCE);
            SQLException failure = assertThrows(SQLException.class, () -> database.apply(first));
            assertTrue(failure.getMessage().contains("test crash hook"));
            assertEquals(before, database.durableStatus(SOURCE));
        }

        try (MasterDatabase database = MasterDatabase.open(data, SOURCE)) {
            assertEquals(before, database.durableStatus(SOURCE));
            assertEquals(new ApplyResult(APPLIED, 1), database.apply(first));
            StatusReply status = status(database);
            assertEquals(1, status.number("applied"));
            assertEquals(0, status.number("appliedTwice"));
            assertEquals(1, status.number("appliedThrough.store-1"));
            assertEquals(999_998, status.number("stock.store-1.P1"));
        }
    }

    @Test
    void committedStockProgressAndDuplicateRecordsSurviveReopen() throws Exception {
        Publish first = event(1, -2);
        Map<String, String> committed;
        try (MasterDatabase database = MasterDatabase.open(data, SOURCE)) {
            database.apply(first);
            committed = database.durableStatus(SOURCE);
        }

        try (MasterDatabase database = MasterDatabase.open(data, SOURCE)) {
            assertEquals(committed, database.durableStatus(SOURCE));
            assertEquals(new ApplyResult(DUPLICATE, 1), database.apply(first));
            assertEquals(committed, database.durableStatus(SOURCE));
            assertEquals(new ApplyResult(APPLIED, 2), database.apply(event(2, -3)));
            assertEquals(999_995, status(database).number("stock.store-1.P1"));
        }
    }

    @Test
    void theConfiguredSourceIsUsedAndAnExistingFolderCannotBeReassigned() throws Exception {
        Map<String, String> committed;
        try (MasterDatabase database = MasterDatabase.open(data, "store-9")) {
            Map<String, String> before = database.durableStatus("store-9");
            assertThrows(IllegalArgumentException.class, () -> database.apply(event(1, -2)));
            assertEquals(before, database.durableStatus("store-9"));

            Publish event = new Publish(UUID.randomUUID(), "store-9", 1, Product.P1, -2, Instant.EPOCH);
            assertEquals(new ApplyResult(APPLIED, 1), database.apply(event));
            committed = database.durableStatus("store-9");
            assertEquals("999998", committed.get("stock.store-9.P1"));
        }

        assertThrows(SQLException.class, () -> {
            try (MasterDatabase unexpected = MasterDatabase.open(data, SOURCE)) {
                fail("opening another store's database should fail");
            }
        });
        try (MasterDatabase database = MasterDatabase.open(data, "store-9")) {
            assertEquals(committed, database.durableStatus("store-9"));
        }
    }

    @Test
    void stockOverflowLeavesTheEventAndCursorUnapplied() throws Exception {
        try (MasterDatabase database = MasterDatabase.open(data, SOURCE)) {
            Map<String, String> before = database.durableStatus(SOURCE);
            assertThrows(ArithmeticException.class, () -> database.apply(event(1, Long.MAX_VALUE)));
            assertEquals(before, database.durableStatus(SOURCE));
            assertEquals(new ApplyResult(APPLIED, 1), database.apply(event(1, -2)));
        }
    }

    @Test
    @Timeout(30)
    void simultaneousDuplicatesAndStatusReadsKeepStockRecordsAndProgressConsistent() throws Exception {
        List<Publish> events = new ArrayList<>();
        for (int sequence = 1; sequence <= 50; sequence++) {
            events.add(event(sequence, -2));
        }
        try (MasterDatabase database = MasterDatabase.open(data, SOURCE);
                var workers = Executors.newFixedThreadPool(3)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Void> publish = () -> {
                start.await();
                for (Publish event : events) {
                    ApplyResult result = database.apply(event);
                    assertNotEquals(GAP, result.outcome());
                    assertTrue(result.appliedThrough() >= event.sequence());
                }
                return null;
            };
            var firstSender = workers.submit(publish);
            var secondSender = workers.submit(publish);
            var reader = workers.submit(() -> {
                start.await();
                for (int read = 0; read < 100; read++) {
                    StatusReply status = status(database);
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

            StatusReply status = status(database);
            assertEquals(50, status.number("applied"));
            assertEquals(50, status.number("appliedThrough.store-1"));
            assertEquals(999_900, status.number("stock.store-1.P1"));
            assertEquals(0, status.number("appliedTwice"));
        }
    }

    private static Publish event(long sequence, long delta) {
        return new Publish(UUID.randomUUID(), SOURCE, sequence, Product.P1, delta, Instant.EPOCH);
    }

    private static StatusReply status(MasterDatabase database) throws SQLException {
        return new StatusReply(database.durableStatus(SOURCE));
    }
}
