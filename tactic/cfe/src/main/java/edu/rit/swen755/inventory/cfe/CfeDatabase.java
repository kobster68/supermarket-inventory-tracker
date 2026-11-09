package edu.rit.swen755.inventory.cfe;

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
 * The CFE's SQLite file: the event log (unique on event ID and on source and sequence) and, per
 * source, the accepted-through and delivered-through positions. Every durable STATUS key is read
 * from these tables on request.
 */
final class CfeDatabase implements AutoCloseable {

    static final String FILE_NAME = "cfe.db";

    /** Schema version stored in {@code PRAGMA user_version}; 0 means the file was just created. */
    private static final int SCHEMA_VERSION = 1;

    private final Connection connection;

    private CfeDatabase(Connection connection) {
        this.connection = connection;
    }

    /** Opens (creating if needed) the database in {@code dataDir}; a new file gets its tables in one transaction. */
    static CfeDatabase open(Path dataDir) throws IOException, SQLException {
        Files.createDirectories(dataDir);
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dataDir.resolve(FILE_NAME));
        try {
            createOnce(c);
            return new CfeDatabase(c);
        } catch (SQLException e) {
            c.close();
            throw e;
        }
    }

    private static void createOnce(Connection c) throws SQLException {
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            if (userVersion(s) == 0) {
                s.executeUpdate("CREATE TABLE event_log (event_id TEXT PRIMARY KEY,"
                        + " source TEXT NOT NULL, sequence INTEGER NOT NULL, product TEXT NOT NULL,"
                        + " delta INTEGER NOT NULL, timestamp TEXT NOT NULL, UNIQUE (source, sequence))");
                s.executeUpdate("CREATE TABLE position (source TEXT PRIMARY KEY,"
                        + " accepted_through INTEGER NOT NULL, delivered_through INTEGER NOT NULL)");
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

    /** Every durable CFE key for {@code source}, in {@link StatusKeys.Cfe#durable} order. */
    synchronized Map<String, String> durableStatus(String source) throws SQLException {
        Map<String, String> values = new LinkedHashMap<>();
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM event_log")) {
            requireRow(rs);
            values.put(StatusKeys.Cfe.STORED, Long.toString(rs.getLong(1)));
        }
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT COALESCE(MAX(accepted_through), 0), COALESCE(MAX(delivered_through), 0)"
                        + " FROM position WHERE source = ?")) {
            s.setString(1, source);
            try (ResultSet rs = s.executeQuery()) {
                requireRow(rs);
                values.put(StatusKeys.Cfe.acceptedThrough(source), Long.toString(rs.getLong(1)));
                values.put(StatusKeys.Cfe.deliveredThrough(source), Long.toString(rs.getLong(2)));
            }
        }
        connection.commit();
        return values;
    }

    private static void requireRow(ResultSet rs) throws SQLException {
        if (!rs.next()) {
            throw new SQLException("an aggregate query returned no row");
        }
    }

    /** Releases the file without committing: an open transaction rolls back. */
    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("cfe: closing the database failed: " + e);
        }
    }
}
