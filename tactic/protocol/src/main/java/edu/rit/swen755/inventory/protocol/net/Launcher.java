package edu.rit.swen755.inventory.protocol.net;

/** The shared body of every process's {@code main}: parse, start, print the READY line, run until killed. */
public final class Launcher {

    /** Parses the command line and starts the process. */
    @FunctionalInterface
    public interface Starter {
        RunningProcess start(String[] args) throws Exception;
    }

    private Launcher() {
    }

    /**
     * Starts the process and blocks until the JVM is stopped. A bad command line exits with status
     * 2 and a startup failure (for example a port in use) with status 1.
     */
    public static void run(String name, String usage, String[] args, Starter starter) {
        RunningProcess process;
        try {
            process = starter.start(args);
        } catch (IllegalArgumentException e) {
            System.err.println(name + ": " + e.getMessage());
            System.err.println("usage: java -jar " + name + ".jar " + usage);
            System.exit(2);
            return;
        } catch (Exception e) {
            System.err.println(name + ": could not start: " + e);
            System.exit(1);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(process::close, name + "-shutdown"));
        System.out.println(process.readyLine());
        System.out.flush();
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
