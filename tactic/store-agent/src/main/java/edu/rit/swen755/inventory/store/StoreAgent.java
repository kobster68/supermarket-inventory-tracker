package edu.rit.swen755.inventory.store;

import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.LineCodec;
import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.Sale;
import edu.rit.swen755.inventory.protocol.SaleRecorded;
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

    private final StoreDatabase database;
    /** Informational: sales recorded while the link was marked down, since this instance started. */
    private final AtomicLong salesWhileDown = new AtomicLong();
    private OutboxSender sender;
    private LineServer sales;
    private LineServer control;

    private StoreAgent(StoreDatabase database) {
        this.database = database;
    }

    /** Opens (or creates and seeds) the database, starts the sender to the link, then binds both ports. */
    public static StoreAgent start(StoreAgentConfig config, RetrySettings retry)
            throws IOException, SQLException {
        return start(config, retry, false);
    }

    /**
     * {@link #start(StoreAgentConfig, RetrySettings)} with the test-only crash hook. With
     * {@code failSalesAfterStockUpdate} set, every sale transaction fails after its stock update and
     * before its Outbox insert, and the sale is answered {@code ERR|reason}. A test uses it to show
     * that neither change survives. It is off in every normal run and cannot be turned on from the
     * command line.
     */
    static StoreAgent start(StoreAgentConfig config, RetrySettings retry, boolean failSalesAfterStockUpdate)
            throws IOException, SQLException {
        Objects.requireNonNull(retry, "retry");
        StoreAgent store = new StoreAgent(
                StoreDatabase.open(config.dataDir(), config.source(), failSalesAfterStockUpdate));
        try {
            store.sender = OutboxSender.start(store.database, retry, config.linkPort(), config.source());
            store.sales = LineServer.start("store-sales", config.salesPort(), store::serveSales);
            store.control = LineServer.control("store-control", config.controlPort(), store::answer);
        } catch (IOException | SQLException e) {
            store.crash();
            throw e;
        }
        return store;
    }

    /** Answers each line on a point-of-sale connection, in order: {@code OK|eventId|sequence} or {@code ERR|reason}. */
    private void serveSales(LineConnection pointOfSale) throws IOException {
        String line;
        while ((line = pointOfSale.readLine()) != null) {
            pointOfSale.send(recordSale(line));
        }
    }

    private Message recordSale(String line) {
        Message request;
        try {
            request = LineCodec.decode(line);
        } catch (MalformedLineException e) {
            return ErrorReply.of(e.getMessage());
        }
        if (!(request instanceof Sale sale)) {
            return ErrorReply.of("the sales port accepts only SALE|product|quantity");
        }
        try {
            SaleRecorded recorded = database.recordSale(sale);
            if (sender.linkState().equals(StatusKeys.LINK_DOWN)) {
                salesWhileDown.incrementAndGet();
            }
            sender.saleRecorded();
            return recorded;
        } catch (SQLException e) {
            return ErrorReply.of("sale not recorded: " + e.getMessage());
        }
    }

    private Message answer(Message request) throws SQLException {
        if (request instanceof StatusRequest) {
            Map<String, String> values = new LinkedHashMap<>(database.durableStatus());
            values.put(StatusKeys.Store.SALES_WHILE_DOWN, Long.toString(salesWhileDown.get()));
            values.put(StatusKeys.Store.RETRIES_SENT, Long.toString(sender.retriesSent()));
            values.put(StatusKeys.Store.LINK_DOWN_EPISODES, Long.toString(sender.linkDownEpisodes()));
            values.put(StatusKeys.Store.LINK_STATE, sender.linkState());
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

    /** Stops at once, as a killed process would: the sender first, then both ports and the database. */
    @Override
    public void crash() {
        stop(false);
    }

    /** Stops in order: the sales port first, then the sender, the control port and the database. */
    @Override
    public void close() {
        stop(true);
    }

    /**
     * Stops the sales port and the sender in the order {@code salesFirst} gives, then the control
     * port, then the database. Closing the database waits for a transaction already committing, so a
     * sale or acknowledgement in progress either commits whole or not at all.
     *
     * <p>{@code close} stops the sales port first, so no new sale starts once shutdown has begun,
     * and only then the sender. {@code crash} stops the sender first, so the connection to the link
     * drops at the moment of the crash and nothing more is sent or acknowledged after it, as when
     * the process is killed; the ports go after.
     */
    private void stop(boolean salesFirst) {
        if (salesFirst && sales != null) {
            sales.close();
        }
        if (sender != null) {
            sender.stop();
        }
        if (!salesFirst && sales != null) {
            sales.close();
        }
        if (control != null) {
            control.close();
        }
        database.close();
    }
}
