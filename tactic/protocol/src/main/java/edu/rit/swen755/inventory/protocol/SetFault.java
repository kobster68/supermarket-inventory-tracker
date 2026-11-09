package edu.rit.swen755.inventory.protocol;

import java.util.Objects;

/**
 * Changes one fault-link setting: {@code SET|drop|<percent 0-100>},
 * {@code SET|duplicate|<percent 0-100>} or {@code SET|delay|<milliseconds>}. Settings apply to
 * lines in both directions, so acknowledgements are dropped, duplicated and delayed as well as
 * events. The link replies {@link Ok} or {@link ErrorReply}.
 */
public record SetFault(FaultSetting setting, int value) implements Message {

    public SetFault {
        Objects.requireNonNull(setting, "setting");
        setting.requireInRange(value);
    }
}
