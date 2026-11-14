package edu.rit.swen755.inventory.link;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import edu.rit.swen755.inventory.protocol.Cut;
import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.Ok;
import edu.rit.swen755.inventory.protocol.Restore;
import edu.rit.swen755.inventory.protocol.SetFault;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.Endpoints;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import edu.rit.swen755.inventory.protocol.net.LineServer;
import edu.rit.swen755.inventory.protocol.net.RunningProcess;

/**
 * The fault link: a TCP proxy between the Store Agent and the CFE. It forwards each line in both
 * directions subject to its current settings, so acknowledgements are dropped, duplicated and
 * delayed as well as events. It has no storage.
 *
 * <p>Each store connection gets its own connection to the CFE. In each direction, one thread
 * reads lines and applies the faults, and a second thread writes each line when its delay is over.
 * Thus a delay does not slow the reader and does not change the order of lines. The random source
 * uses the seed in the system property {@code inventory.link.seed} (default {@value #DEFAULT_SEED}).
 */
public final class FaultLink implements RunningProcess {

    static final long DEFAULT_SEED = 755L;

    private static final int CONNECT_TIMEOUT_MILLIS = 1_000;

    private final FaultLinkConfig config;
    private final Random random = new Random(Long.getLong("inventory.link.seed", DEFAULT_SEED));
    /** Name of every delayed-writer thread. */
    static final String WRITER_THREAD_NAME = "fault-link-writer";

    /** How long stopping a forwarding waits, in all, for its writers to leave. */
    private static final long WRITER_STOP_WAIT_MILLIS = 1_000;

    /** Every live forwarded connection, so that CUT and crash can stop them. */
    private final Set<Forwarding> forwardings = ConcurrentHashMap.newKeySet();
    private LineServer listener;
    private LineServer control;

    private volatile int dropPercent;
    private volatile int duplicatePercent;
    private volatile int delayMillis;
    private volatile boolean cut;

    private FaultLink(FaultLinkConfig config) {
        this.config = config;
    }

    /** Binds both ports and starts serving. */
    public static FaultLink start(FaultLinkConfig config) throws IOException {
        FaultLink link = new FaultLink(config);
        link.listener = LineServer.start("fault-link-listen", config.listenPort(), link::forward);
        
        try {
            link.control = LineServer.control("fault-link-control", config.controlPort(), link::answer);
        } catch (IOException e) {
            link.listener.close();
            throw e;
        }

        return link;
    }

    /** Serves one store connection: opens a CFE connection and forwards in both directions. */
    private void forward(LineConnection store) {
        if (cut) {
            return; // refused: the store's connection closes, and the store retries later
        }

        LineConnection cfe;
        
        try {
            cfe = new LineConnection(Endpoints.connect(config.cfePort(), CONNECT_TIMEOUT_MILLIS));
        } catch (IOException e) {
            return; // the CFE is down: the store's connection closes, and the store retries later
        }

        Forwarding forwarding = new Forwarding(store, cfe);
        forwardings.add(forwarding);
        
        // Check after add(): a CUT either sees this forwarding or is seen here.
        if (cut) {
            forwarding.stop();

            return;
        }

        forwarding.start();
    }

    /**
     * One store connection and its CFE connection, with a writer thread for each direction. When
     * either side ends, or on CUT or crash, {@link #stop()} closes both sockets and stops both
     * writers: closing a socket does not wake a writer that sleeps out a delay.
     */
    private final class Forwarding {

        private final LineConnection store;
        private final LineConnection cfe;
        private final BlockingQueue<DelayedLine> toCfe = new LinkedBlockingQueue<>();
        private final BlockingQueue<DelayedLine> toStore = new LinkedBlockingQueue<>();
        private final List<Thread> writers;
        private final AtomicBoolean stopped = new AtomicBoolean();

        Forwarding(LineConnection store, LineConnection cfe) {
            this.store = store;
            this.cfe = cfe;
            this.writers = List.of(
                    writer(toCfe, cfe),
                    writer(toStore, store));
        }

        /** Starts the writers and the CFE-to-store reader, then reads store-to-CFE on this thread. */
        void start() {
            writers.forEach(Thread::start);
            Thread back = new Thread(() -> pipe(cfe, toStore), "fault-link-cfe-to-store");
            back.setDaemon(true);
            back.start();
            pipe(store, toCfe);
        }

        /** Reads lines from {@code from}, applies the faults, and queues them for the writer. */
        private void pipe(LineConnection from, BlockingQueue<DelayedLine> queue) {
            try {
                String line;

                while ((line = from.readLine()) != null) {
                    if (chance(dropPercent)) {
                        continue;
                    }

                    long due = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
                    queue.add(new DelayedLine(line, due));

                    if (chance(duplicatePercent)) {
                        queue.add(new DelayedLine(line, due));
                    }
                }
            } catch (IOException e) {
                // The connection closed: the peer left, a CUT, or a crash.
            } finally {
                stop(); // the other direction then stops too
            }
        }

        private Thread writer(BlockingQueue<DelayedLine> queue, LineConnection to) {
            Thread t = new Thread(() -> writeWhenDue(queue, to), WRITER_THREAD_NAME);
            t.setDaemon(true);

            return t;
        }

        /** Writes each queued line when it is due, in queue order, until {@link #stop()}. */
        private void writeWhenDue(BlockingQueue<DelayedLine> queue, LineConnection to) {
            try {
                while (!stopped.get()) {
                    DelayedLine next = queue.take();
                    long wait = next.due() - System.nanoTime();

                    if (wait > 0) {
                        TimeUnit.NANOSECONDS.sleep(wait);
                    }

                    if (stopped.get()) {
                        return;
                    }

                    to.writeLine(next.line());
                }
            } catch (IOException | InterruptedException e) {
                // The connection closed. Lines still in the queue are lost, as on a broken network.
            }
        }

        /**
         * Closes both sockets, drops the queued lines, and interrupts both writers, then waits up to
         * {@link #WRITER_STOP_WAIT_MILLIS} for them to leave. Safe to call more than once.
         */
        void stop() {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }

            forwardings.remove(this);
            writers.forEach(Thread::interrupt);
            closeQuietly(store);
            closeQuietly(cfe);
            toCfe.clear();
            toStore.clear();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WRITER_STOP_WAIT_MILLIS);

            for (Thread writer : writers) {
                long remaining = deadline - System.nanoTime();

                if (writer == Thread.currentThread() || remaining <= 0) {
                    continue;
                }

                try {
                    TimeUnit.NANOSECONDS.timedJoin(writer, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    return;
                }
            }
        }
    }

    /** Live writer threads of this link, for tests. */
    long liveWriterCount() {
        return forwardings.stream().flatMap(f -> f.writers.stream()).filter(Thread::isAlive).count();
    }

    /** A line and the {@link System#nanoTime()} at which it may be written. */
    private record DelayedLine(String line, long due) {
    }

    /** True for {@code percent} of the calls, on average. */
    private boolean chance(int percent) {
        return percent > 0 && random.nextInt(100) < percent;
    }

    private static void closeQuietly(LineConnection connection) {
        try {
            connection.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private void stopAll() {
        forwardings.forEach(Forwarding::stop);
    }

    private Message answer(Message request) {
        return switch (request) {
            case StatusRequest s -> status();

            case SetFault s -> {
                switch (s.setting()) {
                    case DROP -> dropPercent = s.value();
                    case DUPLICATE -> duplicatePercent = s.value();
                    case DELAY -> delayMillis = s.value();
                }

                yield new Ok();
            }

            case Cut c -> {
                cut = true;
                stopAll();
                yield new Ok();
            }

            case Restore r -> {
                cut = false;
                yield new Ok();
            }

            default -> ErrorReply.of("the fault link does not accept " + request.getClass().getSimpleName());
        };
    }

    private StatusReply status() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(StatusKeys.FaultLink.DROP, Integer.toString(dropPercent));
        values.put(StatusKeys.FaultLink.DUPLICATE, Integer.toString(duplicatePercent));
        values.put(StatusKeys.FaultLink.DELAY, Integer.toString(delayMillis));
        values.put(StatusKeys.FaultLink.CUT, Boolean.toString(cut));

        return new StatusReply(values);
    }

    @Override
    public String name() {
        return "fault-link";
    }

    @Override
    public Map<String, Integer> boundPorts() {
        Map<String, Integer> ports = new LinkedHashMap<>();
        ports.put("listen", listener.port());
        ports.put("control", control.port());

        return ports;
    }

    @Override
    public void crash() {
        stopAll();

        if (listener != null) {
            listener.close();
        }
        
        if (control != null) {
            control.close();
        }
    }

    @Override
    public void close() {
        // The link has no storage, so a clean stop is the same as a crash.
        crash();
    }
}
