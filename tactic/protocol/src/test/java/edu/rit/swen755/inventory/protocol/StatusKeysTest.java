package edu.rit.swen755.inventory.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The STATUS keys are frozen: these literal lists are the contract every process and the verdict share. */
class StatusKeysTest {

    @Test
    void storeKeysAreFrozen() {
        assertEquals(List.of("created", "acceptedThrough", "pending",
                "stock.P1", "stock.P2", "stock.P3", "stock.P4", "stock.P5"),
                StatusKeys.Store.durable());
        assertEquals(List.of("salesWhileDown", "retriesSent", "linkDownEpisodes", "linkState"),
                StatusKeys.Store.informational());
    }

    @Test
    void cfeKeysAreFrozen() {
        assertEquals(List.of("stored", "acceptedThrough.store-1", "deliveredThrough.store-1"),
                StatusKeys.Cfe.durable(List.of("store-1")));
        assertEquals(List.of("duplicatesReacked", "retriesSent", "linkState"),
                StatusKeys.Cfe.informational());
    }

    @Test
    void masterKeysAreFrozen() {
        assertEquals(List.of("applied", "appliedTwice", "appliedThrough.store-1",
                "stock.store-1.P1", "stock.store-1.P2", "stock.store-1.P3", "stock.store-1.P4",
                "stock.store-1.P5"),
                StatusKeys.Master.durable(List.of("store-1")));
        assertEquals(List.of("duplicatesIgnored"), StatusKeys.Master.informational());
    }

    @Test
    void perSourceKeysRepeatForEachSourceInOrder() {
        assertEquals(List.of("stored",
                "acceptedThrough.store-1", "deliveredThrough.store-1",
                "acceptedThrough.store-2", "deliveredThrough.store-2"),
                StatusKeys.Cfe.durable(List.of("store-1", "store-2")));
    }

    @Test
    void faultLinkKeysAreFrozen() {
        assertEquals(List.of("drop", "duplicate", "delay", "cut"), StatusKeys.FaultLink.all());
    }

    @Test
    void missingKeysAreNamed() {
        StatusReply reply = new StatusReply(java.util.Map.of("drop", "0", "cut", "false"));
        assertEquals(List.of("duplicate", "delay"),
                StatusKeys.missing(StatusKeys.FaultLink.all(), reply));
    }
}
