package edu.rit.swen755.inventory.protocol;

/**
 * Default ports and identity. Every port and each process's data folder can be overridden on the
 * command line; port 0 asks the operating system for a free port, which the process then reports.
 * Every listening socket binds to {@link #HOST} only.
 */
public final class Defaults {

    /** Loopback only, so no firewall prompt appears on Windows. */
    public static final String HOST = "127.0.0.1";

    public static final int STORE_SALES_PORT = 7101;
    public static final int STORE_CONTROL_PORT = 7102;
    public static final int LINK_LISTEN_PORT = 7201;
    public static final int LINK_CONTROL_PORT = 7202;
    public static final int CFE_LISTEN_PORT = 7301;
    public static final int CFE_CONTROL_PORT = 7302;
    public static final int MASTER_LISTEN_PORT = 7401;
    public static final int MASTER_CONTROL_PORT = 7402;

    /** The one store's source identifier, used by the store and reported on by the CFE and master. */
    public static final String SOURCE = "store-1";

    private Defaults() {
    }
}
