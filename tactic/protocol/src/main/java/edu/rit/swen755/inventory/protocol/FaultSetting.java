package edu.rit.swen755.inventory.protocol;

/** What a {@link SetFault} changes on the fault link. Written in lower case on the wire. */
public enum FaultSetting {
    /** Percentage (0-100) of lines dropped, in each direction. */
    DROP("drop", 0, 100),
    /** Percentage (0-100) of lines forwarded twice, in each direction. */
    DUPLICATE("duplicate", 0, 100),
    /** Milliseconds (0 or more) every line is held before it is forwarded. */
    DELAY("delay", 0, Integer.MAX_VALUE);

    private final String wire;
    private final int min;
    private final int max;

    FaultSetting(String wire, int min, int max) {
        this.wire = wire;
        this.min = min;
        this.max = max;
    }

    /** The lower-case form written on the wire. */
    public String wire() {
        return wire;
    }

    void requireInRange(int value) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(wire + " must be " + min + "-" + max + ", was " + value);
        }
    }

    static FaultSetting fromWire(String text) {
        for (FaultSetting setting : values()) {
            if (setting.wire.equals(text)) {
                return setting;
            }
        }
        throw new IllegalArgumentException(
                "setting must be drop, duplicate or delay, was '" + text + "'");
    }
}
