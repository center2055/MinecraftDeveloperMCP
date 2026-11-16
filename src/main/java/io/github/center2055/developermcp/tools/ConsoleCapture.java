package io.github.center2055.developermcp.tools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Collects console lines while a command runs. Command feedback on Spigot and Paper is written straight to
 * Log4j (not through Bukkit's java.util.logging logger), so we listen on the root Log4j logger.
 */
final class ConsoleCapture extends AbstractAppender implements AutoCloseable {

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)§[0-9A-FK-ORX]");
    private static final int MAX_LINES = 2000;
    private static final String SYSTEM_CHAT = "System chat: ";

    private final List<String> lines = new ArrayList<>();
    private volatile long lastLineNanos = System.nanoTime();
    private Logger root;

    private ConsoleCapture() {
        super("DeveloperMCP-Capture-" + COUNTER.incrementAndGet(), null, null, true, new Property[0]);
    }

    static ConsoleCapture attach() {
        ConsoleCapture capture = new ConsoleCapture();
        capture.start();
        capture.root = (Logger) LogManager.getRootLogger();
        capture.root.addAppender(capture);
        return capture;
    }

    @Override
    public void append(LogEvent event) {
        if (event.getMessage() == null) {
            return;
        }
        String text = clean(event.getMessage().getFormattedMessage());
        synchronized (lines) {
            if (lines.size() < MAX_LINES) {
                lines.add(text);
                if (event.getThrown() != null) {
                    lines.add(event.getThrown().toString());
                }
            }
        }
        lastLineNanos = System.nanoTime();
    }

    boolean isEmpty() {
        synchronized (lines) {
            return lines.isEmpty();
        }
    }

    long lastLineNanos() {
        return lastLineNanos;
    }

    String text() {
        synchronized (lines) {
            return String.join("\n", lines);
        }
    }

    static String clean(String message) {
        if (message == null) {
            return "";
        }
        String text = LEGACY_COLOR.matcher(ANSI.matcher(message).replaceAll("")).replaceAll("");
        // Paper 26.x logs command feedback as "System chat: <message>".
        return text.startsWith(SYSTEM_CHAT) ? text.substring(SYSTEM_CHAT.length()) : text;
    }

    @Override
    public void close() {
        if (root != null) {
            root.removeAppender(this);
        }
        stop();
    }
}
