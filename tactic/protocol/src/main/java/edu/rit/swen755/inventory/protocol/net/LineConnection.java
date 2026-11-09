package edu.rit.swen755.inventory.protocol.net;

import edu.rit.swen755.inventory.protocol.LineCodec;
import edu.rit.swen755.inventory.protocol.MalformedLineException;
import edu.rit.swen755.inventory.protocol.Message;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** One TCP connection carrying UTF-8 lines, each terminated by {@code \n}. */
public final class LineConnection implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;

    /** Takes ownership of {@code socket}: it is closed here if its streams cannot be opened. */
    public LineConnection(Socket socket) throws IOException {
        this.socket = socket;
        try {
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    /** The next line without its terminator, or {@code null} once the peer has closed. */
    public String readLine() throws IOException {
        return in.readLine();
    }

    /** The next message, or {@code null} once the peer has closed. */
    public Message receive() throws IOException, MalformedLineException {
        String line = readLine();
        return line == null ? null : LineCodec.decode(line);
    }

    /** Writes one line and flushes it. */
    public void writeLine(String line) throws IOException {
        synchronized (out) {
            out.write(line);
            out.write('\n');
            out.flush();
        }
    }

    /** Encodes and writes one message. */
    public void send(Message message) throws IOException {
        writeLine(LineCodec.encode(message));
    }

    public Socket socket() {
        return socket;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
