package edu.rit.swen755.inventory.link;

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
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The fault link: a TCP proxy between the Store Agent and the CFE. It forwards each line in both
 * directions subject to its current settings, so acknowledgements are dropped, duplicated and
 * delayed as well as events. It has no storage.
 *
 * <p>Each store connection gets its own connection to the CFE. In each direction one thread reads
 * lines and decides their fate (drop, forward once, forward twice) and a second thread writes them
 * when their delay is over, so a delay holds lines back without slowing the reader and never
 * changes their order. The drop and duplicate decisions come from one {@link Random} seeded with
 * the system property {@code inventory.link.seed} (default {@value #DEFAULT_SEED}).
 */
public final class FaultLink implements RunningProcess {

    static final long DEFAULT_SEED = 755L;

    private static final int CONNECT_TIMEOUT_MILLIS = 1_000;

    private final FaultLinkConfig config;
    private final Random random = new Random(Long.getLong("inventory.link.seed", DEFAULT_SEED));
    private final Set<Bridge> bridges = ConcurrentHashMap.newKeySet();
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

    private void forward(LineConnection store) {
        if (cut) {
            return; // refused: closing the connection is the store's signal to retry later
        }
        LineConnection cfe;
        try {
            cfe = new LineConnection(Endpoints.connect(config.cfePort(), CONNECT_TIMEOUT_MILLIS));
        } catch (IOException e) {
            return; // the CFE is down: close the store's connection so it retries later
        }
        Bridge bridge = new Bridge(store, cfe);
        bridges.add(bridge);
        try {
            if (!cut) { // a CUT between the first check and add() closed every bridge but this one
                bridge.run();
            }
        } finally {
            bridges.remove(bridge);
            bridge.close();
        }
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
                bridges.forEach(Bridge::close);
                yield new Ok();
            }
            case Restore r -> {
                cut = false;
                yield new Ok();
            }
            default -> ErrorReply.of("the fault link does not accept " + request.getClass().getSimpleName());
        };
    }

    /** True for {@code percent} out of every 100 calls, on average. */
    private boolean roll(int percent) {
        return percent > 0 && random.nextInt(100) < percent;
    }

    /** A line and the time ({@link System#nanoTime()}) it may be written. */
    private record Timed(long due, String line) {
    }

    /** One store connection and the CFE connection opened for it. Closing either side closes both. */
    private final class Bridge implements AutoCloseable {

        private final LineConnection store;
        private final LineConnection cfe;
        private final Set<Thread> threads = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean closed = new AtomicBoolean();

        Bridge(LineConnection store, LineConnection cfe) {
            this.store = store;
            this.cfe = cfe;
        }

        /** Forwards in both directions until either side closes; the store-to-CFE reader runs here. */
        void run() {
            spawn("fault-link-down", () -> pump(cfe, store));
            pump(store, cfe);
        }

        private void pump(LineConnection from, LineConnection to) {
            BlockingQueue<Timed> queue = new LinkedBlockingQueue<>();
            spawn("fault-link-writer", () -> deliver(queue, to));
            try {
                for (String line; (line = from.readLine()) != null; ) {
                    if (roll(dropPercent)) {
                        continue;
                    }
                    Timed timed = new Timed(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis), line);
                    queue.add(timed);
                    if (roll(duplicatePercent)) {
                        queue.add(timed);
                    }
                }
            } catch (IOException e) {
                // The socket closed: the peer left, a CUT, or a crash.
            } finally {
                close();
            }
        }

        /** Writes each line once its delay is over, in the order the reader queued them. */
        private void deliver(BlockingQueue<Timed> queue, LineConnection to) {
            try {
                while (true) {
                    Timed timed = queue.take();
                    long wait = timed.due() - System.nanoTime();
                    if (wait > 0) {
                        TimeUnit.NANOSECONDS.sleep(wait);
                    }
                    to.writeLine(timed.line());
                }
            } catch (InterruptedException | IOException e) {
                // Closed: lines still queued are lost, as on a broken network link.
            } finally {
                close();
            }
        }

        private void spawn(String name, Runnable body) {
            Thread t = new Thread(body, name);
            t.setDaemon(true);
            threads.add(t);
            t.start();
            if (closed.get()) {
                t.interrupt(); // close() ran before the start, when an interrupt has no effect
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            closeQuietly(store);
            closeQuietly(cfe);
            for (Thread t : threads) {
                if (t != Thread.currentThread()) {
                    t.interrupt();
                }
            }
        }

        private static void closeQuietly(LineConnection c) {
            try {
                c.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }
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
        bridges.forEach(Bridge::close);
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
