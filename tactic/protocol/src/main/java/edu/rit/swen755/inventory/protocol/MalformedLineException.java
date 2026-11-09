package edu.rit.swen755.inventory.protocol;

/**
 * A received line is not a valid message: unknown type, wrong field count, or a field that does not
 * parse or is out of range. Receivers answer it (control and sales ports reply {@code ERR|reason})
 * or drop the line; they never crash on it.
 */
public final class MalformedLineException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String line;

    public MalformedLineException(String line, String reason) {
        super(reason + ": " + line);
        this.line = line;
    }

    public MalformedLineException(String line, String reason, Throwable cause) {
        super(reason + ": " + line, cause);
        this.line = line;
    }

    /** The offending line exactly as received. */
    public String line() {
        return line;
    }
}
