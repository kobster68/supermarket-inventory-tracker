package edu.rit.swen755.inventory.protocol;

/** {@code CUT}: the fault link stops forwarding in both directions until {@link Restore}. */
public record Cut() implements Message {
}
