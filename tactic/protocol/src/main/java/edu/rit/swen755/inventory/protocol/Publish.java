package edu.rit.swen755.inventory.protocol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One store inventory event: {@code PUBLISH|eventId|source|sequence|product|delta|timestamp}. Sent
 * by the store to the CFE and forwarded unchanged by the CFE to the master.
 *
 * @param eventId   created with the sale; never changes on retry
 * @param source    the store that created the event (see {@link Fields#requireIdentifier})
 * @param sequence  consecutive per source, starting at 1; order, not timestamp, defines position
 * @param product   the product whose stock changed
 * @param delta     the signed stock change, negative for a sale; both ends add it to stock
 * @param timestamp when the sale was recorded, ISO-8601 UTC; informational only
 */
public record Publish(UUID eventId, String source, long sequence, Product product, long delta,
        Instant timestamp) implements Message {

    public Publish {
        Objects.requireNonNull(eventId, "eventId");
        Fields.requireIdentifier(source, "source");
        Fields.requireAtLeast(sequence, 1, "sequence");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
