package edu.rit.swen755.inventory.protocol;

import java.util.regex.Pattern;

/** Field rules shared by the message records. */
final class Fields {

    /** Letters, digits, {@code -} and {@code _}: safe inside a line and inside a STATUS key. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]+");

    private Fields() {
    }

    /**
     * A source (store) identifier. It appears as a field and inside STATUS keys such as
     * {@code acceptedThrough.<source>}, so it may not contain {@code |}, {@code =} or {@code .}.
     */
    static String requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + IDENTIFIER + ", was " + value);
        }
        return value;
    }

    /** Free text carried in one field: non-blank, no separator, no line break. */
    static String requireText(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('|') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(
                    name + " must be non-blank text without '|' or line breaks, was " + value);
        }
        return value;
    }

    static long requireAtLeast(long value, long min, String name) {
        if (value < min) {
            throw new IllegalArgumentException(name + " must be at least " + min + ", was " + value);
        }
        return value;
    }
}
