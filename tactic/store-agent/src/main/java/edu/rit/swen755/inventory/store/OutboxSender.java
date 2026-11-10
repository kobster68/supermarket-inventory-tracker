package edu.rit.swen755.inventory.store;

import edu.rit.swen755.inventory.protocol.Ack;
import edu.rit.swen755.inventory.protocol.AckKind;
import edu.rit.swen755.inventory.protocol.LineCodec;
import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.Publish;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.net.Endpoints;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import java.io.IOException;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends the Outbox to the CFE (through the fault link) and resends it until it is accepted (Retry).
 *
 * <p>One attempt is one connection: it sends every event after accepted-through (the highest
 * sequence the CFE has accepted) in sequence order, never more than {@link RetrySettings#window()}
 * beyond it, and keeps sending as acknowledgements move accepted-through on. An acknowledgement
 * {@code ACK|accepted} is cumulative: it marks every event up to its {@code through} accepted in
 * SQLite. A stale one (below accepted-through) is ignored, so accepted-through never moves
 * backwards. The attempt fails when no acknowledgement moves accepted-through within
 * {@link RetrySettings#ackTimeout()} while events are in flight, or when the connection cannot be
 * opened or is lost. The sender then waits the backoff (from {@link RetrySettings#initialBackoff()},
 * doubling up to {@link RetrySettings#maxBackoff()}, plus jitter), reconnects and resends everything
 * after accepted-through, with the events' original identities. After
 * {@link RetrySettings#linkDownAfter()} consecutive failed attempts it marks the link down and keeps
 * probing at {@link RetrySettings#maxBackoff()}; every event stays in the Outbox. Only an
 * acknowledgement that moves accepted-through marks the link up and resets the count.
 *
 * <p>An acknowledgement that repeats accepted-through while later events are in flight (the CFE
 * refusing an event beyond a gap, or a duplicate from the link) is ignored like a stale one. It
 * does not trigger a resend; the acknowledgement timeout does. Resending the whole window on every
 * such acknowledgement would resend it once per refused event, and once per duplicated
 * acknowledgement. Nor does it count as progress: a CFE that answers but refuses every event leaves
 * the attempts failing, so the link is marked down just as when the CFE is unreachable.
 *
 * <p>On start it reads accepted-through from SQLite and sends from the event after
 * it; the CFE's contiguity rule and cumulative acknowledgements realign both sides.
 */
final class OutboxSender {

    private final StoreDatabase database;
    private final RetrySettings retry;
    private final int linkPort;
    private final String source;
    private final Thread thread;

    /** Guards {@link #acceptedThrough}, {@link #failures} and {@link #signalled}. */
    private final Object monitor = new Object();
    private long acceptedThrough;
    private int failures;
    private boolean signalled;

    private final AtomicLong retriesSent = new AtomicLong();
    private final AtomicLong linkDownEpisodes = new AtomicLong();
    /** Written under {@link #monitor}, read without it. */
    private volatile String linkState = StatusKeys.LINK_UP;
    private volatile boolean stopped;
    private volatile LineConnection connection;
    /** Highest sequence this instance has sent; a send at or below it is a resend. Sender thread only. */
    private long highestSent;

    private OutboxSender(StoreDatabase database, RetrySettings retry, int linkPort, String source,
            long acceptedThrough) {
        this.database = database;
        this.retry = retry;
        this.linkPort = linkPort;
        this.source = source;
        this.acceptedThrough = acceptedThrough;
        this.thread = new Thread(this::run, "store-sender");
        this.thread.setDaemon(true);
    }

    /** Starts sending from the Outbox's accepted-through plus one. */
    static OutboxSender start(StoreDatabase database, RetrySettings retry, int linkPort, String source)
            throws SQLException {
        OutboxSender sender = new OutboxSender(database, retry, linkPort, source, database.acceptedThrough());
        sender.thread.start();
        return sender;
    }

    /** A sale has committed: there is a new event to send. */
    void saleRecorded() {
        signal();
    }

    String linkState() {
        return linkState;
    }

    long retriesSent() {
        return retriesSent.get();
    }

    long linkDownEpisodes() {
        return linkDownEpisodes.get();
    }

    /** Stops at once: closes the connection to the link and waits briefly for the sender to leave. */
    void stop() {
        stopped = true;
        LineConnection c = connection;
        if (c != null) {
            closeQuietly(c);
        }
        thread.interrupt();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() {
        while (!stopped) {
            try {
                if (pending()) {
                    attempt();
                } else {
                    awaitSignal(retry.ackTimeout().toMillis());
                }
            } catch (InterruptedException e) {
                return;
            } catch (SQLException | RuntimeException e) {
                if (stopped) {
                    return;
                }
                // Every event stays in the Outbox; count it as a failed attempt and try again.
                System.err.println("store-agent: sending from the Outbox failed: " + e);
                try {
                    failAttemptAndBackOff();
                } catch (InterruptedException stop) {
                    return;
                }
            }
        }
    }

    private boolean pending() throws SQLException {
        long created = database.created();
        synchronized (monitor) {
            return created > acceptedThrough;
        }
    }

    /** One connection: send and resend until accepted-through stops moving, the link fails, or stop. */
    private void attempt() throws InterruptedException, SQLException {
        LineConnection c;
        try {
            c = new LineConnection(Endpoints.connect(linkPort, (int) retry.ackTimeout().toMillis()));
        } catch (IOException e) {
            failAttemptAndBackOff();
            return;
        }
        connection = c;
        // The reader blocks in readLine with no socket read timeout: the acknowledgement timeout below
        // is its only bound, and closing the connection when the attempt ends is what unblocks it.
        Thread reader = new Thread(() -> readAcks(c), "store-sender-acks");
        reader.setDaemon(true);
        reader.start();
        try {
            long next = acceptedThrough() + 1;
            long lastAcceptedThrough = acceptedThrough();
            long deadline = System.nanoTime() + retry.ackTimeout().toNanos();
            while (!stopped) {
                long acceptedThrough = acceptedThrough();
                if (acceptedThrough > lastAcceptedThrough) {
                    lastAcceptedThrough = acceptedThrough;
                    deadline = System.nanoTime() + retry.ackTimeout().toNanos();
                }
                next = Math.max(next, acceptedThrough + 1);
                long room = acceptedThrough + retry.window() - (next - 1);
                if (room > 0) {
                    for (Publish event : database.eventsAfter(next - 1, (int) room)) {
                        c.send(event);
                        if (event.sequence() <= highestSent) {
                            retriesSent.incrementAndGet();
                        } else {
                            highestSent = event.sequence();
                        }
                        next = event.sequence() + 1;
                    }
                }
                boolean inFlight = next - 1 > acceptedThrough;
                if (!inFlight) {
                    deadline = System.nanoTime() + retry.ackTimeout().toNanos();
                }
                long waitMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                awaitSignal(waitMillis);
                if (!reader.isAlive()) {
                    if (pending()) {
                        failAttemptAndBackOff();
                    }
                    return;
                }
                if (inFlight && acceptedThrough() == acceptedThrough && System.nanoTime() - deadline >= 0) {
                    failAttemptAndBackOff();
                    return;
                }
            }
        } catch (IOException e) {
            failAttemptAndBackOff();
        } finally {
            connection = null;
            closeQuietly(c);
            reader.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    /** Reads acknowledgements until the connection closes; wakes the sender on each one and at the end. */
    private void readAcks(LineConnection c) {
        try {
            String line;
            while ((line = c.readLine()) != null) {
                if (decodeOrNull(line) instanceof Ack ack && ack.kind() == AckKind.ACCEPTED && ack.source().equals(source)) {
                    acknowledged(ack.through());
                }
            }
        } catch (IOException e) {
            // The connection closed or failed; the sender decides what follows.
        } catch (SQLException | RuntimeException e) {
            if (!stopped) {
                System.err.println("store-agent: recording an acknowledgement failed: " + e);
            }
        } finally {
            signal();
        }
    }

    /**
     * An acknowledgement ahead of accepted-through moves it, resets the failure count and marks the
     * link up. One at or below it (stale, repeated or a refusal) changes nothing.
     */
    private void acknowledged(long through) throws SQLException {
        if (through <= acceptedThrough()) {
            return;
        }
        // Written outside the monitor; marking rows accepted only ever moves accepted-through forward.
        long marked = database.markAcceptedThrough(through);
        synchronized (monitor) {
            if (marked <= acceptedThrough) {
                return;
            }
            acceptedThrough = marked;
            failures = 0;
            if (linkState.equals(StatusKeys.LINK_DOWN)) {
                linkState = StatusKeys.LINK_UP;
                System.err.println("store-agent: link to the CFE is UP again; accepted through "
                        + acceptedThrough);
            }
            signalled = true;
            monitor.notifyAll();
        }
    }

    /** Counts a failed attempt, marks the link down at the retry limit, then waits the backoff. */
    private void failAttemptAndBackOff() throws InterruptedException {
        if (stopped) {
            return;
        }
        int attempts;
        synchronized (monitor) {
            attempts = ++failures;
            if (attempts >= retry.linkDownAfter() && linkState.equals(StatusKeys.LINK_UP)) {
                linkState = StatusKeys.LINK_DOWN;
                linkDownEpisodes.incrementAndGet();
                System.err.println("store-agent: link to the CFE marked DOWN after " + attempts
                        + " consecutive failed attempts; every event stays in the Outbox and the store"
                        + " keeps probing; accepted through " + acceptedThrough);
            }
        }
        Thread.sleep(backoffMillis(attempts));
    }

    /**
     * {@code initialBackoff * 2^(attempts-1)}, at most {@code maxBackoff}, and {@code maxBackoff}
     * while the link is down; lengthened by up to {@code jitterPercent}.
     */
    private long backoffMillis(int attempts) {
        long max = retry.maxBackoff().toMillis();
        long base = retry.initialBackoff().toMillis();
        for (int i = 1; i < attempts && base < max; i++) {
            base *= 2;
        }
        base = linkState.equals(StatusKeys.LINK_DOWN) ? max : Math.min(base, max);
        long jitter = base * retry.jitterPercent() / 100;
        return base + (jitter == 0 ? 0 : ThreadLocalRandom.current().nextLong(jitter + 1));
    }

    private long acceptedThrough() {
        synchronized (monitor) {
            return acceptedThrough;
        }
    }

    /**
     * The message on {@code line}, or {@code null} if the line is malformed; every reader in this
     * module skips a malformed line rather than failing on it.
     */
    static Message decodeOrNull(String line) {
        try {
            return LineCodec.decode(line);
        } catch (MalformedLineException e) {
            return null;
        }
    }

    private void signal() {
        synchronized (monitor) {
            signalled = true;
            monitor.notifyAll();
        }
    }

    private void awaitSignal(long millis) throws InterruptedException {
        synchronized (monitor) {
            if (!signalled) {
                monitor.wait(millis);
            }
            signalled = false;
        }
    }

    private static void closeQuietly(LineConnection c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
