package edu.rit.swen755.inventory.cfe;

import edu.rit.swen755.inventory.protocol.Defaults;
import edu.rit.swen755.inventory.protocol.Sources;
import edu.rit.swen755.inventory.protocol.net.CommandLine;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * CFE settings from the command line. Retry settings come from {@code RetrySettings}.
 *
 * @param listenPort  where the store (through the fault link) connects ({@code --listen-port}, default 7301)
 * @param controlPort STATUS ({@code --control-port}, default 7302)
 * @param masterPort  the master's listen port ({@code --master-port}, default 7401)
 * @param dataDir     folder of the SQLite file ({@code --data-dir}, default {@code demo-data/cfe})
 * @param source      the store whose positions STATUS reports ({@code --source}, default {@code store-1})
 */
public record CfeConfig(int listenPort, int controlPort, int masterPort, Path dataDir, String source) {

    static final String USAGE =
            "[--listen-port N] [--control-port N] [--master-port N] [--data-dir PATH] [--source ID]";

    public CfeConfig {
        Objects.requireNonNull(dataDir, "dataDir");
        Sources.require(source);
    }

    /** @throws IllegalArgumentException for an unknown option or a bad value */
    public static CfeConfig parse(String[] args) {
        CommandLine cl = CommandLine.parse(args,
                Set.of("listen-port", "control-port", "master-port", "data-dir", "source"));
        return new CfeConfig(
                cl.port("listen-port", Defaults.CFE_LISTEN_PORT),
                cl.port("control-port", Defaults.CFE_CONTROL_PORT),
                cl.port("master-port", Defaults.MASTER_LISTEN_PORT),
                cl.path("data-dir", Path.of("demo-data", "cfe")),
                cl.text("source", Defaults.SOURCE));
    }
}
