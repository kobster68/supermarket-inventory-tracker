package edu.rit.swen755.inventory.protocol.net;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Parses {@code --name value} options. Every option is optional; an unknown one is an error. */
public final class CommandLine {

    private final Map<String, String> values;

    private CommandLine(Map<String, String> values) {
        this.values = values;
    }

    /**
     * @param allowed option names without the leading {@code --}
     * @throws IllegalArgumentException for an unknown option, a repeated one, or one without a value
     */
    public static CommandLine parse(String[] args, Set<String> allowed) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            String arg = args[i];
            if (!arg.startsWith("--") || !allowed.contains(arg.substring(2))) {
                throw new IllegalArgumentException("unknown option '" + arg + "'; options: --"
                        + String.join(" --", allowed.stream().sorted().toList()));
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(arg + " needs a value");
            }
            if (values.put(arg.substring(2), args[i + 1]) != null) {
                throw new IllegalArgumentException(arg + " is given twice");
            }
        }
        return new CommandLine(values);
    }

    /** A TCP port, 0 to 65535; 0 asks for a free port. */
    public int port(String name, int fallback) {
        String text = values.get(name);
        if (text == null) {
            return fallback;
        }
        try {
            int port = Integer.parseInt(text);
            if (port >= 0 && port <= 65_535) {
                return port;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new IllegalArgumentException("--" + name + " must be a port 0-65535, was '" + text + "'");
    }

    /** A folder path, used as given (relative paths resolve against the working directory). */
    public Path path(String name, Path fallback) {
        String text = values.get(name);
        return text == null ? fallback : Path.of(text);
    }

    public String text(String name, String fallback) {
        return values.getOrDefault(name, fallback);
    }
}
