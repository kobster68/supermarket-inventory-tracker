package edu.rit.swen755.inventory.protocol;

/** Source (store) identifiers: letters, digits, {@code -} and {@code _}, so they fit in a field and a STATUS key. */
public final class Sources {

    private Sources() {
    }

    /**
     * @return {@code source} unchanged
     * @throws IllegalArgumentException if it is empty or has any other character
     */
    public static String require(String source) {
        return Fields.requireIdentifier(source, "source");
    }
}
