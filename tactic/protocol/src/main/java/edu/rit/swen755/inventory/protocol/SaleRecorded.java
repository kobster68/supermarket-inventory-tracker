package edu.rit.swen755.inventory.protocol;

import java.util.Objects;
import java.util.UUID;

/**
 * The store's reply to a {@link Sale} once the stock change, the Outbox row and the next sequence
 * number have committed together: {@code OK|eventId|sequence}.
 */
public record SaleRecorded(UUID eventId, long sequence) implements Message {

    public SaleRecorded {
        Objects.requireNonNull(eventId, "eventId");
        Fields.requireAtLeast(sequence, 1, "sequence");
    }
}
