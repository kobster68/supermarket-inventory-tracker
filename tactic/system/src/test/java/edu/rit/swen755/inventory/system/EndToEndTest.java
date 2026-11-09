package edu.rit.swen755.inventory.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.rit.swen755.inventory.protocol.Cut;
import edu.rit.swen755.inventory.protocol.FaultSetting;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.Ok;
import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.Restore;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.SaleRecorded;
import edu.rit.swen755.inventory.protocol.SetFault;
import edu.rit.swen755.inventory.system.SystemHarness.Role;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * One test per demo fault plus a combined run, all four processes in one JVM. Each test drives
 * seeded sales through a fault, passes the drain gate, and asserts only the AV-2 guarantees: lost 0,
 * applied twice 0, and store and master stock equal for every product. Informational counters are
 * never asserted, since thread and socket timing vary between runs.
 */
@Disabled("TODO(Godson, #34): enable in the integration PR, once all four processes are merged")
@Timeout(180)
class EndToEndTest {

    /** Short timeouts and backoff so a fault resolves in well under a second. */
    private static final RetrySettings COMPRESSED = new RetrySettings(20, Duration.ofMillis(50),
            Duration.ofMillis(10), Duration.ofMillis(200), 20, 5);

    /** Generous, so a slow CI runner cannot fail a correct run. */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(60);

    private static final int SALES_PER_PHASE = 150;

    @TempDir
    Path base;

    @Test
    void lossyLinkLosesNothing() throws Exception {
        try (SystemHarness system = SystemHarness.start(base, COMPRESSED)) {
            setFault(system, FaultSetting.DROP, 20);
            sell(system, SALES_PER_PHASE, 1);
            setFault(system, FaultSetting.DROP, 0);
            assertGuarantees(system);
        }
    }

    @Test
    void duplicatedLinesAreAppliedOnce() throws Exception {
        try (SystemHarness system = SystemHarness.start(base, COMPRESSED)) {
            setFault(system, FaultSetting.DUPLICATE, 10);
            sell(system, SALES_PER_PHASE, 2);
            setFault(system, FaultSetting.DUPLICATE, 0);
            assertGuarantees(system);
        }
    }

    @Test
    void linkCutWhileSalesContinueLosesNothing() throws Exception {
        try (SystemHarness system = SystemHarness.start(base, COMPRESSED)) {
            sell(system, SALES_PER_PHASE / 3, 3);
            assertEquals(new Ok(), system.control(Role.LINK, new Cut()));
            sell(system, SALES_PER_PHASE, 4);
            assertEquals(new Ok(), system.control(Role.LINK, new Restore()));
            assertGuarantees(system);
        }
    }

    @Test
    void storeCrashLosesNothing() throws Exception {
        crashMidRun(Role.STORE, 5);
    }

    @Test
    void cfeCrashLosesNothing() throws Exception {
        crashMidRun(Role.CFE, 6);
    }

    @Test
    void masterCrashLosesNothing() throws Exception {
        crashMidRun(Role.MASTER, 7);
    }

    @Test
    void combinedRunLosesNothing() throws Exception {
        try (SystemHarness system = SystemHarness.start(base, COMPRESSED)) {
            sell(system, SALES_PER_PHASE / 3, 8);
            setFault(system, FaultSetting.DROP, 20);
            setFault(system, FaultSetting.DUPLICATE, 10);
            sell(system, SALES_PER_PHASE, 9);
            setFault(system, FaultSetting.DROP, 0);
            setFault(system, FaultSetting.DUPLICATE, 0);
            assertEquals(new Ok(), system.control(Role.LINK, new Cut()));
            sell(system, SALES_PER_PHASE / 3, 10);
            assertEquals(new Ok(), system.control(Role.LINK, new Restore()));
            for (Role role : new Role[] {Role.STORE, Role.CFE, Role.MASTER}) {
                try (Driver driver = Driver.start(system, 11 + role.ordinal())) {
                    Thread.sleep(200);
                    system.crash(role);
                    Thread.sleep(100);
                    system.restart(role);
                    Thread.sleep(200);
                }
            }
            assertGuarantees(system);
        }
    }

    /** Sales flow continuously while {@code role} is crashed and restarted on the same folder. */
    private void crashMidRun(Role role, long seed) throws Exception {
        try (SystemHarness system = SystemHarness.start(base, COMPRESSED)) {
            try (Driver driver = Driver.start(system, seed)) {
                Thread.sleep(300);
                system.crash(role);
                Thread.sleep(200);
                system.restart(role);
                Thread.sleep(300);
            }
            assertGuarantees(system);
        }
    }

    private static void assertGuarantees(SystemHarness system) {
        assertTrue(system.awaitDrained(DRAIN_TIMEOUT), "drain gate did not pass within " + DRAIN_TIMEOUT);
        Verdict verdict = system.verdict();
        assertTrue(verdict.created() > 0, "the run created events");
        assertEquals(0, verdict.lost(), "events lost");
        assertEquals(0, verdict.appliedTwice(), "events applied twice");
        assertTrue(verdict.stockMatches(), "store and master stock match for every product");
    }

    private static void setFault(SystemHarness system, FaultSetting setting, int value) throws Exception {
        assertEquals(new Ok(), system.control(Role.LINK, new SetFault(setting, value)));
    }

    /** Sends {@code count} seeded sales one after another on one connection. */
    private static void sell(SystemHarness system, int count, long seed) throws Exception {
        Random random = new Random(seed);
        try (SalesClient client = system.salesClient()) {
            for (int i = 0; i < count; i++) {
                Message reply = client.sell(randomProduct(random), 1 + random.nextInt(3));
                assertTrue(reply instanceof SaleRecorded,
                        "sale " + i + " was not recorded: " + reply);
            }
        }
    }

    private static Product randomProduct(Random random) {
        return Product.values()[random.nextInt(Product.values().length)];
    }

    /**
     * Sends seeded sales on a background thread until closed. A sale that gets no reply (the store
     * is crashed) is not retried; the driver reconnects and carries on with the next sale.
     */
    private static final class Driver implements AutoCloseable {

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;

        private Driver(SystemHarness system, long seed) {
            Random random = new Random(seed);
            thread = new Thread(() -> {
                while (running.get()) {
                    try (SalesClient client = system.salesClient()) {
                        while (running.get()) {
                            client.sell(randomProduct(random), 1 + random.nextInt(3));
                            Thread.sleep(2);
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Exception e) {
                        pause();
                    }
                }
            }, "e2e-sales-driver");
            thread.setDaemon(true);
        }

        static Driver start(SystemHarness system, long seed) {
            Driver driver = new Driver(system, seed);
            driver.thread.start();
            return driver;
        }

        private static void pause() {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            thread.join(5_000);
        }
    }
}
