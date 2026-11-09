package edu.rit.swen755.inventory.store;

import edu.rit.swen755.inventory.protocol.Defaults;
import edu.rit.swen755.inventory.protocol.Sources;
import edu.rit.swen755.inventory.protocol.net.CommandLine;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Store Agent settings from the command line. Retry settings come from {@code RetrySettings}.
 *
 * @param salesPort   where the point-of-sale feed sends {@code SALE} lines ({@code --sales-port}, default 7101)
 * @param controlPort STATUS ({@code --control-port}, default 7102)
 * @param linkPort    the fault link's listen port, the store's way to the CFE ({@code --link-port}, default 7201)
 * @param dataDir     folder of the SQLite file ({@code --data-dir}, default {@code demo-data/store-agent})
 * @param source      this store's identifier, written into every event ({@code --source}, default {@code store-1})
 */
public record StoreAgentConfig(int salesPort, int controlPort, int linkPort, Path dataDir, String source) {

    static final String USAGE =
            "[--sales-port N] [--control-port N] [--link-port N] [--data-dir PATH] [--source ID]";

    public StoreAgentConfig {
        Objects.requireNonNull(dataDir, "dataDir");
        Sources.require(source);
    }

    /** @throws IllegalArgumentException for an unknown option or a bad value */
    public static StoreAgentConfig parse(String[] args) {
        CommandLine cl = CommandLine.parse(args,
                Set.of("sales-port", "control-port", "link-port", "data-dir", "source"));
        return new StoreAgentConfig(
                cl.port("sales-port", Defaults.STORE_SALES_PORT),
                cl.port("control-port", Defaults.STORE_CONTROL_PORT),
                cl.port("link-port", Defaults.LINK_LISTEN_PORT),
                cl.path("data-dir", Path.of("demo-data", "store-agent")),
                cl.text("source", Defaults.SOURCE));
    }
}
