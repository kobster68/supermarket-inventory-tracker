package edu.rit.swen755.inventory.link;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
    /** Every open connection, on both sides, so that CUT and crash can close them. */
    private final Set<LineConnection> open = ConcurrentHashMap.newKeySet();
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

        open.add(store);
        open.add(cfe);
        
        // Check after add(): a CUT either sees these connections in "open" or is seen here.
        if (cut) {
            closeBoth(store, cfe);

            return;
        }

        Thread back = new Thread(() -> pipe(cfe, store), "fault-link-cfe-to-store");
        back.setDaemon(true);
        back.start();
        pipe(store, cfe);
    }

    /** Reads lines from {@code from}, applies the faults, and queues them for {@code to}. */
    private void pipe(LineConnection from, LineConnection to) {
        BlockingQueue<DelayedLine> queue = new LinkedBlockingQueue<>();
        Thread writer = new Thread(() -> writeWhenDue(queue, to), "fault-link-writer");
        writer.setDaemon(true);
        writer.start();

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
            closeBoth(from, to); // the other direction then stops too
            writer.interrupt(); // a closed socket does not wake a writer that waits for a delay
        }
    }

    /** Writes each queued line when it is due, in queue order, until the pipe interrupts it. */
    private static void writeWhenDue(BlockingQueue<DelayedLine> queue, LineConnection to) {
        try {
            while (true) {
                DelayedLine next = queue.take();
                long wait = next.due() - System.nanoTime();

                if (wait > 0) {
                    TimeUnit.NANOSECONDS.sleep(wait);
                }

                to.writeLine(next.line());
            }
        } catch (IOException | InterruptedException e) {
            // The connection closed. Lines still in the queue are lost, as on a broken network.
        }
    }

    /** A line and the {@link System#nanoTime()} at which it may be written. */
    private record DelayedLine(String line, long due) {
    }

    /** True for {@code percent} of the calls, on average. */
    private boolean chance(int percent) {
        return percent > 0 && random.nextInt(100) < percent;
    }

    private void closeBoth(LineConnection a, LineConnection b) {
        close(a);
        close(b);
    }

    private void close(LineConnection connection) {
        open.remove(connection);
       
        try {
            connection.close();
        } catch (IOException ignored) {
            // Already closed.
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
                open.forEach(this::close);
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
        open.forEach(this::close);

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
