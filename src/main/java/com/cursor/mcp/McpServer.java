package com.cursor.mcp;

import com.cursor.mcp.protocol.McpProtocol;
import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class McpServer {
    private final McpPlugin plugin;
    private final int port;
    private final String token;
    private Javalin app;
    private final McpProtocol protocol;
    private final Map<String, SseClient> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public McpServer(McpPlugin plugin, int port, String token) {
        this.plugin = plugin;
        this.port = port;
        this.token = token;
        this.protocol = new McpProtocol(plugin);
    }

    public void start() {
        // Spigot classloader fix for Javalin/Jetty
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(McpPlugin.class.getClassLoader());
        try {
            app = Javalin.create(config -> {
                config.showJavalinBanner = false;
                config.bundledPlugins.enableCors(cors -> {
                    cors.addRule(it -> {
                        it.anyHost();
                    });
                });
            });

            app.before(ctx -> {
                String auth = ctx.header("Authorization");
                String queryToken = ctx.queryParam("token");
                String validToken = "Bearer " + token;
                
                boolean authorized = (auth != null && auth.equals(validToken)) || 
                                     (queryToken != null && queryToken.equals(token));

                if (!authorized) {
                     ctx.status(401).result("Unauthorized");
                     ctx.skipRemainingHandlers();
                }
            });

            app.sse("/sse", client -> {
                String sessionId = UUID.randomUUID().toString();
                sessions.put(sessionId, client);
                client.keepAlive();
                
                plugin.getLogger().info("New SSE Client connected. Session ID: " + sessionId);
                
                // Send the endpoint URI event as per MCP HTTP transport
                // The client will use this to POST messages
                String endpoint = "/messages?sessionId=" + sessionId;
                client.sendEvent("endpoint", endpoint);
                
                client.onClose(() -> {
                    sessions.remove(sessionId);
                    plugin.getLogger().info("SSE Client disconnected. Session ID: " + sessionId);
                });
            });

            app.post("/messages", ctx -> {
                String sessionId = ctx.queryParam("sessionId");
                if (sessionId == null || !sessions.containsKey(sessionId)) {
                    ctx.status(400).result("Invalid or missing sessionId");
                    return;
                }

                String body = ctx.body();
                String method = null;
                try {
                    JsonNode request = mapper.readTree(body);
                    if (request.hasNonNull("method")) {
                        method = request.get("method").asText();
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to parse MCP request method: " + e.getMessage());
                }

                // Process request
                String response = protocol.handleRequest(body);
                
                if (response != null) {
                    SseClient client = sessions.get(sessionId);
                    if (client != null) {
                        // Send JSON-RPC response via SSE
                        client.sendEvent("message", response);
                    }
                }
                
                // Let clients know they should fetch the tool list. Some MCP clients
                // only request tools after receiving this notification when
                // capabilities.tools.listChanged is true.
                if ("initialize".equals(method)) {
                    SseClient client = sessions.get(sessionId);
                    if (client != null) {
                        client.sendEvent("message", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}");
                    }
                }
                
                ctx.status(202).result("Accepted");
            });

            // Simple synchronous API endpoint - returns response directly in HTTP response body
            // Much faster for scripting/automation as it doesn't require SSE connection management
            app.post("/api", ctx -> {
                String body = ctx.body();
                String response = protocol.handleRequest(body);
                
                if (response != null) {
                    ctx.contentType("application/json");
                    ctx.result(response);
                } else {
                    ctx.status(204); // No content for notifications
                }
            });

            // Streamable HTTP transport endpoint (newer MCP standard)
            // This handles both GET (for SSE stream) and POST (for messages) on the same endpoint
            app.post("/mcp", ctx -> {
                String body = ctx.body();
                String response = protocol.handleRequest(body);
                
                if (response != null) {
                    ctx.contentType("application/json");
                    ctx.result(response);
                } else {
                    ctx.status(202).result("Accepted");
                }
            });

            // Also support GET on /mcp for clients that expect it
            app.get("/mcp", ctx -> {
                ctx.contentType("application/json");
                // Return server info for discovery
                ctx.result("{\"name\":\"MCPMinecraft\",\"version\":\"1.2.0\",\"transport\":\"streamable-http\"}");
            });

            app.start(port);
        } finally {
            Thread.currentThread().setContextClassLoader(classLoader);
        }
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }
}
