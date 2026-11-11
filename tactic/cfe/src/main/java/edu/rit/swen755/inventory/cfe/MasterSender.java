package edu.rit.swen755.inventory.cfe;

import edu.rit.swen755.inventory.protocol.Ack;
import edu.rit.swen755.inventory.protocol.AckKind;
import edu.rit.swen755.inventory.protocol.LineCodec;
import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Publish;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.net.Endpoints;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Retry leg from the durable CFE log to the master. Each connection starts at the durable
 * delivered positions and sends at most window events in total, ordered within each source.
 * Only an applied ACK that advances a sent position resets failures. Repeated ACKs (including
 * gap refusals) wait for the progress timeout instead of causing a resend storm.
 * Connections are closed before backoff; no event is removed from the log.
 */
final class MasterSender {
    private final CfeDatabase database;
    private final RetrySettings retry;
    private final int port;
    private final Thread thread;
    private final AtomicLong retriesSent = new AtomicLong();
    private final Map<String, Long> highestSent = new HashMap<>();
    private volatile boolean stopped;
    private volatile LineConnection connection;
    private volatile String linkState = StatusKeys.LINK_UP;
    private int failures;

    MasterSender(CfeDatabase database, RetrySettings retry, int port) {
        this.database = database;
        this.retry = retry;
        this.port = port;
        thread = new Thread(this::run, "cfe-master-sender");
        thread.setDaemon(true);
    }

    void start() { thread.start(); }
    String linkState() { return linkState; }
    long retriesSent() { return retriesSent.get(); }

    void stop() {
        stopped = true;
        closeQuietly(connection);
        thread.interrupt();
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (!stopped) {
            try {
                if (database.positions().values().stream().noneMatch(p -> p.accepted() > p.delivered())) {
                    Thread.sleep(20);
                    continue;
                }
                try {
                    attempt();
                } catch (IOException | SQLException | RuntimeException e) {
                    if (!stopped) {
                        failedAttempt();
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (SQLException e) {
                System.err.println("cfe: reading the event log failed: " + e);
                try {
                    failedAttempt();
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }
    }

    private void attempt() throws IOException, SQLException, InterruptedException {
        LineConnection c = new LineConnection(Endpoints.connect(port, (int) retry.ackTimeout().toMillis()));
        connection = c;
        BlockingQueue<Object> replies = new ArrayBlockingQueue<>(retry.window() + 1);
        Object disconnected = new Object();
        Thread reader = new Thread(() -> {
            try {
                String line;
                while ((line = c.readLine()) != null) {
                    try {
                        if (LineCodec.decode(line) instanceof Ack ack && ack.kind() == AckKind.APPLIED) {
                            replies.put(ack);
                        }
                    } catch (MalformedLineException ignored) {
                        // A malformed reply is not progress; the timeout still applies.
                    }
                }
            } catch (IOException ignored) {
                // EOF and socket failure both end this attempt.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                try {
                    replies.put(disconnected);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "cfe-master-acks");
        reader.setDaemon(true);
        reader.start();
        try {
            Map<String, Long> delivered = new HashMap<>();
            database.positions().forEach((source, p) -> delivered.put(source, p.delivered()));
            Map<String, Long> sent = new HashMap<>(delivered);
            long deadline = 0;
            while (!stopped) {
                long outstanding = outstanding(sent, delivered);
                boolean idle = outstanding == 0;
                int room = retry.window() - (int) outstanding;
                for (String source : database.positions().keySet()) {
                    if (room == 0) {
                        break;
                    }
                    for (Publish event : database.eventsAfter(source, sent.getOrDefault(source, 0L), room)) {
                        c.send(event);
                        if (event.sequence() <= highestSent.getOrDefault(source, 0L)) {
                            retriesSent.incrementAndGet();
                        }
                        highestSent.merge(source, event.sequence(), Math::max);
                        sent.put(source, event.sequence());
                        room--;
                    }
                }
                outstanding = outstanding(sent, delivered);
                // A newly nonempty window gets a full timeout, regardless of time spent idle.
                if (idle && outstanding > 0) {
                    deadline = System.nanoTime() + retry.ackTimeout().toNanos();
                }
                long wait = outstanding == 0 ? TimeUnit.MILLISECONDS.toNanos(20)
                        : Math.max(1, deadline - System.nanoTime());
                Object reply = replies.poll(wait, TimeUnit.NANOSECONDS);
                if (reply == disconnected) {
                    throw new IOException("master disconnected");
                }
                if (reply instanceof Ack ack && ack.through() > delivered.getOrDefault(ack.source(), 0L)
                        && ack.through() <= sent.getOrDefault(ack.source(), 0L)) {
                    long marked = database.markDelivered(ack.source(), ack.through());
                    delivered.put(ack.source(), marked);
                    failures = 0;
                    if (linkState.equals(StatusKeys.LINK_DOWN)) {
                        System.err.println("cfe: link to the master is UP again");
                    }
                    linkState = StatusKeys.LINK_UP;
                    deadline = System.nanoTime() + retry.ackTimeout().toNanos();
                }
                if (outstanding(sent, delivered) > 0 && System.nanoTime() - deadline >= 0) {
                    throw new IOException("master acknowledgement timed out");
                }
            }
        } finally {
            closeQuietly(c);
            connection = null;
            reader.interrupt();
            reader.join(1_000);
        }
    }

    private static long outstanding(Map<String, Long> sent, Map<String, Long> delivered) {
        return sent.entrySet().stream().mapToLong(e -> e.getValue() - delivered.getOrDefault(e.getKey(), 0L)).sum();
    }

    private void failedAttempt() throws InterruptedException {
        if (stopped) {
            return;
        }
        failures = Math.min(failures + 1, Integer.MAX_VALUE - 1);
        if (failures >= retry.linkDownAfter() && linkState.equals(StatusKeys.LINK_UP)) {
            linkState = StatusKeys.LINK_DOWN;
            System.err.println("cfe: link to the master DOWN; retaining all events and probing");
        }
        long cap = retry.maxBackoff().toMillis();
        long delay = retry.initialBackoff().toMillis();
        for (int i = 1; i < failures && delay < cap; i++) {
            delay = Math.min(cap, delay * 2);
        }
        delay = linkState.equals(StatusKeys.LINK_DOWN) ? cap : Math.min(cap, delay);
        long jitter = delay * retry.jitterPercent() / 100;
        Thread.sleep(delay + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitter + 1)));
    }

    private static void closeQuietly(LineConnection c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
    }
}
