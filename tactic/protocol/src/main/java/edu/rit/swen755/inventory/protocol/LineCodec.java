package edu.rit.swen755.inventory.protocol;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Encodes every {@link Message} as one line (no line terminator) and decodes a line back. Fields
 * are separated by {@code |} and the first field names the message type.
 */
public final class LineCodec {

    static final String SEPARATOR = "|";

    private LineCodec() {
    }

    /** The message's line, without a line terminator. */
    public static String encode(Message message) {
        return switch (message) {
            case Publish p -> String.join(SEPARATOR, "PUBLISH", p.eventId().toString(), p.source(),
                    Long.toString(p.sequence()), p.product().name(), Long.toString(p.delta()),
                    p.timestamp().toString());
            case Ack a -> String.join(SEPARATOR, "ACK", a.kind().wire(), a.source(),
                    Long.toString(a.through()));
            case Sale s -> String.join(SEPARATOR, "SALE", s.product().name(),
                    Integer.toString(s.quantity()));
            case SaleRecorded s -> String.join(SEPARATOR, "OK", s.eventId().toString(),
                    Long.toString(s.sequence()));
            case ErrorReply e -> String.join(SEPARATOR, "ERR", e.reason());
            case StatusRequest s -> "STATUS";
            case StatusReply s -> encodePairs(s.values());
            case SetFault s -> String.join(SEPARATOR, "SET", s.setting().wire(),
                    Integer.toString(s.value()));
            case Cut c -> "CUT";
            case Restore r -> "RESTORE";
            case Ok o -> "OK";
        };
    }

    private static String encodePairs(Map<String, String> values) {
        StringJoiner line = new StringJoiner(SEPARATOR);
        values.forEach((k, v) -> line.add(k + "=" + v));
        return line.toString();
    }

    /**
     * Decodes one line (with any trailing line terminator already removed).
     *
     * @throws MalformedLineException if the line is not exactly one valid message
     */
    public static Message decode(String line) throws MalformedLineException {
        if (line == null || line.isEmpty()) {
            throw new MalformedLineException(String.valueOf(line), "empty line");
        }
        String[] f = line.split("\\|", -1);
        Reader r = new Reader(line, f);
        if (f[0].indexOf('=') >= 0) {
            return decodePairs(r);
        }
        return switch (f[0]) {
            case "PUBLISH" -> {
                r.expectFields(7);
                yield r.build(() -> new Publish(r.uuid(1), f[2], r.number(3), r.product(4),
                        r.number(5), r.instant(6)));
            }
            case "ACK" -> {
                r.expectFields(4);
                yield r.build(() -> new Ack(AckKind.fromWire(f[1]), f[2], r.number(3)));
            }
            case "SALE" -> {
                r.expectFields(3);
                yield r.build(() -> new Sale(r.product(1), r.integer(2)));
            }
            case "OK" -> {
                if (f.length == 1) {
                    yield new Ok();
                }
                r.expectFields(3);
                yield r.build(() -> new SaleRecorded(r.uuid(1), r.number(2)));
            }
            case "STATUS" -> {
                r.expectFields(1);
                yield new StatusRequest();
            }
            case "SET" -> {
                r.expectFields(3);
                yield r.build(() -> new SetFault(FaultSetting.fromWire(f[1]), r.integer(2)));
            }
            case "CUT" -> {
                r.expectFields(1);
                yield new Cut();
            }
            case "RESTORE" -> {
                r.expectFields(1);
                yield new Restore();
            }
            case "ERR" -> {
                r.expectFields(2);
                yield r.build(() -> new ErrorReply(f[1]));
            }
            default -> throw new MalformedLineException(line, "unknown message type '" + f[0] + "'");
        };
    }

    /** A status reply: every field is {@code key=value}; a key may appear once. */
    private static StatusReply decodePairs(Reader r) throws MalformedLineException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : r.f()) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw new MalformedLineException(r.line(), "status field '" + pair + "' is not key=value");
            }
            if (values.put(pair.substring(0, eq), pair.substring(eq + 1)) != null) {
                throw new MalformedLineException(r.line(), "status key '" + pair.substring(0, eq) + "' repeats");
            }
        }
        return r.build(() -> new StatusReply(values));
    }

    /** Field parsing that reports every failure as a {@link MalformedLineException}. */
    private record Reader(String line, String[] f) {

        void expectFields(int count) throws MalformedLineException {
            if (f.length != count) {
                throw new MalformedLineException(line,
                        f[0] + " needs " + count + " fields, found " + f.length);
            }
        }

        UUID uuid(int i) throws MalformedLineException {
            try {
                UUID id = UUID.fromString(f[i]);
                if (!id.toString().equals(f[i])) {
                    throw new IllegalArgumentException("not in canonical form");
                }
                return id;
            } catch (IllegalArgumentException e) {
                throw new MalformedLineException(line, "field " + i + " is not a UUID", e);
            }
        }

        long number(int i) throws MalformedLineException {
            try {
                return Long.parseLong(f[i]);
            } catch (NumberFormatException e) {
                throw new MalformedLineException(line, "field " + i + " is not an integer", e);
            }
        }

        int integer(int i) throws MalformedLineException {
            try {
                return Integer.parseInt(f[i]);
            } catch (NumberFormatException e) {
                throw new MalformedLineException(line, "field " + i + " is not an integer", e);
            }
        }

        Product product(int i) throws MalformedLineException {
            try {
                return Product.valueOf(f[i]);
            } catch (IllegalArgumentException e) {
                throw new MalformedLineException(line, "field " + i + " is not a product P1-P5", e);
            }
        }

        Instant instant(int i) throws MalformedLineException {
            if (!f[i].endsWith("Z")) {
                throw new MalformedLineException(line, "field " + i + " is not in UTC (must end in Z)");
            }
            try {
                return Instant.parse(f[i]);
            } catch (DateTimeParseException e) {
                throw new MalformedLineException(line, "field " + i + " is not an ISO-8601 UTC instant", e);
            }
        }

        /** Runs a record constructor and turns its range checks into a malformed-line error. */
        <T extends Message> T build(Builder<T> builder) throws MalformedLineException {
            try {
                return builder.build();
            } catch (IllegalArgumentException e) {
                throw new MalformedLineException(line, e.getMessage(), e);
            }
        }
    }

    @FunctionalInterface
    private interface Builder<T> {
        T build() throws MalformedLineException;
    }
}
