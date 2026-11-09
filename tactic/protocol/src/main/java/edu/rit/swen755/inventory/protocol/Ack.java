package edu.rit.swen755.inventory.protocol;

import java.util.Objects;

/**
 * A cumulative acknowledgement: {@code ACK|accepted|source|through} (CFE to store) or
 * {@code ACK|applied|source|through} (master to CFE). It covers every sequence of {@code source}
 * from 1 to {@code through}; {@code through} 0 means nothing yet. It is sent only after the
 * receiver's transaction commits, and a receiver also sends it in reply to a duplicate or to an
 * event beyond a gap, so the sender resends from {@code through + 1}.
 */
public record Ack(AckKind kind, String source, long through) implements Message {

    public Ack {
        Objects.requireNonNull(kind, "kind");
        Fields.requireIdentifier(source, "source");
        Fields.requireAtLeast(through, 0, "through");
    }
}
