package edu.rit.swen755.inventory.master;

import edu.rit.swen755.inventory.protocol.Defaults;
import edu.rit.swen755.inventory.protocol.Sources;
import edu.rit.swen755.inventory.protocol.net.CommandLine;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Master settings from the command line.
 *
 * @param listenPort  where the CFE connects ({@code --listen-port}, default 7401)
 * @param controlPort STATUS ({@code --control-port}, default 7402)
 * @param dataDir     folder of the SQLite file ({@code --data-dir}, default {@code demo-data/master-inventory})
 * @param source      the store whose stock the master seeds and reports ({@code --source}, default {@code store-1})
 */
public record MasterInventoryConfig(int listenPort, int controlPort, Path dataDir, String source) {

    static final String USAGE = "[--listen-port N] [--control-port N] [--data-dir PATH] [--source ID]";

    public MasterInventoryConfig {
        Objects.requireNonNull(dataDir, "dataDir");
        Sources.require(source);
    }

    /** @throws IllegalArgumentException for an unknown option or a bad value */
    public static MasterInventoryConfig parse(String[] args) {
        CommandLine cl = CommandLine.parse(args, Set.of("listen-port", "control-port", "data-dir", "source"));
        return new MasterInventoryConfig(
                cl.port("listen-port", Defaults.MASTER_LISTEN_PORT),
                cl.port("control-port", Defaults.MASTER_CONTROL_PORT),
                cl.path("data-dir", Path.of("demo-data", "master-inventory")),
                cl.text("source", Defaults.SOURCE));
    }
}
