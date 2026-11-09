package edu.rit.swen755.inventory.store;

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
 * The Store Agent: applies each sale to the Store-Local Inventory and appends its event to the
 * Outbox in one local SQLite transaction, whether or not the link is up, then resends every
 * unaccepted event to the CFE with its original identity until it is acknowledged.
 */
public final class StoreAgent implements RunningProcess {

    private final StoreAgentConfig config;
    private final RetrySettings retry;
    private final StoreDatabase database;
    /** Informational: sales recorded while the link was marked down, since this instance started. */
    private final AtomicLong salesWhileDown = new AtomicLong();
    /** Informational: events resent to the CFE since this instance started. */
    private final AtomicLong retriesSent = new AtomicLong();
    /** Informational: times the link was marked down since this instance started. */
    private final AtomicLong linkDownEpisodes = new AtomicLong();
    /** Informational: state of the link to the CFE. */
    private volatile String linkState = StatusKeys.LINK_UP;
    private LineServer sales;
    private LineServer control;

    private StoreAgent(StoreAgentConfig config, RetrySettings retry, StoreDatabase database) {
        this.config = config;
        this.retry = Objects.requireNonNull(retry, "retry");
        this.database = database;
    }

    /** Opens (or creates and seeds) the database, then binds both ports. */
    public static StoreAgent start(StoreAgentConfig config, RetrySettings retry)
            throws IOException, SQLException {
        StoreAgent store = new StoreAgent(config, retry, StoreDatabase.open(config.dataDir()));
        try {
            store.sales = LineServer.start("store-sales", config.salesPort(), store::serveSales);
            store.control = LineServer.control("store-control", config.controlPort(), store::answer);
        } catch (IOException e) {
            store.crash();
            throw e;
        }
        // TODO(Godson, #30): start the sender to the fault link on config.linkPort(): send every
        // Outbox event after acceptedThrough in sequence order with config.source(), at most
        // retry.window() in flight, resend on retry.ackTimeout() with backoff, mark linkState DOWN
        // after retry.linkDownAfter() failed attempts (counting linkDownEpisodes) and keep probing,
        // and mark Outbox rows accepted on each ACK|accepted.
        return store;
    }

    private void serveSales(LineConnection pointOfSale) throws IOException {
        // TODO(Godson, #30): for each SALE, add delta = -quantity to the product's stock, insert the
        // Outbox row (new event ID, next sequence, ISO-8601 UTC timestamp) and advance the sequence
        // in one transaction, then reply OK|eventId|sequence; reply ERR|reason to anything else.
        while (pointOfSale.readLine() != null) {
            pointOfSale.send(new ErrorReply("sales are not implemented yet"));
        }
    }

    private Message answer(Message request) throws SQLException {
        if (request instanceof StatusRequest) {
            Map<String, String> values = new LinkedHashMap<>(database.durableStatus());
            values.put(StatusKeys.Store.SALES_WHILE_DOWN, Long.toString(salesWhileDown.get()));
            values.put(StatusKeys.Store.RETRIES_SENT, Long.toString(retriesSent.get()));
            values.put(StatusKeys.Store.LINK_DOWN_EPISODES, Long.toString(linkDownEpisodes.get()));
            values.put(StatusKeys.Store.LINK_STATE, linkState);
            return new StatusReply(values);
        }
        return ErrorReply.of("the store does not accept " + request.getClass().getSimpleName());
    }

    @Override
    public String name() {
        return "store-agent";
    }

    @Override
    public Map<String, Integer> boundPorts() {
        Map<String, Integer> ports = new LinkedHashMap<>();
        ports.put("sales", sales.port());
        ports.put("control", control.port());
        return ports;
    }

    @Override
    public void crash() {
        // TODO(Godson, #30): also interrupt the sender thread and close its socket to the link.
        if (sales != null) {
            sales.close();
        }
        if (control != null) {
            control.close();
        }
        database.close();
    }

    @Override
    public void close() {
        // TODO(Godson, #30): stop the sender and let a sale in progress commit before the database
        // closes. Until then a clean stop is the same as a crash.
        crash();
    }
}
