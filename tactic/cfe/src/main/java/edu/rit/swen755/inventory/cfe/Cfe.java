package edu.rit.swen755.inventory.cfe;

import edu.rit.swen755.inventory.protocol.Ack;
import edu.rit.swen755.inventory.protocol.AckKind;
import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.LineCodec;
import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.Publish;
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
    private final CfeDatabase database;
    /** Informational: duplicates re-acknowledged without a new row since this instance started. */
    private final AtomicLong duplicatesReacked = new AtomicLong();
    private final MasterSender sender;
    private LineServer listener;
    private LineServer control;

    private Cfe(CfeConfig config, RetrySettings retry, CfeDatabase database) {
        this.config = config;
        this.database = database;
        this.sender = new MasterSender(database, retry, config.masterPort());
    }

    /** Opens (or creates) the database, then binds both ports. */
    public static Cfe start(CfeConfig config, RetrySettings retry) throws IOException, SQLException {
        Objects.requireNonNull(retry, "retry");
        Cfe cfe = new Cfe(config, retry, CfeDatabase.open(config.dataDir()));
        try {
            cfe.listener = LineServer.start("cfe-listen", config.listenPort(), cfe::serveStore);
            cfe.control = LineServer.control("cfe-control", config.controlPort(), cfe::answer);
            cfe.sender.start();
        } catch (IOException e) {
            cfe.crash();
            throw e;
        }
        return cfe;
    }

    private void serveStore(LineConnection store) throws IOException {
        String line;
        while ((line = store.readLine()) != null) {
            Message request;
            try {
                request = LineCodec.decode(line);
            } catch (MalformedLineException e) {
                store.send(ErrorReply.of(e.getMessage()));
                continue;
            }
            if (!(request instanceof Publish event)) {
                store.send(ErrorReply.of("the CFE accepts only PUBLISH on this port"));
                continue;
            }
            try {
                CfeDatabase.Acceptance accepted = database.accept(event);
                if (accepted.duplicate()) {
                    duplicatesReacked.incrementAndGet();
                }
                store.send(new Ack(AckKind.ACCEPTED, event.source(), accepted.through()));
            } catch (SQLException e) {
                store.send(ErrorReply.of("event not accepted: " + e.getMessage()));
            }
        }
    }

    private Message answer(Message request) throws SQLException {
        if (request instanceof StatusRequest) {
            Map<String, String> values = new LinkedHashMap<>(database.durableStatus(config.source()));
            values.put(StatusKeys.Cfe.DUPLICATES_REACKED, Long.toString(duplicatesReacked.get()));
            values.put(StatusKeys.Cfe.RETRIES_SENT, Long.toString(sender.retriesSent()));
            values.put(StatusKeys.Cfe.LINK_STATE, sender.linkState());
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
        stop(false);
    }

    private void stop(boolean ingressFirst) {
        if (ingressFirst && listener != null) {
            listener.close();
        }
        if (ingressFirst) {
            sender.stop();
        } else {
            sender.crash();
        }
        if (!ingressFirst && listener != null) {
            listener.close();
        }
        if (control != null) {
            control.close();
        }
        database.close();
    }

    @Override
    public void close() {
        // Stop ingress first; the synchronized database close waits for an acceptance to finish.
        stop(true);
    }
}
