package edu.rit.swen755.inventory.protocol;

/** {@code RESTORE}: the fault link forwards again after a {@link Cut}. */
public record Restore() implements Message {
}
