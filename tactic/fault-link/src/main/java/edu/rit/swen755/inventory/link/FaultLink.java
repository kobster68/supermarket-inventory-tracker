package edu.rit.swen755.inventory.link;

import edu.rit.swen755.inventory.protocol.Cut;
import edu.rit.swen755.inventory.protocol.ErrorReply;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.Restore;
import edu.rit.swen755.inventory.protocol.SetFault;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.LineConnection;
import edu.rit.swen755.inventory.protocol.net.LineServer;
import edu.rit.swen755.inventory.protocol.net.RunningProcess;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The fault link: a TCP proxy between the Store Agent and the CFE. It forwards each line in both
 * directions subject to its current settings, so acknowledgements are dropped, duplicated and
 * delayed as well as events. It has no storage.
 */
public final class FaultLink implements RunningProcess {

    private final FaultLinkConfig config;
    private LineServer listener;
    private LineServer control;

    private volatile int dropPercent;
    private volatile int duplicatePercent;
    private volatile int delayMillis;
    private volatile boolean cut;

    private FaultLink(FaultLinkConfig config) {
        this.config = config;
    }

    /** Binds both ports and starts serving. */
    public static FaultLink start(FaultLinkConfig config) throws IOException {
        FaultLink link = new FaultLink(config);
        link.listener = LineServer.start("fault-link-listen", config.listenPort(), link::forward);
        try {
            link.control = LineServer.control("fault-link-control", config.controlPort(), link::answer);
        } catch (IOException e) {
            link.listener.close();
            throw e;
        }
        return link;
    }

    private void forward(LineConnection store) throws IOException {
        // TODO(Leon, #33): open a connection to the CFE on config.cfePort() and forward every line
        // in both directions, applying drop, duplicate and delay with a seeded random source and
        // forwarding nothing while cut. Until then lines from the store are read and discarded.
        while (store.readLine() != null) {
            // discarded
        }
    }

    private Message answer(Message request) {
        return switch (request) {
            case StatusRequest s -> status();
            case SetFault s -> new ErrorReply("SET is not implemented yet"); // TODO(Leon, #33)
            case Cut c -> new ErrorReply("CUT is not implemented yet"); // TODO(Leon, #33)
            case Restore r -> new ErrorReply("RESTORE is not implemented yet"); // TODO(Leon, #33)
            default -> ErrorReply.of("the fault link does not accept " + request.getClass().getSimpleName());
        };
    }

    private StatusReply status() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(StatusKeys.FaultLink.DROP, Integer.toString(dropPercent));
        values.put(StatusKeys.FaultLink.DUPLICATE, Integer.toString(duplicatePercent));
        values.put(StatusKeys.FaultLink.DELAY, Integer.toString(delayMillis));
        values.put(StatusKeys.FaultLink.CUT, Boolean.toString(cut));
        return new StatusReply(values);
    }

    @Override
    public String name() {
        return "fault-link";
    }

    @Override
    public Map<String, Integer> boundPorts() {
        Map<String, Integer> ports = new LinkedHashMap<>();
        ports.put("listen", listener.port());
        ports.put("control", control.port());
        return ports;
    }

    @Override
    public void crash() {
        if (listener != null) {
            listener.close();
        }
        if (control != null) {
            control.close();
        }
    }

    @Override
    public void close() {
        // The link has no storage, so a clean stop is the same as a crash.
        crash();
    }
}
