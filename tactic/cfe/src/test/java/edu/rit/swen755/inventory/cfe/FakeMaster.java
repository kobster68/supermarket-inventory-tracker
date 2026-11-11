package edu.rit.swen755.inventory.cfe;

import edu.rit.swen755.inventory.protocol.Ack;
import edu.rit.swen755.inventory.protocol.AckKind;
import edu.rit.swen755.inventory.protocol.Publish;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import edu.rit.swen755.inventory.protocol.net.LineServer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Protocol-only peer: keeps original events, accepts contiguously, and can lose traffic. */
final class FakeMaster implements AutoCloseable {
    private final LineServer server;
    private final Map<String, Map<Long, Publish>> applied = new LinkedHashMap<>();
    private final List<Publish> received = new ArrayList<>();
    private final List<Publish> conflicts = new ArrayList<>();
    final AtomicInteger connections = new AtomicInteger();
    volatile boolean silent;
    volatile boolean dropAcks;
    volatile boolean noisy;
    volatile boolean invalidAcks;
    volatile boolean disconnectOnce;
    volatile long acceptLimit = Long.MAX_VALUE;
    volatile long dropOnce = -1;
    volatile long ackDelayMillis;

    FakeMaster(int port) throws IOException {
        server = LineServer.start("fake-master", port, this::serve);
    }

    int port() { return server.port(); }
    synchronized List<Publish> received() { return List.copyOf(received); }
    synchronized List<Publish> conflicts() { return List.copyOf(conflicts); }
    synchronized void clearReceived() { received.clear(); }
    synchronized Map<Long, Publish> applied(String source) {
        return new LinkedHashMap<>(applied.getOrDefault(source, Map.of()));
    }

    private void serve(LineConnection cfe) throws IOException {
        connections.incrementAndGet();
        String line;
        while ((line = cfe.readLine()) != null) {
            Publish event;
            try {
                event = (Publish) edu.rit.swen755.inventory.protocol.LineCodec.decode(line);
            } catch (Exception e) {
                throw new IOException(e);
            }
            long through;
            synchronized (this) {
                received.add(event);
                if (silent || event.sequence() == dropOnce) {
                    if (event.sequence() == dropOnce) {
                        dropOnce = -1;
                    }
                    continue;
                }
                Map<Long, Publish> log = applied.computeIfAbsent(event.source(), key -> new LinkedHashMap<>());
                Publish old = log.get(event.sequence());
                if (old != null && !old.equals(event)) {
                    conflicts.add(event);
                }
                if (event.sequence() == log.size() + 1L && event.sequence() <= acceptLimit) {
                    log.put(event.sequence(), event);
                }
                through = log.size();
            }
            if (dropAcks) {
                continue;
            }
            if (disconnectOnce) {
                disconnectOnce = false;
                return;
            }
            if (invalidAcks) {
                cfe.writeLine("malformed acknowledgement");
                cfe.send(new Ack(AckKind.ACCEPTED, event.source(), through));
                cfe.send(new Ack(AckKind.APPLIED, "unknown-store", through));
                cfe.send(new Ack(AckKind.APPLIED, event.source(), Long.MAX_VALUE));
                continue;
            }
            if (ackDelayMillis > 0) {
                try {
                    Thread.sleep(ackDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            cfe.send(new Ack(AckKind.APPLIED, event.source(), through));
            if (noisy) {
                cfe.send(new Ack(AckKind.APPLIED, event.source(), through));
                cfe.send(new Ack(AckKind.APPLIED, event.source(), Math.max(0, through - 2)));
            }
        }
    }

    @Override
    public void close() { server.close(); }
}
