package edu.rit.swen755.inventory.master;

import edu.rit.swen755.inventory.protocol.net.Launcher;

/** {@code java -jar master-inventory.jar [--listen-port N] [--control-port N] [--data-dir PATH] [--source ID]} */
public final class MasterInventoryMain {

    private MasterInventoryMain() {
    }

    public static void main(String[] args) {
        Launcher.run("master-inventory", MasterInventoryConfig.USAGE, args,
                a -> MasterInventory.start(MasterInventoryConfig.parse(a)));
    }
}
