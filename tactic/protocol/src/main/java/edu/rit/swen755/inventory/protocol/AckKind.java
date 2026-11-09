package edu.rit.swen755.inventory.protocol;

/** What an {@link Ack} promises about the events it covers. Written in lower case on the wire. */
public enum AckKind {
    /** CFE to store: every sequence up to {@code through} is durably stored in the CFE event log. */
    ACCEPTED("accepted"),
    /** Master to CFE: every sequence up to {@code through} is applied to master stock. */
    APPLIED("applied");

    private final String wire;

    AckKind(String wire) {
        this.wire = wire;
    }

    /** The lower-case form written on the wire. */
    public String wire() {
        return wire;
    }

    static AckKind fromWire(String text) {
        for (AckKind kind : values()) {
            if (kind.wire.equals(text)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("ack kind must be accepted or applied, was '" + text + "'");
    }
}
