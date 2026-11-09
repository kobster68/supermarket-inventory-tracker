package edu.rit.swen755.inventory.protocol;

import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

/**
 * The retry policy both senders use (store to CFE, CFE to master). The demo runs the
 * {@link #DEFAULTS}; tests build a compressed copy with short timeouts.
 *
 * <ul>
 *   <li>At most {@code window} unacknowledged events are in flight, in sequence order.</li>
 *   <li>No acknowledgement within {@code ackTimeout} resends everything after the receiver's last
 *       acknowledged position, waiting {@code initialBackoff} before the first resend and doubling
 *       the wait after each further failed attempt up to {@code maxBackoff}. Each wait is lengthened
 *       by a random amount of up to {@code jitterPercent} percent.</li>
 *   <li>After {@code linkDownAfter} consecutive failed attempts the sender marks the link down,
 *       keeps every event, and keeps probing at {@code maxBackoff}. The first acknowledgement marks
 *       it up again. The limit never discards an event.</li>
 * </ul>
 *
 * <p>A process reads overrides from Java system properties (see {@link #fromProperties}), for
 * example {@code java -Dinventory.retry.ackTimeoutMs=50 -jar store-agent.jar}.
 */
public record RetrySettings(int window, Duration ackTimeout, Duration initialBackoff,
        Duration maxBackoff, int jitterPercent, int linkDownAfter) {

    /** The documented policy: window 100, 500 ms timeout, 100 ms to 2 s backoff, 20% jitter, down after 10. */
    public static final RetrySettings DEFAULTS = new RetrySettings(100, Duration.ofMillis(500),
            Duration.ofMillis(100), Duration.ofSeconds(2), 20, 10);

    static final String PREFIX = "inventory.retry.";

    public RetrySettings {
        Fields.requireAtLeast(window, 1, "window");
        requirePositive(ackTimeout, "ackTimeout");
        requirePositive(initialBackoff, "initialBackoff");
        requirePositive(maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
        if (jitterPercent < 0 || jitterPercent > 100) {
            throw new IllegalArgumentException("jitterPercent must be 0-100, was " + jitterPercent);
        }
        Fields.requireAtLeast(linkDownAfter, 1, "linkDownAfter");
    }

    /**
     * {@link #DEFAULTS} with any of these properties applied: {@code inventory.retry.window},
     * {@code .ackTimeoutMs}, {@code .initialBackoffMs}, {@code .maxBackoffMs},
     * {@code .jitterPercent}, {@code .linkDownAfter}.
     *
     * @throws IllegalArgumentException if a property is not a whole number or out of range
     */
    public static RetrySettings fromProperties(Properties properties) {
        RetrySettings d = DEFAULTS;
        return new RetrySettings(
                (int) read(properties, "window", d.window),
                Duration.ofMillis(read(properties, "ackTimeoutMs", d.ackTimeout.toMillis())),
                Duration.ofMillis(read(properties, "initialBackoffMs", d.initialBackoff.toMillis())),
                Duration.ofMillis(read(properties, "maxBackoffMs", d.maxBackoff.toMillis())),
                (int) read(properties, "jitterPercent", d.jitterPercent),
                (int) read(properties, "linkDownAfter", d.linkDownAfter));
    }

    private static long read(Properties properties, String name, long fallback) {
        String text = properties.getProperty(PREFIX + name);
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(PREFIX + name + " must be a whole number, was '" + text + "'", e);
        }
    }

    private static void requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name);
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be positive, was " + d);
        }
    }
}
