package edu.rit.swen755.inventory.cfe;

import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.rit.swen755.inventory.protocol.RetrySettings;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MasterSenderTest {
    @TempDir Path data;

    @Test
    void crashDoesNotWaitForASenderBlockedOnTheDatabase() throws Exception {
        try (CfeDatabase database = CfeDatabase.open(data)) {
            MasterSender sender = new MasterSender(database, RetrySettings.DEFAULTS, 0);
            try {
                synchronized (database) {
                    sender.start();
                    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                    while (Thread.getAllStackTraces().keySet().stream().noneMatch(t ->
                            t.getName().equals("cfe-master-sender") && t.getState() == Thread.State.BLOCKED)) {
                        assertTrue(System.nanoTime() < deadline, "sender did not reach the database");
                        Thread.sleep(10);
                    }
                    assertTimeout(Duration.ofMillis(500), sender::crash);
                }
            } finally {
                sender.stop();
            }
        }
    }
}
