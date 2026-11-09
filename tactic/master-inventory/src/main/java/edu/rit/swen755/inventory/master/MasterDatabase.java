package edu.rit.swen755.inventory.master;

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
 * The master's SQLite file: stock per store and product, the applied-event record, and the
 * applied-through cursor per source. Every durable STATUS key is read from these tables on request.
 */
final class MasterDatabase implements AutoCloseable {

    static final String FILE_NAME = "master-inventory.db";

    /** Schema version stored in {@code PRAGMA user_version}; 0 means the file was just created. */
    private static final int SCHEMA_VERSION = 1;

    private final Connection connection;

    private MasterDatabase(Connection connection) {
        this.connection = connection;
    }

    /**
     * Opens (creating if needed) the database in {@code dataDir}. A new file gets its tables and
     * {@code source}'s initial stock in one transaction; an existing file is reopened as it is and
     * never re-seeded.
     */
    static MasterDatabase open(Path dataDir, String source) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve(FILE_NAME));
        try {
            createAndSeedOnce(c, source);
            return new MasterDatabase(c);
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

    /** Every durable master key for {@code source}, in {@link StatusKeys.Master#durable} order. */
    synchronized Map<String, String> durableStatus(String source) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(StatusKeys.Master.APPLIED, Long.toString(count("SELECT COUNT(*) FROM applied_event")));
        values.put(StatusKeys.Master.APPLIED_TWICE,
                Long.toString(count("SELECT COUNT(*) FROM applied_event WHERE apply_count > 1")));
        values.put(StatusKeys.Master.appliedThrough(source), Long.toString(single(
                "SELECT COALESCE(MAX(applied_through), 0) FROM cursor WHERE source = ?", source)));
        for (Product p : Product.values()) {
            values.put(StatusKeys.Master.stock(source, p), Long.toString(single(
                    "SELECT quantity FROM stock WHERE source = ? AND product = ?", source, p.name())));
        }
        connection.commit();
        return values;
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

    /** Releases the file without committing: an open transaction rolls back. */
    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("master-inventory: closing the database failed: " + e);
        }
    }
}
