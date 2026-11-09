package edu.rit.swen755.inventory.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import edu.rit.swen755.inventory.protocol.Defaults;
import edu.rit.swen755.inventory.protocol.RetrySettings;
import edu.rit.swen755.inventory.protocol.StatusKeys;
import edu.rit.swen755.inventory.protocol.StatusReply;
import edu.rit.swen755.inventory.system.SystemHarness.Role;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The harness itself: all four processes in one JVM, and a crash-and-restart of any one of them. */
class SystemHarnessTest {

    @TempDir
    Path base;

    @Test
    void startsAllFourOnEphemeralPortsAndEachAnswersStatusWithEveryKey() throws Exception {
        try (SystemHarness system = SystemHarness.start(base, RetrySettings.DEFAULTS)) {
            assertEquals(List.of(), StatusKeys.missing(StatusKeys.Store.all(), system.status(Role.STORE)));
            assertEquals(List.of(), StatusKeys.missing(StatusKeys.FaultLink.all(), system.status(Role.LINK)));
            assertEquals(List.of(), StatusKeys.missing(
                    StatusKeys.Cfe.all(List.of(Defaults.SOURCE)), system.status(Role.CFE)));
            assertEquals(List.of(), StatusKeys.missing(
                    StatusKeys.Master.all(List.of(Defaults.SOURCE)), system.status(Role.MASTER)));
            for (Role role : Role.values()) {
                system.process(role).boundPorts().values()
                        .forEach(port -> assertNotEquals(0, port, role + " reports the port it bound"));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void aCrashedProcessRestartsOnItsOwnFolderAndPorts(Role role) throws Exception {
        try (SystemHarness system = SystemHarness.start(base, RetrySettings.DEFAULTS)) {
            Map<String, Integer> ports = system.process(role).boundPorts();
            StatusReply before = system.status(role);

            system.crash(role);
            system.restart(role);

            assertEquals(ports, system.process(role).boundPorts());
            assertEquals(before.values().keySet(), system.status(role).values().keySet());
            assertEquals(durable(role, before), durable(role, system.status(role)));
        }
    }

    private static Map<String, String> durable(Role role, StatusReply status) {
        List<String> keys = switch (role) {
            case STORE -> StatusKeys.Store.durable();
            case CFE -> StatusKeys.Cfe.durable(List.of(Defaults.SOURCE));
            case MASTER -> StatusKeys.Master.durable(List.of(Defaults.SOURCE));
            case LINK -> List.of();
        };
        Map<String, String> values = new LinkedHashMap<>();
        keys.forEach(k -> values.put(k, status.value(k)));
        return values;
    }
}
