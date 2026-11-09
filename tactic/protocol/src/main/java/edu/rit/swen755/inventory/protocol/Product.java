package edu.rit.swen755.inventory.protocol;

/** The five products every store sells. Written on the wire by name: {@code P1} to {@code P5}. */
public enum Product {
    P1, P2, P3, P4, P5;

    /** Initial stock of every product, seeded once when a data file is first created. */
    public static final long INITIAL_STOCK = 1_000_000L;
}
