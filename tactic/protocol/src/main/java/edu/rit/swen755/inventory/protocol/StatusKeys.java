package edu.rit.swen755.inventory.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The frozen STATUS keys of every process. Each process's {@link StatusReply} carries every key
 * listed here, durable keys first, in this order.
 *
 * <p><b>Durable</b> keys are computed from the process's SQLite tables on every request, so a kill
 * cannot reset them; the verdict reads only these. <b>Informational</b> keys may be kept in memory
 * and reset to zero on restart; tests never assert them exactly.
 */
public final class StatusKeys {

    /** Value of a {@code linkState} key while acknowledgements arrive. */
    public static final String LINK_UP = "UP";
    /** Value of a {@code linkState} key after the retry limit is reached (events are kept). */
    public static final String LINK_DOWN = "DOWN";

    private StatusKeys() {
    }

    /** The keys in {@code expected} that {@code reply} does not carry, in order. */
    public static List<String> missing(List<String> expected, StatusReply reply) {
        return expected.stream().filter(k -> !reply.values().containsKey(k)).toList();
    }

    /** Store Agent keys. */
    public static final class Store {

        /** Durable: highest committed sequence (events created). */
        public static final String CREATED = "created";
        /** Durable: highest sequence the CFE has acknowledged as accepted. */
        public static final String ACCEPTED_THROUGH = "acceptedThrough";
        /** Durable: {@code created - acceptedThrough}. */
        public static final String PENDING = "pending";
        /** Informational: sales recorded while {@code linkState} was {@code DOWN}. */
        public static final String SALES_WHILE_DOWN = "salesWhileDown";
        /** Informational: events resent to the CFE. */
        public static final String RETRIES_SENT = "retriesSent";
        /** Informational: times the link was marked down. */
        public static final String LINK_DOWN_EPISODES = "linkDownEpisodes";
        /** Informational: {@link StatusKeys#LINK_UP} or {@link StatusKeys#LINK_DOWN}. */
        public static final String LINK_STATE = "linkState";

        private Store() {
        }

        /** Durable: store-local stock of {@code product}. */
        public static String stock(Product product) {
            return "stock." + product.name();
        }

        public static List<String> durable() {
            List<String> keys = new ArrayList<>(List.of(CREATED, ACCEPTED_THROUGH, PENDING));
            for (Product p : Product.values()) {
                keys.add(stock(p));
            }
            return List.copyOf(keys);
        }

        public static List<String> informational() {
            return List.of(SALES_WHILE_DOWN, RETRIES_SENT, LINK_DOWN_EPISODES, LINK_STATE);
        }

        public static List<String> all() {
            return concat(durable(), informational());
        }
    }

    /** Communications Front-End keys. Per-source keys repeat for every source the CFE reports. */
    public static final class Cfe {

        /** Durable: rows in the event log. */
        public static final String STORED = "stored";
        /** Informational: duplicates re-acknowledged without a new row. */
        public static final String DUPLICATES_REACKED = "duplicatesReacked";
        /** Informational: events resent to the master. */
        public static final String RETRIES_SENT = "retriesSent";
        /** Informational: state of the CFE-to-master link, {@link StatusKeys#LINK_UP} or {@link StatusKeys#LINK_DOWN}. */
        public static final String LINK_STATE = "linkState";

        private Cfe() {
        }

        /** Durable: highest contiguous sequence of {@code source} stored in the event log. */
        public static String acceptedThrough(String source) {
            return "acceptedThrough." + Fields.requireIdentifier(source, "source");
        }

        /** Durable: highest sequence of {@code source} the master has acknowledged as applied. */
        public static String deliveredThrough(String source) {
            return "deliveredThrough." + Fields.requireIdentifier(source, "source");
        }

        public static List<String> durable(Collection<String> sources) {
            List<String> keys = new ArrayList<>(List.of(STORED));
            for (String s : sources) {
                keys.add(acceptedThrough(s));
                keys.add(deliveredThrough(s));
            }
            return List.copyOf(keys);
        }

        public static List<String> informational() {
            return List.of(DUPLICATES_REACKED, RETRIES_SENT, LINK_STATE);
        }

        public static List<String> all(Collection<String> sources) {
            return concat(durable(sources), informational());
        }
    }

    /** Central Master Inventory keys. Per-source keys repeat for every source the master reports. */
    public static final class Master {

        /** Durable: count of distinct applied-event records. */
        public static final String APPLIED = "applied";
        /** Durable: count of events whose stock-changing apply count is above 1. */
        public static final String APPLIED_TWICE = "appliedTwice";
        /** Informational: duplicates recognized and not applied. */
        public static final String DUPLICATES_IGNORED = "duplicatesIgnored";

        private Master() {
        }

        /** Durable: highest contiguous sequence of {@code source} applied. */
        public static String appliedThrough(String source) {
            return "appliedThrough." + Fields.requireIdentifier(source, "source");
        }

        /** Durable: the master's stock of {@code product} at store {@code source}. */
        public static String stock(String source, Product product) {
            return "stock." + Fields.requireIdentifier(source, "source") + "." + product.name();
        }

        public static List<String> durable(Collection<String> sources) {
            List<String> keys = new ArrayList<>(List.of(APPLIED, APPLIED_TWICE));
            for (String s : sources) {
                keys.add(appliedThrough(s));
                for (Product p : Product.values()) {
                    keys.add(stock(s, p));
                }
            }
            return List.copyOf(keys);
        }

        public static List<String> informational() {
            return List.of(DUPLICATES_IGNORED);
        }

        public static List<String> all(Collection<String> sources) {
            return concat(durable(sources), informational());
        }
    }

    /** Fault link keys: its current settings. The link has no storage, so it has no durable keys. */
    public static final class FaultLink {

        /** Current drop percentage, 0-100. */
        public static final String DROP = "drop";
        /** Current duplicate percentage, 0-100. */
        public static final String DUPLICATE = "duplicate";
        /** Current delay in milliseconds. */
        public static final String DELAY = "delay";
        /** {@code true} between {@link Cut} and {@link Restore}, otherwise {@code false}. */
        public static final String CUT = "cut";

        private FaultLink() {
        }

        public static List<String> all() {
            return List.of(DROP, DUPLICATE, DELAY, CUT);
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
