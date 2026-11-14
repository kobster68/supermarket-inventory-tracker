package edu.rit.swen755.inventory.system;

import edu.rit.swen755.inventory.protocol.Defaults;
import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.SaleRecorded;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.CommandLine;
import edu.rit.swen755.inventory.protocol.net.ControlClient;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The demo's point-of-sale feed and verdict table, run by {@code scripts/demo.sh} against the four
 * processes on their default ports.
 *
 * <ul>
 *   <li>{@code sell}: sends seeded sales (a random product, quantity 1 to 3) at a fixed rate until
 *       the process is stopped. A sale that gets no reply is not retried; the driver reconnects
 *       and carries on with the next sale. On stop it prints how many sales it sent.</li>
 *   <li>{@code verdict}: waits at the drain gate, then prints the verdict table from durable
 *       STATUS keys. Exits 0 if the run passed and 1 if it did not.</li>
 * </ul>
 */
public final class DemoDriver {

    /** AV-2's response measure, verbatim from the architecture document. */
    static final String AV2_MEASURE =
            "100% of store events reach the DC; 0 events lost; 0 events applied twice";

    private static final Duration RECONNECT_PAUSE = Duration.ofMillis(200);

    private DemoDriver() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || !(args[0].equals("sell") || args[0].equals("verdict"))) {
            System.err.println("usage: DemoDriver sell [--rate 50] [--seed 1] [--sales-port 7101]");
            System.err.println("       DemoDriver verdict [--drain-timeout-seconds 120]");
            System.exit(2);
        }
        String[] options = Arrays.copyOfRange(args, 1, args.length);
        if (args[0].equals("sell")) {
            CommandLine line = CommandLine.parse(options, Set.of("rate", "seed", "sales-port"));
            sell(line.port("sales-port", Defaults.STORE_SALES_PORT),
                    Integer.parseInt(line.text("rate", "50")), Long.parseLong(line.text("seed", "1")));
        } else {
            CommandLine line = CommandLine.parse(options, Set.of("drain-timeout-seconds"));
            Duration timeout = Duration.ofSeconds(Long.parseLong(line.text("drain-timeout-seconds", "120")));
            System.exit(verdict(timeout) ? 0 : 1);
        }
    }

    /** Sends sales at {@code rate} per second until the JVM is stopped. */
    private static void sell(int salesPort, int rate, long seed) {
        if (rate < 1) {
            throw new IllegalArgumentException("--rate must be at least 1, was " + rate);
        }
        AtomicLong sent = new AtomicLong();
        AtomicLong recorded = new AtomicLong();
        AtomicLong unanswered = new AtomicLong();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.printf(
                "driver stopped: %d sales sent, %d recorded, %d without a reply (not retried)%n",
                sent.get(), recorded.get(), unanswered.get())));
        System.out.printf("driver: %d sales per second to port %d, seed %d%n", rate, salesPort, seed);
        Random random = new Random(seed);
        long intervalNanos = 1_000_000_000L / rate;
        long next = System.nanoTime();
        while (true) {
            try (SalesClient client = SalesClient.connect(salesPort)) {
                while (true) {
                    next = waitUntil(next + intervalNanos);
                    Product product = Product.values()[random.nextInt(Product.values().length)];
                    int quantity = 1 + random.nextInt(3);
                    sent.incrementAndGet();
                    try {
                        Message reply = client.sell(product, quantity);
                        if (reply instanceof SaleRecorded) {
                            recorded.incrementAndGet();
                        }
                    } catch (IOException | MalformedLineException e) {
                        unanswered.incrementAndGet();
                        break;
                    }
                }
            } catch (IOException e) {
                // the store is down: try again shortly
            }
            sleep(RECONNECT_PAUSE.toNanos());
            next = System.nanoTime();
        }
    }

    private static long waitUntil(long deadline) {
        sleep(deadline - System.nanoTime());
        return deadline;
    }

    private static void sleep(long nanos) {
        if (nanos <= 0) {
            return;
        }
        try {
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Waits at the drain gate, prints the verdict table and returns whether the run passed. */
    private static boolean verdict(Duration timeout) throws IOException, MalformedLineException {
        String source = Defaults.SOURCE;
        boolean drained = Verdict.awaitDrained(timeout, () -> Verdict.isDrained(
                status(Defaults.STORE_CONTROL_PORT), status(Defaults.CFE_CONTROL_PORT),
                status(Defaults.MASTER_CONTROL_PORT), source));
        StatusReply store = status(Defaults.STORE_CONTROL_PORT);
        StatusReply cfe = status(Defaults.CFE_CONTROL_PORT);
        StatusReply master = status(Defaults.MASTER_CONTROL_PORT);
        Verdict v = Verdict.from(store, master, source);
        long reachedPercent = v.created() == 0 ? 100 : Math.floorDiv(100 * v.applied(), v.created());

        System.out.println();
        System.out.println("VERDICT (durable STATUS keys only)");
        System.out.println("  drain gate                         " + (drained
                ? "passed (store pending 0; CFE delivered = accepted = master applied)"
                : "FAILED: not drained within " + timeout.toSeconds() + " s"));
        System.out.printf("  events created at the store        %d%n", v.created());
        System.out.printf("  events applied at the master       %d%n", v.applied());
        System.out.println("  AV-2 response measure:");
        System.out.printf("    100%% of store events reach the DC  %d%% (lost = created - applied = %d)%n",
                reachedPercent, v.lost());
        System.out.printf("    0 events lost                      %d%n", v.lost());
        System.out.printf("    0 events applied twice             %d%n", v.appliedTwice());
        System.out.println("  additional cross-check:");
        System.out.println("    store stock = master stock, P1-P5  " + (v.stockMatches() ? "yes" : "NO"));
        for (Product p : Product.values()) {
            System.out.printf("      %s  store %d  master %d%n", p.name(),
                    store.number(StatusKeys.Store.stock(p)), master.number(StatusKeys.Master.stock(source, p)));
        }
        System.out.println("  informational (in memory, reset when a process restarts):");
        System.out.printf("    sales while the link was down      %s%n", store.value(StatusKeys.Store.SALES_WHILE_DOWN));
        System.out.printf("    retries sent (store / CFE)         %s / %s%n",
                store.value(StatusKeys.Store.RETRIES_SENT), cfe.value(StatusKeys.Cfe.RETRIES_SENT));
        System.out.printf("    duplicates ignored (CFE / master)  %s / %s%n",
                cfe.value(StatusKeys.Cfe.DUPLICATES_REACKED), master.value(StatusKeys.Master.DUPLICATES_IGNORED));
        System.out.printf("    link-down episodes                 %s%n", store.value(StatusKeys.Store.LINK_DOWN_EPISODES));
        boolean passed = drained && v.passed();
        System.out.println();
        if (passed) {
            System.out.println("PASS  " + AV2_MEASURE);
        } else {
            System.out.printf("FAIL: lost %d, applied twice %d, stock matches %s, drained %s%n",
                    v.lost(), v.appliedTwice(), v.stockMatches() ? "yes" : "no", drained ? "yes" : "no");
        }
        return passed;
    }

    private static StatusReply status(int controlPort) throws IOException, MalformedLineException {
        Message reply = ControlClient.request(controlPort, new StatusRequest());
        if (reply instanceof StatusReply status) {
            return status;
        }
        throw new IOException("port " + controlPort + " answered STATUS with " + reply);
    }
}
