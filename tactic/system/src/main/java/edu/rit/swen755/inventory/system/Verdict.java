package edu.rit.swen755.inventory.system;

import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import java.io.IOException;
import java.time.Duration;

/**
 * The run's result, computed only from durable STATUS keys after the drain gate. The pass line is
 * the architecture document's AV-2 response measure, "100% of store events reach the DC; 0 events
 * lost; 0 events applied twice": 100% reached and 0 lost both mean {@code lost == 0}. Stock match
 * is an additional cross-check. A run passes only if all three hold.
 *
 * @param created      store {@code created}
 * @param applied      master {@code applied}
 * @param appliedTwice master {@code appliedTwice}
 * @param stockMatches store {@code stock.Pn} equals master {@code stock.<source>.Pn} for every product
 */
public record Verdict(long created, long applied, long appliedTwice, boolean stockMatches) {

    /** How often the drain gate polls STATUS. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    /** One poll of the three STATUS replies; an exception means a process did not answer yet. */
    @FunctionalInterface
    interface DrainCheck {
        boolean drained() throws IOException, MalformedLineException;
    }

    /** {@code created - applied}. */
    public long lost() {
        return created - applied;
    }

    public boolean passed() {
        return lost() == 0 && appliedTwice == 0 && stockMatches;
    }

    /** The verdict from the store's and the master's STATUS replies for {@code source}. */
    public static Verdict from(StatusReply store, StatusReply master, String source) {
        boolean stockMatches = true;
        for (Product p : Product.values()) {
            if (store.number(StatusKeys.Store.stock(p)) != master.number(StatusKeys.Master.stock(source, p))) {
                stockMatches = false;
            }
        }
        return new Verdict(store.number(StatusKeys.Store.CREATED),
                master.number(StatusKeys.Master.APPLIED),
                master.number(StatusKeys.Master.APPLIED_TWICE), stockMatches);
    }

    /**
     * The drain gate's condition for {@code source}: store {@code pending} is 0, CFE
     * {@code deliveredThrough} equals CFE {@code acceptedThrough}, and master {@code appliedThrough}
     * equals CFE {@code acceptedThrough}.
     */
    public static boolean isDrained(StatusReply store, StatusReply cfe, StatusReply master, String source) {
        long accepted = cfe.number(StatusKeys.Cfe.acceptedThrough(source));
        return store.number(StatusKeys.Store.PENDING) == 0
                && cfe.number(StatusKeys.Cfe.deliveredThrough(source)) == accepted
                && master.number(StatusKeys.Master.appliedThrough(source)) == accepted;
    }

    /**
     * Polls {@code check} until it reports drained or {@code timeout} passes. A process that does
     * not answer (for example while it restarts) counts as not drained yet.
     *
     * @return {@code false} if {@code timeout} passed first
     */
    static boolean awaitDrained(Duration timeout, DrainCheck check) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                if (check.drained()) {
                    return true;
                }
            } catch (IOException | MalformedLineException | RuntimeException e) {
                // not answering yet: poll again
            }
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
