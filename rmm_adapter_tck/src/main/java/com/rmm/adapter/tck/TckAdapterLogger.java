package com.rmm.adapter.tck;

import com.rmm.adapter.api.AdapterLogger;

import java.io.PrintStream;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * {@code logger()} of a connection: writes to the console and into {@code lines}, including the message of a
 * cause, masked as in the host.
 */
record TckAdapterLogger(String systemKey, List<String> lines, UnaryOperator<String> mask) implements AdapterLogger {

    @Override
    public void info(String message) {
        write(System.out, "", message);
    }

    @Override
    public void warn(String message) {
        write(System.out, "WARN ", message);
    }

    @Override
    public void error(String message) {
        write(System.err, "ERROR ", message);
    }

    @Override
    public void error(String message, Throwable cause) {
        write(System.err, "ERROR ", message + " " + cause);
    }

    private void write(PrintStream console, String level, String text) {
        String masked = mask.apply(text);
        lines.add(masked);
        console.println("[TCK " + systemKey + "] " + level + masked);
    }

    @Override
    public String toString() {
        return "ConsoleLogger[" + systemKey + "]";
    }
}
