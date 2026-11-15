package edu.rit.swen755.inventory.system;

import edu.rit.swen755.inventory.cfe.Cfe;
import edu.rit.swen755.inventory.cfe.CfeConfig;
import edu.rit.swen755.inventory.link.FaultLink;
import edu.rit.swen755.inventory.link.FaultLinkConfig;
import edu.rit.swen755.inventory.master.MasterInventory;
import edu.rit.swen755.inventory.master.MasterInventoryConfig;
import edu.rit.swen755.inventory.protocol.Defaults;
import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.ControlClient;
import edu.rit.swen755.inventory.protocol.net.RunningProcess;
import edu.rit.swen755.inventory.store.StoreAgent;
import edu.rit.swen755.inventory.store.StoreAgentConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All four processes in this JVM: store, fault link, CFE and master, wired in that order on free
 * ports with fresh data folders.
 *
 * <p><b>Crash.</b> {@link #crash} calls {@link RunningProcess#crash()}: threads interrupted,
 * sockets closed, no orderly shutdown step, and the SQLite file released without committing. An
 * open transaction is rolled back and committed data stays, the same data on disk as after a kill
 * at a transaction boundary. No hot journal is left for SQLite to recover. The file is released
 * rather than left open because, inside one JVM, an abandoned connection would keep its locks and
 * let a still-running thread write after the crash, and on Windows would keep the file from being
 * deleted. SQLite's recovery of a file left by a real kill is exercised by the demo script, which
 * uses {@code kill -9}.
 *
 * <p><b>Restart.</b> {@link #restart} starts a new instance on the same data folder and on the
 * ports the crashed instance had bound, which address reuse allows at once, because its peers keep
 * the port numbers they were given (as in the demo). The new instance reports the ports it bound.
 * Another program could in principle take one of those free ports between crash and restart; the
 * restart then fails loudly with a bind error rather than wiring the system wrongly.
 */
public final class SystemHarness implements AutoCloseable {

    /** The four processes. */
    public enum Role {
        STORE, LINK, CFE, MASTER
    }

    private final Path runDir;
    private final RetrySettings retry;
    /** Read by test threads (a sales driver) while the test thread crashes and restarts processes. */
    private final Map<Role, RunningProcess> processes = new ConcurrentHashMap<>();
    private final Map<Role, Map<String, Integer>> ports = new ConcurrentHashMap<>();

    private SystemHarness(Path runDir, RetrySettings retry) {
        this.runDir = runDir;
        this.retry = retry;
    }

    /**
     * Starts the master, CFE, fault link and store, each on free ports, with data folders in a new
     * folder under {@code baseDir}. Store and CFE use {@code retry} (pass a compressed copy in tests).
     */
    public static SystemHarness start(Path baseDir, RetrySettings retry) throws Exception {
        Files.createDirectories(baseDir);
        SystemHarness system = new SystemHarness(Files.createTempDirectory(baseDir, "run-"), retry);
        try {
            for (Role role : new Role[] {Role.MASTER, Role.CFE, Role.LINK, Role.STORE}) {
                system.launch(role, 0, 0);
            }
        } catch (Exception e) {
            system.close();
            throw e;
        }
        return system;
    }

    /** The running instance in {@code role}. */
    public RunningProcess process(Role role) {
        RunningProcess p = processes.get(role);
        if (p == null) {
            throw new IllegalStateException(role + " is not running");
        }
        return p;
    }

    /** The data folder of {@code role}; the fault link has none. */
    public Path dataDir(Role role) {
        return switch (role) {
            case STORE -> runDir.resolve("store-agent");
            case CFE -> runDir.resolve("cfe");
            case MASTER -> runDir.resolve("master-inventory");
            case LINK -> throw new IllegalArgumentException("the fault link has no data folder");
        };
    }

    /** Sends one control request to {@code role}'s control port. */
    public Message control(Role role, Message request) throws IOException, MalformedLineException {
        return ControlClient.request(process(role).boundPorts().get("control"), request);
    }

    /** {@code role}'s STATUS reply. */
    public StatusReply status(Role role) throws IOException, MalformedLineException {
        Message reply = control(role, new StatusRequest());
        if (reply instanceof StatusReply status) {
            return status;
        }
        throw new IOException(role + " answered STATUS with " + reply);
    }

    /** A new connection to the store's sales port. */
    public SalesClient salesClient() throws IOException {
        return SalesClient.connect(process(Role.STORE).boundPorts().get("sales"));
    }

    /** Stops {@code role} at once, as a kill would. */
    public void crash(Role role) {
        process(role).crash();
        processes.remove(role);
    }

    /** Starts a new instance of a crashed {@code role} on its data folder and the ports it bound before. */
    public void restart(Role role) throws Exception {
        if (processes.containsKey(role)) {
            throw new IllegalStateException(role + " is still running; crash it first");
        }
        Map<String, Integer> before = ports.get(role);
        if (role == Role.STORE) {
            launch(role, before.get("sales"), before.get("control"));
        } else {
            launch(role, before.get("listen"), before.get("control"));
        }
    }

    /**
     * Waits until, for the store's source, store {@code pending} is 0, CFE {@code deliveredThrough}
     * equals CFE {@code acceptedThrough}, and master {@code appliedThrough} equals CFE
     * {@code acceptedThrough}.
     *
     * @return {@code false} if {@code timeout} passed first
     */
    public boolean awaitDrained(Duration timeout) {
        return Verdict.awaitDrained(timeout, () -> Verdict.isDrained(
                status(Role.STORE), status(Role.CFE), status(Role.MASTER), Defaults.SOURCE));
    }

    /** The verdict computed from durable STATUS keys only. Call after {@link #awaitDrained}. */
    public Verdict verdict() {
        try {
            return Verdict.from(status(Role.STORE), status(Role.MASTER), Defaults.SOURCE);
        } catch (IOException | MalformedLineException e) {
            throw new IllegalStateException("could not read STATUS for the verdict", e);
        }
    }

    private void launch(Role role, int listenPort, int controlPort) throws Exception {
        RunningProcess p = switch (role) {
            case MASTER -> MasterInventory.start(new MasterInventoryConfig(
                    listenPort, controlPort, dataDir(role), Defaults.SOURCE));
            case CFE -> Cfe.start(new CfeConfig(listenPort, controlPort,
                    ports.get(Role.MASTER).get("listen"), dataDir(role), Defaults.SOURCE), retry);
            case LINK -> FaultLink.start(new FaultLinkConfig(
                    listenPort, controlPort, ports.get(Role.CFE).get("listen")));
            case STORE -> StoreAgent.start(new StoreAgentConfig(listenPort, controlPort,
                    ports.get(Role.LINK).get("listen"), dataDir(role), Defaults.SOURCE), retry);
        };
        processes.put(role, p);
        ports.put(role, p.boundPorts());
    }

    /** Stops every running process in order, store first. */
    @Override
    public void close() {
        for (Role role : Role.values()) {
            RunningProcess p = processes.remove(role);
            if (p != null) {
                p.close();
            }
        }
    }
}
