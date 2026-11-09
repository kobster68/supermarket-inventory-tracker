package edu.rit.swen755.inventory.protocol;

/** {@code OK}: a control command ({@link SetFault}, {@link Cut}, {@link Restore}) took effect. */
public record Ok() implements Message {
}
