package edu.rit.swen755.inventory.cfe;

import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import edu.rit.swen755.inventory.protocol.net.LineServer;
import edu.rit.swen755.inventory.protocol.net.RunningProcess;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Communications Front-End: persists each event before acknowledging it {@code accepted},
 * keeps the event log, and resends to the master until each event is acknowledged {@code applied}.
 */
public final class Cfe implements RunningProcess {

    private final CfeConfig config;
    private final RetrySettings retry;
    private final CfeDatabase database;
    /** Informational: duplicates re-acknowledged without a new row since this instance started. */
    private final AtomicLong duplicatesReacked = new AtomicLong();
    /** Informational: events resent to the master since this instance started. */
    private final AtomicLong retriesSent = new AtomicLong();
    /** Informational: state of the link to the master. */
    private volatile String linkState = StatusKeys.LINK_UP;
    private LineServer listener;
    private LineServer control;

    private Cfe(CfeConfig config, RetrySettings retry, CfeDatabase database) {
        this.config = config;
        this.retry = Objects.requireNonNull(retry, "retry");
        this.database = database;
    }

    /** Opens (or creates) the database, then binds both ports. */
    public static Cfe start(CfeConfig config, RetrySettings retry) throws IOException, SQLException {
        Cfe cfe = new Cfe(config, retry, CfeDatabase.open(config.dataDir()));
        try {
            cfe.listener = LineServer.start("cfe-listen", config.listenPort(), cfe::serveStore);
            cfe.control = LineServer.control("cfe-control", config.controlPort(), cfe::answer);
        } catch (IOException e) {
            cfe.crash();
            throw e;
        }
        // TODO(Kobe, #31): start the sender to the master on config.masterPort(): send every event
        // after deliveredThrough in sequence order, at most retry.window() in flight, resend on
        // retry.ackTimeout() with backoff, mark linkState DOWN after retry.linkDownAfter() failed
        // attempts, and advance deliveredThrough on each ACK|applied.
        return cfe;
    }

    private void serveStore(LineConnection store) throws IOException {
        // TODO(Kobe, #31): for each PUBLISH, store it only if it is the next contiguous sequence for
        // its source (event log row and acceptedThrough in one transaction); re-acknowledge an event
        // already held without a new row; refuse an event beyond a gap. After the commit, reply
        // ACK|accepted|source|acceptedThrough in every case. Until then lines are read and discarded.
        while (store.readLine() != null) {
            // discarded
        }
    }

    private Message answer(Message request) throws SQLException {
        if (request instanceof StatusRequest) {
            Map<String, String> values = new LinkedHashMap<>(database.durableStatus(config.source()));
            values.put(StatusKeys.Cfe.DUPLICATES_REACKED, Long.toString(duplicatesReacked.get()));
            values.put(StatusKeys.Cfe.RETRIES_SENT, Long.toString(retriesSent.get()));
            values.put(StatusKeys.Cfe.LINK_STATE, linkState);
            return new StatusReply(values);
        }
        return ErrorReply.of("the CFE does not accept " + request.getClass().getSimpleName());
    }

    @Override
    public String name() {
        return "cfe";
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
        // TODO(Kobe, #31): also interrupt the sender thread and close its socket to the master.
        if (listener != null) {
            listener.close();
        }
        if (control != null) {
            control.close();
        }
        database.close();
    }

    @Override
    public void close() {
        // TODO(Kobe, #31): stop the sender and let a store in progress commit before the database
        // closes. Until then a clean stop is the same as a crash.
        crash();
    }
}
