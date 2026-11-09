package edu.rit.swen755.inventory.protocol;

import java.util.Objects;

/**
 * One sale, sent by the point-of-sale feed (the demo driver) to the store's sales port:
 * {@code SALE|product|quantity}. The store replies {@link SaleRecorded} after its transaction
 * commits, or {@link ErrorReply}. A sales connection may carry many sales, each answered in order.
 * A sale that gets no reply is not retried by the driver.
 *
 * @param quantity units sold, 1 to 3; the event's delta is {@code -quantity}
 */
public record Sale(Product product, int quantity) implements Message {

    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 3;

    public Sale {
        Objects.requireNonNull(product, "product");
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException(
                    "quantity must be " + MIN_QUANTITY + "-" + MAX_QUANTITY + ", was " + quantity);
        }
    }
}
