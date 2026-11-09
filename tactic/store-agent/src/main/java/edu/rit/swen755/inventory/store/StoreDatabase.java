package edu.rit.swen755.inventory.store;

import edu.rit.swen755.inventory.protocol.Product;
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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The store's SQLite file: the Store-Local Inventory (product to quantity), the Outbox, and the next
 * sequence number. Every durable STATUS key is read from these tables on request.
 */
final class StoreDatabase implements AutoCloseable {

    static final String FILE_NAME = "store-agent.db";

    /** Schema version stored in {@code PRAGMA user_version}; 0 means the file was just created. */
    private static final int SCHEMA_VERSION = 1;

    private final Connection connection;

    private StoreDatabase(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens (creating if needed) the database in {@code dataDir}. A new file gets its tables and the
     * initial stock of every product in one transaction; an existing file is reopened as it is and
     * never re-seeded.
     */
    static StoreDatabase open(Path dataDir) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve(FILE_NAME));
        try {
            createAndSeedOnce(c);
            return new StoreDatabase(c);
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

    /** Every durable store key, in {@link StatusKeys.Store#durable} order. */
    synchronized Map<String, String> durableStatus() throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (Statement s = connection.createStatement()) {
            long created = single(s, "SELECT next_sequence - 1 FROM sequence_counter");
            long acceptedThrough = single(s,
                    "SELECT COALESCE(MAX(sequence), 0) FROM outbox WHERE accepted = 1");
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

    /** Releases the file without committing: an open transaction rolls back. */
    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("store-agent: closing the database failed: " + e);
        }
    }
}
