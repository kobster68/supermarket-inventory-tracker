package edu.rit.swen755.inventory.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** One retry configuration for both legs: the documented defaults, overridable as a whole or per key. */
class RetrySettingsTest {

    @Test
    void defaultsAreTheDocumentedPolicy() {
        RetrySettings d = RetrySettings.DEFAULTS;
        assertEquals(100, d.window());
        assertEquals(Duration.ofMillis(500), d.ackTimeout());
        assertEquals(Duration.ofMillis(100), d.initialBackoff());
        assertEquals(Duration.ofSeconds(2), d.maxBackoff());
        assertEquals(20, d.jitterPercent());
        assertEquals(10, d.linkDownAfter());
    }

    @Test
    void noPropertiesMeansDefaults() {
        assertEquals(RetrySettings.DEFAULTS, RetrySettings.fromProperties(new Properties()));
    }

    @Test
    void eachSettingCanBeOverriddenByProperty() {
        Properties p = new Properties();
        p.setProperty("inventory.retry.window", "5");
        p.setProperty("inventory.retry.ackTimeoutMs", "50");
        p.setProperty("inventory.retry.initialBackoffMs", "10");
        p.setProperty("inventory.retry.maxBackoffMs", "80");
        p.setProperty("inventory.retry.jitterPercent", "0");
        p.setProperty("inventory.retry.linkDownAfter", "3");

        assertEquals(new RetrySettings(5, Duration.ofMillis(50), Duration.ofMillis(10),
                Duration.ofMillis(80), 0, 3), RetrySettings.fromProperties(p));
    }

    @Test
    void invalidSettingsAreRejected() {
        Properties p = new Properties();
        p.setProperty("inventory.retry.window", "zero");
        assertThrows(IllegalArgumentException.class, () -> RetrySettings.fromProperties(p));
        assertThrows(IllegalArgumentException.class, () -> new RetrySettings(0, Duration.ofMillis(1),
                Duration.ofMillis(1), Duration.ofMillis(1), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetrySettings(1, Duration.ofMillis(1),
                Duration.ofMillis(10), Duration.ofMillis(5), 0, 1));
    }
}
