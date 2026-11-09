package edu.rit.swen755.inventory.protocol;

/**
 * {@code STATUS}: asks a process for its {@link StatusReply}. Sent to any process's control port.
 * A control connection carries one request line and one reply line, then closes.
 */
public record StatusRequest() implements Message {
}
