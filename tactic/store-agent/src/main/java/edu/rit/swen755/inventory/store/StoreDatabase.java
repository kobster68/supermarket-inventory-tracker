package edu.rit.swen755.inventory.store;

import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.Publish;
import edu.rit.swen755.inventory.protocol.Sale;
import edu.rit.swen755.inventory.protocol.SaleRecorded;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The store's SQLite file: the Store-Local Inventory (product to quantity), the Outbox, and the next
 * sequence number. Every durable STATUS key is read from these tables on request.
 */
final class StoreDatabase implements AutoCloseable {

    static final String FILE_NAME = "store-agent.db";

    /** Schema version stored in {@code PRAGMA user_version}; 0 means the file was just created. */
    private static final int SCHEMA_VERSION = 1;

    private static final String CREATED_SQL = "SELECT next_sequence - 1 FROM sequence_counter";
    private static final String ACCEPTED_THROUGH_SQL =
            "SELECT COALESCE(MAX(sequence), 0) FROM outbox WHERE accepted = 1";

    private final Connection connection;
    /** This store's source identifier, written into every Outbox row. */
    private final String source;
    /** Test-only crash hook: fail every sale after its stock update, before its Outbox insert. */
    private final boolean failSalesAfterStockUpdate;

    private StoreDatabase(Connection connection, String source, boolean failSalesAfterStockUpdate) {
        this.connection = connection;
        this.source = source;
        this.failSalesAfterStockUpdate = failSalesAfterStockUpdate;
    }

    /**
     * Opens (creating if needed) the database in {@code dataDir}. A new file gets its tables and the
     * initial stock of every product in one transaction; an existing file is reopened as it is and
     * never re-seeded.
     *
     * @param failSalesAfterStockUpdate the test-only crash hook (see {@link #recordSale}); false in
     *                                  every normal run
     */
    static StoreDatabase open(Path dataDir, String source, boolean failSalesAfterStockUpdate) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve(FILE_NAME));
        try {
            createAndSeedOnce(c);
            return new StoreDatabase(c, source, failSalesAfterStockUpdate);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }

    private static void createAndSeedOnce(Connection c) throws SQLException {
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            if (userVersion(s) == 0) {
                s.executeUpdate("CREATE TABLE stock (product TEXT PRIMARY KEY, quantity INTEGER NOT NULL)");
                // accepted is 1 once the CFE has acknowledged this sequence (acks are cumulative).
                s.executeUpdate("CREATE TABLE outbox (event_id TEXT PRIMARY KEY, source TEXT NOT NULL,"
                        + " sequence INTEGER NOT NULL UNIQUE, product TEXT NOT NULL,"
                        + " delta INTEGER NOT NULL, timestamp TEXT NOT NULL,"
                        + " accepted INTEGER NOT NULL DEFAULT 0)");
                s.executeUpdate("CREATE TABLE sequence_counter (next_sequence INTEGER NOT NULL)");
                s.executeUpdate("INSERT INTO sequence_counter (next_sequence) VALUES (1)");
                try (PreparedStatement seed = c.prepareStatement(
                        "INSERT INTO stock (product, quantity) VALUES (?, ?)")) {
                    for (Product p : Product.values()) {
                        seed.setString(1, p.name());
                        seed.setLong(2, Product.INITIAL_STOCK);
                        seed.executeUpdate();
                    }
                }
                s.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
            }
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        }
    }

    private static int userVersion(Statement s) throws SQLException {
        try (ResultSet rs = s.executeQuery("PRAGMA user_version")) {
            if (!rs.next()) {
                throw new SQLException("PRAGMA user_version returned no row");
            }
            return rs.getInt(1);
        }
    }

    /**
     * Records one sale in one transaction: adds {@code -quantity} to the product's stock, appends
     * the Outbox row with a new event ID, the next sequence number and the current time, and
     * advances the sequence. Either all three commit or none does.
     *
     * <p>With the test-only crash hook on, the transaction fails right after the stock update and
     * before the Outbox insert, and is rolled back.
     */
    synchronized SaleRecorded recordSale(Sale sale) throws SQLException {
        UUID eventId = UUID.randomUUID();
        long delta = -sale.quantity();
        try {
            try (PreparedStatement stock = connection.prepareStatement(
                    "UPDATE stock SET quantity = quantity + ? WHERE product = ?")) {
                stock.setLong(1, delta);
                stock.setString(2, sale.product().name());
                if (stock.executeUpdate() != 1) {
                    throw new SQLException("no stock row for " + sale.product().name());
                }
            }
            if (failSalesAfterStockUpdate) {
                throw new SQLException("test crash hook: failed after the stock update, before the Outbox insert");
            }
            long sequence;
            try (Statement s = connection.createStatement()) {
                sequence = single(s, "SELECT next_sequence FROM sequence_counter");
                s.executeUpdate("UPDATE sequence_counter SET next_sequence = next_sequence + 1");
            }
            try (PreparedStatement outbox = connection.prepareStatement(
                    "INSERT INTO outbox (event_id, source, sequence, product, delta, timestamp)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                outbox.setString(1, eventId.toString());
                outbox.setString(2, source);
                outbox.setLong(3, sequence);
                outbox.setString(4, sale.product().name());
                outbox.setLong(5, delta);
                outbox.setString(6, Instant.now().toString());
                outbox.executeUpdate();
            }
            connection.commit();
            return new SaleRecorded(eventId, sequence);
        } catch (SQLException | RuntimeException e) {
            rollbackAfter(e);
            throw e;
        }
    }

    private void rollbackAfter(Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    /** Highest committed sequence: the number of events created. */
    synchronized long created() throws SQLException {
        try (Statement s = connection.createStatement()) {
            return single(s, CREATED_SQL);
        } finally {
            connection.commit();
        }
    }

    /** Highest sequence the CFE has acknowledged as accepted, 0 if none. */
    synchronized long acceptedThrough() throws SQLException {
        try (Statement s = connection.createStatement()) {
            return single(s, ACCEPTED_THROUGH_SQL);
        } finally {
            connection.commit();
        }
    }

    /** Up to {@code limit} Outbox events after {@code sequence}, in sequence order, as first recorded. */
    synchronized List<Publish> eventsAfter(long sequence, int limit) throws SQLException {
        List<Publish> events = new ArrayList<>();
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT event_id, source, sequence, product, delta, timestamp FROM outbox"
                        + " WHERE sequence > ? ORDER BY sequence LIMIT ?")) {
            q.setLong(1, sequence);
            q.setInt(2, limit);
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    events.add(new Publish(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3),
                            Product.valueOf(rs.getString(4)), rs.getLong(5), Instant.parse(rs.getString(6))));
                }
            }
        } finally {
            connection.commit();
        }
        return events;
    }

    /**
     * Marks every Outbox event up to {@code through} accepted (acknowledgements are cumulative) and
     * returns the new accepted-through. It never moves backwards: rows are only ever set accepted,
     * and a {@code through} beyond the last event marks only the events that exist.
     */
    synchronized long markAcceptedThrough(long through) throws SQLException {
        try (PreparedStatement mark = connection.prepareStatement(
                "UPDATE outbox SET accepted = 1 WHERE sequence <= ? AND accepted = 0");
                Statement s = connection.createStatement()) {
            mark.setLong(1, through);
            mark.executeUpdate();
            long acceptedThrough = single(s, ACCEPTED_THROUGH_SQL);
            connection.commit();
            return acceptedThrough;
        } catch (SQLException | RuntimeException e) {
            rollbackAfter(e);
            throw e;
        }
    }

    /** Every durable store key, in {@link StatusKeys.Store#durable} order. */
    synchronized Map<String, String> durableStatus() throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (Statement s = connection.createStatement()) {
            long created = single(s, CREATED_SQL);
            long acceptedThrough = single(s, ACCEPTED_THROUGH_SQL);
            values.put(StatusKeys.Store.CREATED, Long.toString(created));
            values.put(StatusKeys.Store.ACCEPTED_THROUGH, Long.toString(acceptedThrough));
            values.put(StatusKeys.Store.PENDING, Long.toString(created - acceptedThrough));
            Map<String, Long> stock = new LinkedHashMap<>();
            try (ResultSet rs = s.executeQuery("SELECT product, quantity FROM stock")) {
                while (rs.next()) {
                    stock.put(rs.getString(1), rs.getLong(2));
                }
            }
            for (Product p : Product.values()) {
                Long quantity = stock.get(p.name());
                if (quantity == null) {
                    throw new SQLException("no stock row for " + p.name());
                }
                values.put(StatusKeys.Store.stock(p), Long.toString(quantity));
            }
        }
        connection.commit();
        return values;
    }

    private static long single(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("no row: " + sql);
            }
            return rs.getLong(1);
        }
    }

    /**
     * Releases the file without committing. It waits for a sale or acknowledgement that is
     * committing on another thread, so that one finishes first; any other open transaction rolls back.
     */
    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("store-agent: closing the database failed: " + e);
        }
    }
}
