package edu.rit.swen755.inventory.protocol.net;

import java.util.Map;
import java.util.StringJoiner;

/** One running instance of a process, started in this JVM by its {@code start} method or its {@code main}. */
public interface RunningProcess extends AutoCloseable {

    /** The process name, which is also its module and jar name, for example {@code store-agent}. */
    String name();

    /** Every port the process listens on, by role ({@code sales}, {@code listen}, {@code control}), as bound. */
    Map<String, Integer> boundPorts();

    /**
     * Stops at once, as a kill would. Runs none of the process's orderly shutdown steps: it
     * interrupts the process's threads, closes its sockets, and releases its database file without
     * committing. An open transaction is rolled back and committed transactions stay, so the data on
     * disk is the same as after a kill at a transaction boundary. Closing the connection does not
     * leave a hot journal for SQLite to recover when the file is reopened; the demo's real
     * {@code kill -9} covers that path. Releasing the file (rather than leaving the
     * connection open) stops a still-running thread from writing after the crash and frees the
     * file's locks and handles for the next instance. A new instance can then be started on the
     * same data folder and the same ports.
     */
    void crash();

    /** Stops in order: finishes or abandons in-flight work as the process defines, then closes the database. */
    @Override
    void close();

    /**
     * The line a process prints once it is listening, for scripts and people to read the ports it
     * bound: {@code READY <name> <role>=<port> ...}, for example
     * {@code READY store-agent sales=7101 control=7102}.
     */
    default String readyLine() {
        StringJoiner line = new StringJoiner(" ");
        line.add("READY").add(name());
        boundPorts().forEach((role, port) -> line.add(role + "=" + port));
        return line.toString();
    }
}
