package edu.rit.swen755.inventory.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A line that is not exactly one valid message is rejected with a typed error naming the line. */
class MalformedLineTest {

    private static final String ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String AT = "2026-11-09T18:30:00Z";

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "HELLO",
        "publish|" + ID + "|store-1|1|P1|-1|" + AT,
        // PUBLISH
        "PUBLISH|" + ID + "|store-1|1|P1|-1",
        "PUBLISH|" + ID + "|store-1|1|P1|-1|" + AT + "|extra",
        "PUBLISH|not-a-uuid|store-1|1|P1|-1|" + AT,
        "PUBLISH|" + ID + "||1|P1|-1|" + AT,
        "PUBLISH|" + ID + "|store.1|1|P1|-1|" + AT,
        "PUBLISH|" + ID + "|store-1|0|P1|-1|" + AT,
        "PUBLISH|" + ID + "|store-1|one|P1|-1|" + AT,
        "PUBLISH|" + ID + "|store-1|1|P6|-1|" + AT,
        "PUBLISH|" + ID + "|store-1|1|P1|minus|" + AT,
        "PUBLISH|" + ID + "|store-1|1|P1|-1|yesterday",
        "PUBLISH|" + ID + "|store-1|1|P1|-1|2026-11-09T18:30:00+01:00",
        // ACK
        "ACK|received|store-1|3",
        "ACK|accepted|store-1|-1",
        "ACK|applied|store-1",
        "ACK|applied|store-1|3|4",
        // sales line and replies
        "SALE|P1",
        "SALE|P1|0",
        "SALE|P1|4",
        "SALE|P0|1",
        "SALE|P1|1|extra",
        "OK|" + ID,
        "OK|" + ID + "|0",
        "ERR",
        "ERR|",
        "ERR|a|b",
        // control
        "STATUS|now",
        "SET|drop|101",
        "SET|duplicate|-1",
        "SET|delay|-5",
        "SET|jitter|5",
        "SET|drop",
        "CUT|now",
        "RESTORE|now",
        // status replies
        "created=1|created=2",
        "created=1|oops",
        "=5",
        "created=",
    })
    void isRejectedWithTheOffendingLine(String line) {
        MalformedLineException e = assertThrows(MalformedLineException.class, () -> LineCodec.decode(line));
        assertEquals(line, e.line());
    }

    @org.junit.jupiter.api.Test
    void nullIsRejected() {
        assertThrows(MalformedLineException.class, () -> LineCodec.decode(null));
    }
}
