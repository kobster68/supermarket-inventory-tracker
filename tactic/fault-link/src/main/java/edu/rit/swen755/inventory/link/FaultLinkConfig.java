package edu.rit.swen755.inventory.link;

import edu.rit.swen755.inventory.protocol.Defaults;
import edu.rit.swen755.inventory.protocol.net.CommandLine;
import java.util.Set;

/**
 * Fault link settings from the command line.
 *
 * @param listenPort  where the store connects ({@code --listen-port}, default 7201)
 * @param controlPort STATUS, SET, CUT and RESTORE ({@code --control-port}, default 7202)
 * @param cfePort     the CFE's listen port the link forwards to ({@code --cfe-port}, default 7301)
 */
public record FaultLinkConfig(int listenPort, int controlPort, int cfePort) {

    static final String USAGE = "[--listen-port N] [--control-port N] [--cfe-port N]";

    /** @throws IllegalArgumentException for an unknown option or a bad value */
    public static FaultLinkConfig parse(String[] args) {
        CommandLine cl = CommandLine.parse(args, Set.of("listen-port", "control-port", "cfe-port"));
        return new FaultLinkConfig(
                cl.port("listen-port", Defaults.LINK_LISTEN_PORT),
                cl.port("control-port", Defaults.LINK_CONTROL_PORT),
                cl.port("cfe-port", Defaults.CFE_LISTEN_PORT));
    }
}
