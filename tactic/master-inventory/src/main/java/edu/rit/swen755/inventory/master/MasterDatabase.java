package edu.rit.swen755.inventory.master;

import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.Publish;
import edu.rit.swen755.inventory.protocol.Sources;
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
 * The master's SQLite file: stock per store and product, the applied-event record, and the
 * applied-through cursor per source. Every durable STATUS key is read from these tables on request.
 */
final class MasterDatabase implements AutoCloseable {

    static final String FILE_NAME = "master-inventory.db";

    /** Schema version stored in {@code PRAGMA user_version}; 0 means the file was just created. */
    private static final int SCHEMA_VERSION = 1;

    private final Connection connection;
    private final String source;
    /** Test-only failure after changing stock, before recording the applied event. */
    private final boolean failAfterStockUpdate;

    private MasterDatabase(Connection connection, String source, boolean failAfterStockUpdate) {
        this.connection = connection;
        this.source = source;
        this.failAfterStockUpdate = failAfterStockUpdate;
    }

    enum ApplyOutcome {
        APPLIED, DUPLICATE, GAP
    }

    /** The event's outcome and the committed position the caller can acknowledge. */
    record ApplyResult(ApplyOutcome outcome, long appliedThrough) {
    }

    /**
     * Opens (creating if needed) the database in {@code dataDir}. A new file gets its tables and
     * {@code source}'s initial stock in one transaction; an existing file is reopened as it is and
     * never re-seeded.
     */
    static MasterDatabase open(Path dataDir, String source) throws IOException, SQLException {
        return open(dataDir, source, false);
    }

    /** Test entry point. A true flag fails every new apply after its stock update. */
    static MasterDatabase open(Path dataDir, String source, boolean failAfterStockUpdate)
            throws IOException, SQLException {
        Sources.require(source);
        Files.createDirectories(dataDir);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve(FILE_NAME));
        try {
            createAndSeedOnce(c, source);
            return new MasterDatabase(c, source, failAfterStockUpdate);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }

    private static void createAndSeedOnce(Connection c, String source) throws SQLException {
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            if (userVersion(s) == 0) {
                s.executeUpdate("CREATE TABLE stock (source TEXT NOT NULL, product TEXT NOT NULL,"
                        + " quantity INTEGER NOT NULL, PRIMARY KEY (source, product))");
                // apply_count counts stock-changing applies of one event; above 1 is a double apply.
                s.executeUpdate("CREATE TABLE applied_event (event_id TEXT PRIMARY KEY,"
                        + " source TEXT NOT NULL, sequence INTEGER NOT NULL,"
                        + " apply_count INTEGER NOT NULL, UNIQUE (source, sequence))");
                s.executeUpdate("CREATE TABLE cursor (source TEXT PRIMARY KEY,"
                        + " applied_through INTEGER NOT NULL)");
                try (PreparedStatement seed = c.prepareStatement(
                        "INSERT INTO stock (source, product, quantity) VALUES (?, ?, ?)")) {
                    for (Product p : Product.values()) {
                        seed.setString(1, source);
                        seed.setString(2, p.name());
                        seed.setLong(3, Product.INITIAL_STOCK);
                        seed.executeUpdate();
                    }
                }
                try (PreparedStatement cursor = c.prepareStatement(
                        "INSERT INTO cursor (source, applied_through) VALUES (?, 0)")) {
                    cursor.setString(1, source);
                    cursor.executeUpdate();
                }
                s.executeUpdate("PRAGMA user_version = " + SCHEMA_VERSION);
            }
            try (PreparedStatement cursor = c.prepareStatement(
                    "SELECT applied_through FROM cursor WHERE source = ?")) {
                cursor.setString(1, source);
                try (ResultSet rs = cursor.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("database has no inventory for configured source " + source);
                    }
                }
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
     * Applies only the next event for the configured source. Stock, the applied-event record and
     * cursor commit together. Duplicates and gaps return the current committed cursor without
     * changing inventory. The result is returned only after the transaction commits.
     */
    synchronized ApplyResult apply(Publish event) throws SQLException {
        if (!source.equals(event.source())) {
            throw new IllegalArgumentException("unexpected source " + event.source()
                    + ", expected " + source);
        }
        try {
            long through = single("SELECT applied_through FROM cursor WHERE source = ?", source);
            if (event.sequence() <= through || single(
                    "SELECT COUNT(*) FROM applied_event WHERE event_id = ?", event.eventId().toString()) > 0) {
                connection.commit();
                return new ApplyResult(ApplyOutcome.DUPLICATE, through);
            }
            if (event.sequence() != through + 1) {
                connection.commit();
                return new ApplyResult(ApplyOutcome.GAP, through);
            }

            long quantity = single("SELECT quantity FROM stock WHERE source = ? AND product = ?",
                    source, event.product().name());
            long updatedQuantity = Math.addExact(quantity, event.delta());
            try (PreparedStatement stock = connection.prepareStatement(
                    "UPDATE stock SET quantity = ? WHERE source = ? AND product = ?")) {
                stock.setLong(1, updatedQuantity);
                stock.setString(2, source);
                stock.setString(3, event.product().name());
                if (stock.executeUpdate() != 1) {
                    throw new SQLException("no stock row for " + source + "/" + event.product());
                }
            }
            if (failAfterStockUpdate) {
                throw new SQLException("test crash hook: failed after the stock update, before the applied record");
            }
            try (PreparedStatement applied = connection.prepareStatement(
                    "INSERT INTO applied_event (event_id, source, sequence, apply_count) VALUES (?, ?, ?, 1)")) {
                applied.setString(1, event.eventId().toString());
                applied.setString(2, source);
                applied.setLong(3, event.sequence());
                applied.executeUpdate();
            }
            try (PreparedStatement cursor = connection.prepareStatement(
                    "UPDATE cursor SET applied_through = ? WHERE source = ?")) {
                cursor.setLong(1, event.sequence());
                cursor.setString(2, source);
                if (cursor.executeUpdate() != 1) {
                    throw new SQLException("no cursor for " + source);
                }
            }
            connection.commit();
            return new ApplyResult(ApplyOutcome.APPLIED, event.sequence());
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

    /** Every durable master key for {@code source}, in {@link StatusKeys.Master#durable} order. */
    synchronized Map<String, String> durableStatus(String source) throws SQLException {
        if (!this.source.equals(source)) {
            throw new IllegalArgumentException("unexpected source " + source + ", expected " + this.source);
        }
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(StatusKeys.Master.APPLIED, Long.toString(count("SELECT COUNT(*) FROM applied_event")));
            values.put(StatusKeys.Master.APPLIED_TWICE,
                    Long.toString(count("SELECT COUNT(*) FROM applied_event WHERE apply_count > 1")));
            values.put(StatusKeys.Master.appliedThrough(source), Long.toString(single(
                    "SELECT applied_through FROM cursor WHERE source = ?", source)));
            for (Product p : Product.values()) {
                values.put(StatusKeys.Master.stock(source, p), Long.toString(single(
                        "SELECT quantity FROM stock WHERE source = ? AND product = ?", source, p.name())));
            }
            connection.commit();
            return values;
        } catch (SQLException | RuntimeException e) {
            rollbackAfter(e);
            throw e;
        }
    }

    private long count(String sql) throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            if (!rs.next()) {
                throw new SQLException("no row: " + sql);
            }
            return rs.getLong(1);
        }
    }

    private long single(String sql, String... params) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                s.setString(i + 1, params[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("no row for " + String.join(", ", params) + ": " + sql);
                }
                return rs.getLong(1);
            }
        }
    }

    /** Waits for the current database operation, then releases the file. */
    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("master-inventory: closing the database failed: " + e);
        }
    }
}
