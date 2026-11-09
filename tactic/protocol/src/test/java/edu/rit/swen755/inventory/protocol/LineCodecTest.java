package edu.rit.swen755.inventory.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Every message encodes to its documented line and decodes back to an equal value. */
class LineCodecTest {

    private static final UUID EVENT = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    @Test
    void publishHasItsDocumentedLineAndRoundTrips() throws MalformedLineException {
        Publish publish = new Publish(EVENT, "store-1", 7, Product.P3, -2,
                Instant.parse("2026-11-09T18:30:00Z"));
        String line = "PUBLISH|0f8fad5b-d9cb-469f-a165-70867728950e|store-1|7|P3|-2|2026-11-09T18:30:00Z";

        assertEquals(line, LineCodec.encode(publish));
        assertEquals(publish, LineCodec.decode(line));
    }

    @Test
    void acceptedAckHasItsDocumentedLineAndRoundTrips() throws MalformedLineException {
        assertRoundTrip(new Ack(AckKind.ACCEPTED, "store-1", 41), "ACK|accepted|store-1|41");
    }

    @Test
    void appliedAckHasItsDocumentedLineAndRoundTrips() throws MalformedLineException {
        assertRoundTrip(new Ack(AckKind.APPLIED, "store-1", 0), "ACK|applied|store-1|0");
    }

    @Test
    void saleHasItsDocumentedLineAndRoundTrips() throws MalformedLineException {
        assertRoundTrip(new Sale(Product.P5, 3), "SALE|P5|3");
    }

    @Test
    void saleRecordedReplyHasItsDocumentedLineAndRoundTrips() throws MalformedLineException {
        assertRoundTrip(new SaleRecorded(EVENT, 12),
                "OK|0f8fad5b-d9cb-469f-a165-70867728950e|12");
    }

    @Test
    void errorReplyHasItsDocumentedLineAndRoundTrips() throws MalformedLineException {
        assertRoundTrip(new ErrorReply("quantity must be 1-3"), "ERR|quantity must be 1-3");
    }

    @Test
    void statusRequestHasItsDocumentedLineAndRoundTrips() throws MalformedLineException {
        assertRoundTrip(new StatusRequest(), "STATUS");
    }

    @Test
    void statusReplyIsKeyValuePairsInOrderAndRoundTrips() throws MalformedLineException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("created", "12");
        values.put("acceptedThrough", "10");
        values.put("linkState", "UP");
        assertRoundTrip(new StatusReply(values), "created=12|acceptedThrough=10|linkState=UP");
    }

    @Test
    void statusReplyReadsValuesAsNumbers() throws MalformedLineException {
        StatusReply reply = (StatusReply) LineCodec.decode("stored=4|acceptedThrough.store-1=4");
        assertEquals(4L, reply.number("acceptedThrough.store-1"));
    }

    @Test
    void faultSettingsHaveTheirDocumentedLinesAndRoundTrip() throws MalformedLineException {
        assertRoundTrip(new SetFault(FaultSetting.DROP, 20), "SET|drop|20");
        assertRoundTrip(new SetFault(FaultSetting.DUPLICATE, 100), "SET|duplicate|100");
        assertRoundTrip(new SetFault(FaultSetting.DELAY, 1500), "SET|delay|1500");
    }

    @Test
    void cutRestoreAndOkHaveTheirDocumentedLinesAndRoundTrip() throws MalformedLineException {
        assertRoundTrip(new Cut(), "CUT");
        assertRoundTrip(new Restore(), "RESTORE");
        assertRoundTrip(new Ok(), "OK");
    }

    private static void assertRoundTrip(Message message, String line) throws MalformedLineException {
        assertEquals(line, LineCodec.encode(message));
        assertEquals(message, LineCodec.decode(line));
    }
}
