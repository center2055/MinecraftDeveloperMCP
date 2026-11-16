package io.github.center2055.developermcp.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** One open Server-Sent Events stream of the legacy HTTP+SSE transport. */
final class SseSession {

    private static final long PING_MILLIS = 15_000;

    private final String id;
    private final OutputStream out;
    private boolean open = true;

    SseSession(String id, OutputStream out) {
        this.id = id;
        this.out = out;
    }

    String id() {
        return id;
    }

    synchronized boolean send(String event, String data) {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event).append('\n');
        for (String line : data.split("\n", -1)) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        return write(sb.toString());
    }

    private synchronized boolean write(String text) {
        if (!open) {
            return false;
        }
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    /** Blocks until the client disconnects, sending a comment every 15 s so proxies keep the stream open. */
    void awaitClose() {
        synchronized (this) {
            while (open) {
                try {
                    wait(PING_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    close();
                    return;
                }
                if (open) {
                    write(": ping\n\n");
                }
            }
        }
    }

    synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        try {
            out.close();
        } catch (IOException ignored) {
            // already gone
        }
        notifyAll();
    }
}
