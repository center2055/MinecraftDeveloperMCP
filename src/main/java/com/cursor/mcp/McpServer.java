package com.cursor.mcp;

import com.cursor.mcp.protocol.McpProtocol;
import io.javalin.Javalin;
import io.javalin.http.sse.SseClient;
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
                
                // Send the endpoint URI event as per MCP HTTP transport
                // The client will use this to POST messages
                client.sendEvent("endpoint", "/messages?sessionId=" + sessionId);
                
                client.onClose(() -> sessions.remove(sessionId));
            });

            app.post("/messages", ctx -> {
                String sessionId = ctx.queryParam("sessionId");
                if (sessionId == null || !sessions.containsKey(sessionId)) {
                    ctx.status(400).result("Invalid or missing sessionId");
                    return;
                }

                String body = ctx.body();
                // Process request
                String response = protocol.handleRequest(body);
                
                if (response != null) {
                    SseClient client = sessions.get(sessionId);
                    if (client != null) {
                        // Send JSON-RPC response via SSE
                        client.sendEvent("message", response);
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

