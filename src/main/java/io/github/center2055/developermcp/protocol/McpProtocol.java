package io.github.center2055.developermcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.center2055.developermcp.tools.Tool;
import io.github.center2055.developermcp.tools.ToolException;
import io.github.center2055.developermcp.tools.ToolRegistry;
import io.github.center2055.developermcp.tools.ToolResult;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** JSON-RPC 2.0 handling for the Model Context Protocol. Transport independent. */
public final class McpProtocol {

    /** Newest first. The client's requested version is used when we support it. */
    public static final List<String> SUPPORTED_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    private static final String INSTRUCTIONS = "You are connected to a live Minecraft server (Spigot/Paper). "
            + "File paths are relative to the server folder, the one containing server.properties. "
            + "Everything you do happens on the real server right away, so double-check destructive actions "
            + "such as stop, ban, kill, op, or overwriting and deleting files. "
            + "Start with server_info for an overview and use get_logs to check what happened.";

    /** Notified before every tool call, for the audit log. */
    public interface CallListener {
        void onCall(String client, String tool, JsonObject arguments);
    }

    private final ToolRegistry tools;
    private final String serverVersion;
    private final Logger logger;
    private final CallListener listener;

    public McpProtocol(ToolRegistry tools, String serverVersion, Logger logger, CallListener listener) {
        this.tools = tools;
        this.serverVersion = serverVersion;
        this.logger = logger;
        this.listener = listener;
    }

    /** Handles one HTTP body (a single message or a batch). Returns the JSON to send back, or null for none. */
    public String handle(String body, String client) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body == null ? "" : body);
        } catch (JsonParseException | IllegalStateException e) {
            return error(JsonNull.INSTANCE, PARSE_ERROR, "Parse error: " + e.getMessage()).toString();
        }
        if (parsed.isJsonArray()) {
            JsonArray responses = new JsonArray();
            for (JsonElement element : parsed.getAsJsonArray()) {
                JsonObject response = handleOne(element, client);
                if (response != null) {
                    responses.add(response);
                }
            }
            return responses.size() == 0 ? null : responses.toString();
        }
        JsonObject response = handleOne(parsed, client);
        return response == null ? null : response.toString();
    }

    JsonObject handleOne(JsonElement element, String client) {
        if (!element.isJsonObject()) {
            return error(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid request: expected a JSON object");
        }
        JsonObject message = element.getAsJsonObject();
        JsonElement id = message.get("id");
        boolean isNotification = id == null;

        if (!message.has("method")) {
            // A response from the client (we never send requests) or garbage. Nothing to answer.
            return isNotification || message.has("result") || message.has("error") ? null
                    : error(id, INVALID_REQUEST, "Invalid request: missing method");
        }
        String method;
        try {
            method = message.get("method").getAsString();
        } catch (RuntimeException e) {
            return isNotification ? null : error(id, INVALID_REQUEST, "Invalid request: method must be a string");
        }
        JsonObject params = message.has("params") && message.get("params").isJsonObject()
                ? message.getAsJsonObject("params") : new JsonObject();

        if (isNotification) {
            return null; // notifications/initialized, notifications/cancelled, ... need no answer
        }

        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(params));
                case "ping" -> result(id, new JsonObject());
                case "tools/list" -> {
                    JsonObject r = new JsonObject();
                    r.add("tools", tools.describe());
                    yield result(id, r);
                }
                case "tools/call" -> callTool(id, params, client);
                case "resources/list", "resources/templates/list", "prompts/list" -> {
                    // Not offered, but some clients ask anyway. An empty list is friendlier than an error.
                    JsonObject r = new JsonObject();
                    String key = method.startsWith("prompts") ? "prompts"
                            : method.contains("templates") ? "resourceTemplates" : "resources";
                    r.add(key, new JsonArray());
                    yield result(id, r);
                }
                default -> error(id, METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Error handling MCP method " + method, e);
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
        String version = requested != null && SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0);

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", version);
        JsonObject capabilities = new JsonObject();
        JsonObject toolsCap = new JsonObject();
        toolsCap.addProperty("listChanged", false);
        capabilities.add("tools", toolsCap);
        result.add("capabilities", capabilities);
        JsonObject info = new JsonObject();
        info.addProperty("name", "DeveloperMCP");
        info.addProperty("title", "Minecraft DeveloperMCP");
        info.addProperty("version", serverVersion);
        result.add("serverInfo", info);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    private JsonObject callTool(JsonElement id, JsonObject params, String client) {
        if (!params.has("name") || !params.get("name").isJsonPrimitive()) {
            return error(id, INVALID_PARAMS, "tools/call needs a tool name");
        }
        String name = params.get("name").getAsString();
        Tool tool = tools.find(name);
        if (tool == null) {
            return error(id, INVALID_PARAMS, "Unknown tool: " + name);
        }
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        if (listener != null) {
            listener.onCall(client, name, args);
        }
        ToolResult outcome;
        try {
            outcome = tool.handler().call(args);
        } catch (ToolException e) {
            outcome = ToolResult.error(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = ToolResult.error("Interrupted.");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Tool " + name + " failed", e);
            outcome = ToolResult.error(name + " failed: " + e);
        }
        return result(id, outcome.toJson());
    }

    private static JsonObject result(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        return response;
    }

    static JsonObject error(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
    }
}
