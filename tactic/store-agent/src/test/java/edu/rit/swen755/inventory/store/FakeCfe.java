package edu.rit.swen755.inventory.store;

import edu.rit.swen755.inventory.protocol.Ack;
import edu.rit.swen755.inventory.protocol.AckKind;
import edu.rit.swen755.inventory.protocol.Publish;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import edu.rit.swen755.inventory.protocol.net.LineServer;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for the CFE, built from {@code protocol}, listening where the store expects the fault
 * link. It follows the CFE's acceptance rule: store an event only if it is the next contiguous
 * sequence, re-acknowledge one it already holds, refuse one beyond a gap, and always answer with
 * the cumulative {@code ACK|accepted|source|through}. Tests switch its behaviour while it runs.
 */
final class FakeCfe implements AutoCloseable {

    /** How the fake answers. */
    enum Behaviour {
        /** The CFE's rule, one acknowledgement per event. */
        ACK,
        /** The CFE's rule, but every acknowledgement is sent twice and followed by a stale one. */
        NOISY_ACKS,
        /** The CFE's rule, but every acknowledgement is lost on the way back to the store. */
        DROP_ACKS,
        /** Reads and records every line but stores nothing and answers nothing, like a cut link. */
        SILENT
    }

    private final LineServer server;
    private final Map<Long, UUID> log = new LinkedHashMap<>();
    private final List<Publish> received = new ArrayList<>();
    private final List<String> identityConflicts = new ArrayList<>();
    private final Set<Long> dropFirstDelivery = ConcurrentHashMap.newKeySet();
    private volatile Behaviour behaviour = Behaviour.ACK;
    /** Events above this sequence are refused (not stored, current position acknowledged). */
    private volatile long acceptLimit = Long.MAX_VALUE;
    /** How long each acknowledgement is held back before it is sent. */
    private volatile Duration ackDelay = Duration.ZERO;

    private FakeCfe(int port) throws IOException {
        server = LineServer.start("fake-cfe", port, this::serve);
    }

    /** Listens on {@code port} (0 for a free one). */
    static FakeCfe start(int port) throws IOException {
        return new FakeCfe(port);
    }

    int port() {
        return server.port();
    }

    void behave(Behaviour behaviour) {
        this.behaviour = behaviour;
    }

    /** The first time {@code sequence} arrives it is lost, as on a lossy link. */
    void dropFirstDeliveryOf(long sequence) {
        dropFirstDelivery.add(sequence);
    }

    /** Holds every acknowledgement back for {@code delay}, as a slow CFE or link would. */
    void delayAcks(Duration delay) {
        ackDelay = delay;
    }

    void acceptOnlyThrough(long sequence) {
        acceptLimit = sequence;
    }

    /** Stored events, sequence to event ID, in the order stored. */
    synchronized Map<Long, UUID> log() {
        return new LinkedHashMap<>(log);
    }

    /** Every PUBLISH that reached the fake, including resends and refused ones. */
    synchronized List<Publish> received() {
        return List.copyOf(received);
    }

    synchronized void forgetReceived() {
        received.clear();
    }

    /** Events that arrived again under a known sequence but a different event ID. */
    synchronized List<String> identityConflicts() {
        return List.copyOf(identityConflicts);
    }

    private void serve(LineConnection store) throws IOException {
        String line;
        while ((line = store.readLine()) != null) {
            if (!(OutboxSender.decodeOrNull(line) instanceof Publish event)) {
                continue;
            }
            long through;
            synchronized (this) {
                received.add(event);
                if (behaviour == Behaviour.SILENT || dropFirstDelivery.remove(event.sequence())) {
                    continue;
                }
                UUID held = log.get(event.sequence());
                if (held != null && !held.equals(event.eventId())) {
                    identityConflicts.add(event.sequence() + ": " + held + " then " + event.eventId());
                }
                if (event.sequence() == log.size() + 1L && event.sequence() <= acceptLimit) {
                    log.put(event.sequence(), event.eventId());
                }
                through = log.size();
            }
            if (behaviour == Behaviour.DROP_ACKS) {
                continue;
            }
            try {
                Thread.sleep(ackDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            store.send(new Ack(AckKind.ACCEPTED, event.source(), through));
            if (behaviour == Behaviour.NOISY_ACKS) {
                store.send(new Ack(AckKind.ACCEPTED, event.source(), through));
                store.send(new Ack(AckKind.ACCEPTED, event.source(), Math.max(0, through - 3)));
            }
        }
    }

    @Override
    public void close() {
        server.close();
    }
}
