package edu.rit.swen755.inventory.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * The reply to {@link StatusRequest}: one line of {@code key=value} pairs separated by {@code |},
 * in the order the process wrote them, for example {@code created=12|acceptedThrough=10|...}. The
 * keys each process must send are fixed in {@link StatusKeys}.
 */
public record StatusReply(Map<String, String> values) implements Message {

    /** Keys and values are single tokens: no separator, no {@code =}, no whitespace. */
    private static final Pattern TOKEN = Pattern.compile("[^|=\\s]+");

    public StatusReply {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("a status reply needs at least one key");
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            requireToken(e.getKey(), "key");
            requireToken(e.getValue(), "value of " + e.getKey());
        }
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** The value of {@code key}. */
    public String value(String key) {
        String v = values.get(key);
        if (v == null) {
            throw new NoSuchElementException("status has no key '" + key + "'");
        }
        return v;
    }

    /** The value of {@code key} read as a whole number. */
    public long number(String key) {
        return Long.parseLong(value(key));
    }

    private static void requireToken(String s, String name) {
        if (s == null || !TOKEN.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    name + " must be non-empty without '|', '=' or whitespace, was '" + s + "'");
        }
    }
}
