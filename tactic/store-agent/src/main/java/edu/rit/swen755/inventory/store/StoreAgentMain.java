package edu.rit.swen755.inventory.store;

import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.net.Launcher;

/**
 * {@code java [-Dinventory.retry.*=...] -jar store-agent.jar [--sales-port N] [--control-port N]
 * [--link-port N] [--data-dir PATH] [--source ID]}
 */
public final class StoreAgentMain {

    private StoreAgentMain() {
    }

    public static void main(String[] args) {
        Launcher.run("store-agent", StoreAgentConfig.USAGE, args,
                a -> StoreAgent.start(StoreAgentConfig.parse(a), RetrySettings.fromProperties(System.getProperties())));
    }
}
