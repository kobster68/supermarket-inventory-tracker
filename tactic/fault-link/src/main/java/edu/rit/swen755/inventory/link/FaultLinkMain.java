package edu.rit.swen755.inventory.link;

import edu.rit.swen755.inventory.protocol.net.Launcher;

/** {@code java -jar fault-link.jar [--listen-port N] [--control-port N] [--cfe-port N]} */
public final class FaultLinkMain {

    private FaultLinkMain() {
    }

    public static void main(String[] args) {
        Launcher.run("fault-link", FaultLinkConfig.USAGE, args,
                a -> FaultLink.start(FaultLinkConfig.parse(a)));
    }
}
