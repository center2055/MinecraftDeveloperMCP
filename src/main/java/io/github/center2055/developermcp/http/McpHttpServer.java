package io.github.center2055.developermcp.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.center2055.developermcp.protocol.McpProtocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves MCP over HTTP using the JDK's built-in server, so the plugin ships without Jetty, Javalin or Kotlin.
 *
 * <ul>
 *   <li>{@code POST /mcp} - Streamable HTTP transport (current MCP spec)</li>
 *   <li>{@code GET /sse} + {@code POST /messages} - HTTP+SSE transport (MCP 2024-11-05, older clients)</li>
 *   <li>{@code POST /api} - same as /mcp, for scripts and curl</li>
 * </ul>
 */
public final class McpHttpServer {

    static final long MAX_BODY_BYTES = 64L * 1024 * 1024;
    private static final int MAX_SSE_SESSIONS = 32;

    private final Logger logger;
    private final McpProtocol protocol;
    private final AuthGuard auth;
    private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executor;

    public McpHttpServer(Logger logger, McpProtocol protocol, AuthGuard auth) {
        this.logger = logger;
        this.protocol = protocol;
        this.auth = auth;
    }

    public void start(String host, int port) throws IOException {
        InetSocketAddress address = host == null || host.isBlank() || host.equals("0.0.0.0") || host.equals("*")
                ? new InetSocketAddress(port) : new InetSocketAddress(host, port);
        server = HttpServer.create(address, 64);
        AtomicInteger n = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "DeveloperMCP-HTTP-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        executor = Executors.newCachedThreadPool(factory);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public void stop() {
        for (SseSession s : sessions.values()) {
            s.close();
        }
        sessions.clear();
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public int openSessions() {
        return sessions.size();
    }

    private void handle(HttpExchange ex) {
        try {
            addCors(ex.getResponseHeaders());
            String method = ex.getRequestMethod().toUpperCase();
            String path = ex.getRequestURI().getPath();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (method.equals("OPTIONS")) {
                send(ex, 204, null, null);
                return;
            }
            if (method.equals("GET") && path.equals("/")) {
                send(ex, 200, "text/plain; charset=utf-8",
                        "DeveloperMCP is running. Point your MCP client at /mcp (or /sse for older clients).");
                return;
            }

            Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
            String sessionId = query.get("sessionId");
            boolean sessionAuth = path.equals("/messages") && sessionId != null && sessions.containsKey(sessionId);
            if (!sessionAuth) {
                AuthGuard.Result result = auth.check(clientAddress(ex), bearer(ex), query.get("token"));
                if (result == AuthGuard.Result.BLOCKED) {
                    sendJson(ex, 429, "{\"error\":\"Too many requests with a wrong token. Try again later.\"}");
                    return;
                }
                if (result == AuthGuard.Result.DENIED) {
                    sendJson(ex, 401, "{\"error\":\"Unauthorized: missing or wrong token. Send 'Authorization: Bearer <token>'.\"}");
                    return;
                }
            }

            switch (path) {
                case "/mcp", "/api", "/" -> {
                    if (method.equals("POST")) {
                        handleRpc(ex);
                    } else {
                        // Spec: GET on the MCP endpoint is either an SSE stream or 405. We don't push messages.
                        ex.getResponseHeaders().set("Allow", "POST, OPTIONS");
                        send(ex, 405, null, null);
                    }
                }
                case "/sse" -> {
                    if (method.equals("GET")) {
                        handleSse(ex);
                    } else {
                        ex.getResponseHeaders().set("Allow", "GET, OPTIONS");
                        send(ex, 405, null, null);
                    }
                }
                case "/messages" -> {
                    if (!method.equals("POST")) {
                        ex.getResponseHeaders().set("Allow", "POST, OPTIONS");
                        send(ex, 405, null, null);
                    } else if (!sessionAuth) {
                        sendJson(ex, 404, "{\"error\":\"Unknown or expired sessionId. Reconnect to /sse.\"}");
                    } else {
                        handleSessionMessage(ex, sessions.get(sessionId));
                    }
                }
                default -> sendJson(ex, 404, "{\"error\":\"Not found. The MCP endpoint is /mcp.\"}");
            }
        } catch (BodyTooLargeException e) {
            trySend(ex, 413, "{\"error\":\"Request body too large\"}");
        } catch (Exception e) {
            logger.log(Level.WARNING, "HTTP request failed", e);
            trySend(ex, 500, "{\"error\":\"Internal server error\"}");
        } finally {
            ex.close();
        }
    }

    private void handleRpc(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        String response = protocol.handle(body, clientAddress(ex));
        if (response == null) {
            send(ex, 202, null, null);
        } else {
            sendJson(ex, 200, response);
        }
    }

    private void handleSse(HttpExchange ex) throws IOException {
        if (sessions.size() >= MAX_SSE_SESSIONS) {
            sendJson(ex, 503, "{\"error\":\"Too many open SSE sessions\"}");
            return;
        }
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream");
        h.set("Cache-Control", "no-cache");
        h.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        String id = UUID.randomUUID().toString();
        SseSession session = new SseSession(id, ex.getResponseBody());
        sessions.put(id, session);
        logger.info("MCP client connected over SSE from " + clientAddress(ex));
        try {
            // The session id authorizes the follow-up POSTs, so the token never needs to appear in this URL.
            if (session.send("endpoint", "/messages?sessionId=" + id)) {
                session.awaitClose();
            }
        } finally {
            sessions.remove(id);
            session.close();
            logger.info("MCP SSE client disconnected (" + clientAddress(ex) + ")");
        }
    }

    private void handleSessionMessage(HttpExchange ex, SseSession session) throws IOException {
        String body = readBody(ex);
        // Acknowledge right away; the JSON-RPC answer goes out over the SSE stream.
        send(ex, 202, null, null);
        String response = protocol.handle(body, clientAddress(ex));
        if (response != null) {
            session.send("message", response);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            long total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    throw new BodyTooLargeException();
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static String bearer(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return null;
        }
        header = header.trim();
        return header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : header;
    }

    private static String clientAddress(HttpExchange ex) {
        InetSocketAddress remote = ex.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            map.putIfAbsent(key, value);
        }
        return map;
    }

    private static void addCors(Headers h) {
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        h.set("Access-Control-Allow-Headers",
                "Authorization, Content-Type, Accept, Mcp-Session-Id, Mcp-Protocol-Version, Last-Event-ID");
        h.set("Access-Control-Expose-Headers", "Mcp-Session-Id");
        h.set("Access-Control-Max-Age", "600");
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        send(ex, status, "application/json; charset=utf-8", json);
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        if (body == null) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void trySend(HttpExchange ex, int status, String json) {
        try {
            sendJson(ex, status, json);
        } catch (IOException | RuntimeException ignored) {
            // headers were already sent or the client is gone
        }
    }

    private static final class BodyTooLargeException extends IOException {
        BodyTooLargeException() {
            super("body too large");
        }
    }
}
