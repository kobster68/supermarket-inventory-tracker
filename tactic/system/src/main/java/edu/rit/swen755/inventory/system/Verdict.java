package edu.rit.swen755.inventory.system;

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

    /** {@code created - applied}. */
    public long lost() {
        return created - applied;
    }

    public boolean passed() {
        return lost() == 0 && appliedTwice == 0 && stockMatches;
    }
}
