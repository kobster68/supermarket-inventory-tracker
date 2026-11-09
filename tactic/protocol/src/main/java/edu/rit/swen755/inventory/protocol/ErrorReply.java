package edu.rit.swen755.inventory.protocol;

/**
 * A refusal on the sales or a control port: {@code ERR|reason}. The reason is one field of plain
 * text, so it may not contain {@code |} or a line break.
 */
public record ErrorReply(String reason) implements Message {

    public ErrorReply {
        Fields.requireText(reason, "reason");
    }

    /** An error reply for any text, with separators and line breaks replaced so it fits one field. */
    public static ErrorReply of(String text) {
        String cleaned = text == null ? "" : text.replace('|', '/').replaceAll("[\\r\\n]+", " ").strip();
        return new ErrorReply(cleaned.isEmpty() ? "error" : cleaned);
    }
}
