package edu.rit.swen755.inventory.master;

import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.Message;
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
        MasterInventory master = new MasterInventory(config, MasterDatabase.open(config.dataDir(), config.source()));
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
        // TODO(Mike, #32): for each PUBLISH, apply it if it is the next contiguous sequence for its
        // source (stock change, applied-event record and cursor in one transaction); treat an event
        // already applied (by event ID, or by sequence at or below the cursor) as a no-op; refuse an
        // event beyond a gap. After the commit, reply ACK|applied|source|appliedThrough in every
        // case. Until then lines are read and discarded.
        while (cfe.readLine() != null) {
            // discarded
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
        // TODO(Mike, #32): stop accepting events and let an apply in progress commit before the
        // database closes. Until then a clean stop is the same as a crash.
        crash();
    }
}
