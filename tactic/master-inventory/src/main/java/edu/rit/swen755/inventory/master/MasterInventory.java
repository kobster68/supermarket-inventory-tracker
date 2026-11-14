package edu.rit.swen755.inventory.master;

import edu.rit.swen755.inventory.protocol.Ack;
import edu.rit.swen755.inventory.protocol.AckKind;
import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.LineCodec;
import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.Publish;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Central Master Inventory: applies each event exactly once. The stock change, the
 * applied-event record and the applied-through cursor commit in one transaction, and the
 * {@code ACK|applied} is sent only after that commit.
 */
public final class MasterInventory implements RunningProcess {

    private final MasterInventoryConfig config;
    private final MasterDatabase database;
    /** Informational: duplicates recognized and not applied since this instance started. */
    private final AtomicLong duplicatesIgnored = new AtomicLong();
    private LineServer listener;
    private LineServer control;

    private MasterInventory(MasterInventoryConfig config, MasterDatabase database) {
        this.config = config;
        this.database = database;
    }

    /** Opens (or creates and seeds) the database, then binds both ports. */
    public static MasterInventory start(MasterInventoryConfig config) throws IOException, SQLException {
        return start(config, false);
    }

    /** Test entry point enabling a failure after the stock update and before the applied record. */
    static MasterInventory start(MasterInventoryConfig config, boolean failAfterStockUpdate)
            throws IOException, SQLException {
        MasterInventory master = new MasterInventory(config,
                MasterDatabase.open(config.dataDir(), config.source(), failAfterStockUpdate));
        try {
            master.listener = LineServer.start("master-listen", config.listenPort(), master::serveCfe);
            master.control = LineServer.control("master-control", config.controlPort(), master::answer);
        } catch (IOException e) {
            master.crash();
            throw e;
        }
        return master;
    }

    private void serveCfe(LineConnection cfe) throws IOException {
        String line;
        while ((line = cfe.readLine()) != null) {
            cfe.send(apply(line));
        }
    }

    private Message apply(String line) {
        Message request;
        try {
            request = LineCodec.decode(line);
        } catch (MalformedLineException e) {
            return ErrorReply.of(e.getMessage());
        }
        if (!(request instanceof Publish event)) {
            return ErrorReply.of("the master event port accepts only PUBLISH messages");
        }
        try {
            MasterDatabase.ApplyResult result = database.apply(event);
            if (result.outcome() == MasterDatabase.ApplyOutcome.DUPLICATE) {
                duplicatesIgnored.incrementAndGet();
            }
            return new Ack(AckKind.APPLIED, event.source(), result.appliedThrough());
        } catch (SQLException | IllegalArgumentException | ArithmeticException e) {
            return ErrorReply.of("event processing failed: " + e.getMessage());
        }
    }

    private Message answer(Message request) throws SQLException {
        if (request instanceof StatusRequest) {
            Map<String, String> values = new LinkedHashMap<>(database.durableStatus(config.source()));
            values.put(StatusKeys.Master.DUPLICATES_IGNORED, Long.toString(duplicatesIgnored.get()));
            return new StatusReply(values);
        }
        return ErrorReply.of("the master does not accept " + request.getClass().getSimpleName());
    }

    @Override
    public String name() {
        return "master-inventory";
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
        // The listeners stop new requests. Database close waits for an active apply to finish.
        crash();
    }
}
