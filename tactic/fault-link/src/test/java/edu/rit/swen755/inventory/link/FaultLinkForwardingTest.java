package edu.rit.swen755.inventory.link;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import edu.rit.swen755.inventory.protocol.Cut;
import edu.rit.swen755.inventory.protocol.FaultSetting;
import edu.rit.swen755.inventory.protocol.Message;
import edu.rit.swen755.inventory.protocol.Ok;
import edu.rit.swen755.inventory.protocol.Restore;
import edu.rit.swen755.inventory.protocol.SetFault;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.protocol.StatusRequest;
import edu.rit.swen755.inventory.protocol.net.ControlClient;
import edu.rit.swen755.inventory.protocol.net.Endpoints;
import edu.rit.swen755.inventory.protocol.net.LineConnection;

/** The fault link between two fake endpoints: a fake store (this test) and a fake CFE. */
class FaultLinkForwardingTest {

    private static final int LINES = 2_000;
    /** How long a side must stay silent before a count is taken as final. */
    private static final int QUIET_MILLIS = 300;

    private FakeCfe cfe;
    private FaultLink link;

    @BeforeEach
    void start() throws IOException {
        cfe = new FakeCfe();
        link = FaultLink.start(new FaultLinkConfig(0, 0, cfe.port()));
    }

    @AfterEach
    void stop() {
        link.close();
        cfe.close();
    }

    @Test
    void atZeroEveryLineIsForwardedUnchangedAndInOrderInBothDirections() throws Exception {
        try (LineConnection store = connectStore()) {
            LineConnection cfeSide = cfe.nextConnection();
            List<String> lines = numbered("PUBLISH|store-1|", LINES);
            
            for (String line : lines) {
                store.writeLine(line);
                cfeSide.writeLine(line.replace("PUBLISH", "ACK"));
            }

            assertEquals(lines, cfe.drain());
            assertEquals(lines.stream().map(l -> l.replace("PUBLISH", "ACK")).toList(), drain(store));
        }
    }

    @Test
    void dropRemovesAboutTheSetShareInBothDirections() throws Exception {
        assertOk(new SetFault(FaultSetting.DROP, 20));
        try (LineConnection store = connectStore()) {
            LineConnection cfeSide = cfe.nextConnection();
            List<String> lines = numbered("x", LINES);
            
            for (String line : lines) {
                store.writeLine(line);
                cfeSide.writeLine(line);
            }
            for (List<String> got : List.of(cfe.drain(), drain(store))) {
                assertBetween(LINES * 70 / 100, LINES * 90 / 100, got.size());
                assertInOrderSubsetOf(lines, got);
            }
        }
    }

    @Test
    void duplicateAddsAboutTheSetShareInBothDirections() throws Exception {
        assertOk(new SetFault(FaultSetting.DUPLICATE, 10));

        try (LineConnection store = connectStore()) {
            LineConnection cfeSide = cfe.nextConnection();
            List<String> lines = numbered("x", LINES);

            for (String line : lines) {
                store.writeLine(line);
                cfeSide.writeLine(line);
            }

            for (List<String> got : List.of(cfe.drain(), drain(store))) {
                assertBetween(LINES * 105 / 100, LINES * 115 / 100, got.size());
                assertEquals(lines, List.copyOf(new LinkedHashSet<>(got)), "every line, once or twice, in order");
            }
        }
    }

    @Test
    void delayHoldsLinesBackInBothDirectionsAndKeepsTheirOrder() throws Exception {
        assertOk(new SetFault(FaultSetting.DELAY, 300));
       
        try (LineConnection store = connectStore()) {
            LineConnection cfeSide = cfe.nextConnection();
            long sent = System.nanoTime();
            store.writeLine("a");
            store.writeLine("b");
            assertEquals("a", cfe.received.poll(5, TimeUnit.SECONDS));
            assertTrue(millisSince(sent) >= 300, "store to CFE is delayed");
            assertEquals("b", cfe.received.poll(5, TimeUnit.SECONDS));

            sent = System.nanoTime();
            cfeSide.writeLine("c");
            store.socket().setSoTimeout(5_000);
            assertEquals("c", store.readLine());
            assertTrue(millisSince(sent) >= 300, "CFE to store is delayed");
        }
    }

    @Test
    void cutClosesAndRefusesConnectionsUntilRestore() throws Exception {
        LineConnection store = connectStore();
        LineConnection cfeSide = cfe.nextConnection();
        assertOk(new Cut());
        assertClosed(store);
        assertClosed(cfeSide);
        assertEquals("true", status().value(StatusKeys.FaultLink.CUT));

        try (LineConnection refused = connectStore()) {
            assertClosed(refused);
        }
        assertNull(cfe.connections.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS), "no CFE connection while cut");

        assertOk(new Restore());
        assertEquals("false", status().value(StatusKeys.FaultLink.CUT));

        try (LineConnection again = connectStore()) {
            again.writeLine("after restore");
            assertEquals("after restore", cfe.received.poll(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cutStopsWritersThatWaitForALongDelay() throws Exception {
        assertOk(new SetFault(FaultSetting.DELAY, 60_000));
        LineConnection store = connectStore();
        LineConnection cfeSide = cfe.nextConnection();
        store.writeLine("held");
        cfeSide.writeLine("held");
        // Each writer sleeps on its delayed line in TIMED_WAITING. An idle writer is in WAITING.
        awaitWriters(Thread.State.TIMED_WAITING, 2);

        assertOk(new Cut());
        awaitWriters(null, 0);
        store.close();
    }

    @Test
    void setChangesStatusAndAnOutOfRangeValueIsRefused() throws Exception {
        assertOk(new SetFault(FaultSetting.DROP, 5));
        assertOk(new SetFault(FaultSetting.DUPLICATE, 6));
        assertOk(new SetFault(FaultSetting.DELAY, 7));
        StatusReply status = status();
        assertEquals("5", status.value(StatusKeys.FaultLink.DROP));
        assertEquals("6", status.value(StatusKeys.FaultLink.DUPLICATE));
        assertEquals("7", status.value(StatusKeys.FaultLink.DELAY));

        assertTrue(rawControl("SET|drop|101").startsWith("ERR|"));
        assertEquals("5", status().value(StatusKeys.FaultLink.DROP));
    }

    @Test
    void aStoreConnectionIsClosedWhenTheCfeIsDown() throws Exception {
        cfe.close();

        try (LineConnection store = connectStore()) {
            assertClosed(store);
        }
    }

    // --- helpers ---

    private LineConnection connectStore() throws IOException {
        return new LineConnection(Endpoints.connect(link.boundPorts().get("listen"), 1_000));
    }

    private void assertOk(Message command) throws Exception {
        assertInstanceOf(Ok.class, ControlClient.request(link.boundPorts().get("control"), command));
    }

    private StatusReply status() throws Exception {
        return assertInstanceOf(StatusReply.class,
                ControlClient.request(link.boundPorts().get("control"), new StatusRequest()));
    }

    private String rawControl(String line) throws IOException {
        try (Socket socket = Endpoints.connect(link.boundPorts().get("control"), 1_000)) {
            socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.UTF_8));

            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    .readLine();
        }
    }

    private static List<String> numbered(String prefix, int count) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            lines.add(prefix + i);
        }

        return lines;
    }

    /** Every line that arrives until the connection stays silent for {@link #QUIET_MILLIS}. */
    private static List<String> drain(LineConnection connection) throws IOException {
        connection.socket().setSoTimeout(QUIET_MILLIS);
        List<String> got = new ArrayList<>();
        
        try {
            for (String line; (line = connection.readLine()) != null; ) {
                got.add(line);
            }
        } catch (SocketTimeoutException e) {
            // quiet: done
        }

        return got;
    }

    private static void assertClosed(LineConnection connection) throws IOException {
        connection.socket().setSoTimeout(5_000);
        
        try {
            assertNull(connection.readLine());
        } catch (SocketException e) {
            // reset by the peer: closed too
        }
    }

    private static void assertBetween(int low, int high, int actual) {
        assertTrue(actual >= low && actual <= high, actual + " is not in " + low + "-" + high);
    }

    private static void assertInOrderSubsetOf(List<String> all, List<String> some) {
        int i = 0;

        for (String line : some) {
            while (i < all.size() && !all.get(i).equals(line)) {
                i++;
            }

            assertTrue(i < all.size(), line + " is out of order or not sent");
            i++;
        }
    }

    /** Waits until {@code count} live writers are in {@code state} (any state if null). */
    private static void awaitWriters(Thread.State state, int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        long found;

        do {
            found = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().equals("fault-link-writer") && t.isAlive())
                    .filter(t -> state == null || t.getState() == state)
                    .count();

            if (found == count) {
                return;
            }

            Thread.sleep(20);
        } while (System.nanoTime() < deadline);

        assertEquals(count, found, "writers in state " + state);
    }

    private static long millisSince(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos);
    }

    /** A CFE that accepts connections and records every line it receives. */
    private static final class FakeCfe implements AutoCloseable {

        final ServerSocket server = Endpoints.listen(0);
        final BlockingQueue<LineConnection> connections = new LinkedBlockingQueue<>();
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();

        FakeCfe() throws IOException {
            Thread accept = new Thread(this::acceptLoop, "fake-cfe-accept");
            accept.setDaemon(true);
            accept.start();
        }

        int port() {
            return server.getLocalPort();
        }

        LineConnection nextConnection() throws InterruptedException {
            LineConnection c = connections.poll(5, TimeUnit.SECONDS);
            assertNotNull(c, "the link connected to the CFE");

            return c;
        }

        List<String> drain() throws InterruptedException {
            List<String> got = new ArrayList<>();
            
            for (String line; (line = received.poll(QUIET_MILLIS, TimeUnit.MILLISECONDS)) != null; ) {
                got.add(line);
            }

            return got;
        }

        private void acceptLoop() {
            try {
                while (true) {
                    LineConnection c = new LineConnection(server.accept());
                    connections.add(c);
                    Thread reader = new Thread(() -> read(c), "fake-cfe-reader");
                    reader.setDaemon(true);
                    reader.start();
                }
            } catch (IOException e) {
                // closed
            }
        }

        private void read(LineConnection c) {
            try {
                for (String line; (line = c.readLine()) != null; ) {
                    received.add(line);
                }
            } catch (IOException e) {
                // closed
            }
        }

        /** Stops accepting. The link closes its own connections to this CFE. */
        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // closed already
            }
        }
    }
}
