package edu.rit.swen755.inventory.cfe;

import edu.rit.swen755.inventory.protocol.Product;
import edu.rit.swen755.inventory.protocol.Publish;
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

    record Acceptance(long through, boolean duplicate) { }
    record Position(long accepted, long delivered) { }

    /** The event and its contiguous cursor commit together, before any accepted ACK is sent. */
    synchronized Acceptance accept(Publish event) throws SQLException {
        try {
            long through = position(event.source()).accepted();
            boolean duplicate = event.sequence() <= through;
            try (PreparedStatement q = connection.prepareStatement("SELECT 1 FROM event_log WHERE event_id = ?")) {
                q.setString(1, event.eventId().toString());
                try (ResultSet rs = q.executeQuery()) {
                    duplicate |= rs.next();
                }
            }
            if (!duplicate && event.sequence() == through + 1) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO event_log VALUES (?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, event.eventId().toString());
                    insert.setString(2, event.source());
                    insert.setLong(3, event.sequence());
                    insert.setString(4, event.product().name());
                    insert.setLong(5, event.delta());
                    insert.setString(6, event.timestamp().toString());
                    insert.executeUpdate();
                }
                through = event.sequence();
                try (PreparedStatement update = connection.prepareStatement(
                        "INSERT INTO position VALUES (?, ?, 0) ON CONFLICT(source)"
                                + " DO UPDATE SET accepted_through = excluded.accepted_through")) {
                    update.setString(1, event.source());
                    update.setLong(2, through);
                    update.executeUpdate();
                }
            }
            connection.commit();
            return new Acceptance(through, duplicate);
        } catch (SQLException | RuntimeException e) {
            rollbackAfter(e);
            throw e;
        }
    }

    private Position position(String source) throws SQLException {
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT accepted_through, delivered_through FROM position WHERE source = ?")) {
            q.setString(1, source);
            try (ResultSet rs = q.executeQuery()) {
                return rs.next() ? new Position(rs.getLong(1), rs.getLong(2)) : new Position(0, 0);
            }
        }
    }

    synchronized Map<String, Position> positions() throws SQLException {
        Map<String, Position> positions = new LinkedHashMap<>();
        try (Statement s = connection.createStatement();
                ResultSet rs = s.executeQuery("SELECT source, accepted_through, delivered_through FROM position ORDER BY source")) {
            while (rs.next()) {
                positions.put(rs.getString(1), new Position(rs.getLong(2), rs.getLong(3)));
            }
        } finally {
            connection.commit();
        }
        return positions;
    }

    synchronized List<Publish> eventsAfter(String source, long through, int limit) throws SQLException {
        List<Publish> events = new ArrayList<>();
        try (PreparedStatement q = connection.prepareStatement(
                "SELECT event_id, sequence, product, delta, timestamp FROM event_log"
                        + " WHERE source = ? AND sequence > ? ORDER BY sequence LIMIT ?")) {
            q.setString(1, source);
            q.setLong(2, through);
            q.setInt(3, limit);
            try (ResultSet rs = q.executeQuery()) {
                while (rs.next()) {
                    events.add(new Publish(UUID.fromString(rs.getString(1)), source, rs.getLong(2),
                            Product.valueOf(rs.getString(3)), rs.getLong(4), Instant.parse(rs.getString(5))));
                }
            }
        } finally {
            connection.commit();
        }
        return events;
    }

    /** Only the sender calls this, after validating an applied ACK against its sent position. */
    synchronized long markDelivered(String source, long through) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE position SET delivered_through = ? WHERE source = ?"
                        + " AND delivered_through < ? AND accepted_through >= ?")) {
            update.setLong(1, through);
            update.setString(2, source);
            update.setLong(3, through);
            update.setLong(4, through);
            update.executeUpdate();
            long delivered = position(source).delivered();
            connection.commit();
            return delivered;
        } catch (SQLException | RuntimeException e) {
            rollbackAfter(e);
            throw e;
        }
    }

    private void rollbackAfter(Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            failure.addSuppressed(e);
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
        Map<String, Position> positions = new LinkedHashMap<>();
        positions.put(source, new Position(0, 0));
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT source, accepted_through, delivered_through FROM position ORDER BY source")) {
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    positions.put(rs.getString(1), new Position(rs.getLong(2), rs.getLong(3)));
                }
            }
        }
        positions.forEach((id, p) -> {
            values.put(StatusKeys.Cfe.acceptedThrough(id), Long.toString(p.accepted()));
            values.put(StatusKeys.Cfe.deliveredThrough(id), Long.toString(p.delivered()));
        });
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
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("cfe: closing the database failed: " + e);
        }
    }
}
