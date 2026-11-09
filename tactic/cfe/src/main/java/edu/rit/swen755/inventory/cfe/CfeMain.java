package edu.rit.swen755.inventory.cfe;

import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.net.Launcher;

/**
 * {@code java [-Dinventory.retry.*=...] -jar cfe.jar [--listen-port N] [--control-port N]
 * [--master-port N] [--data-dir PATH] [--source ID]}
 */
public final class CfeMain {

    private CfeMain() {
    }

    public static void main(String[] args) {
        Launcher.run("cfe", CfeConfig.USAGE, args,
                a -> Cfe.start(CfeConfig.parse(a), RetrySettings.fromProperties(System.getProperties())));
    }
}
