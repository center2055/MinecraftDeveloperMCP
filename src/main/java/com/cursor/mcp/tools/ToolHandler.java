package com.cursor.mcp.tools;

import com.cursor.mcp.McpPlugin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class ToolHandler {
    private final McpPlugin plugin;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path serverRoot;

    public ToolHandler(McpPlugin plugin) {
        this.plugin = plugin;
        this.serverRoot = new File(".").toPath().toAbsolutePath().normalize();
    }

    public ObjectNode listTools() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        ObjectNode commandSchema = mapper.createObjectNode();
        commandSchema.put("type", "object");
        ObjectNode commandProps = mapper.createObjectNode();
        commandProps.set("command", mapper.createObjectNode().put("type", "string"));
        commandSchema.set("properties", commandProps);
        addTool(tools, "execute_command", "Execute a Minecraft command", commandSchema);

        ObjectNode readFileSchema = mapper.createObjectNode();
        readFileSchema.put("type", "object");
        ObjectNode readFileProps = mapper.createObjectNode();
        readFileProps.set("path", mapper.createObjectNode().put("type", "string"));
        readFileSchema.set("properties", readFileProps);
        addTool(tools, "read_file", "Read a file from the server", readFileSchema);

        ObjectNode writeFileSchema = mapper.createObjectNode();
        writeFileSchema.put("type", "object");
        ObjectNode writeFileProps = mapper.createObjectNode();
        writeFileProps.set("path", mapper.createObjectNode().put("type", "string"));
        writeFileProps.set("content", mapper.createObjectNode().put("type", "string"));
        writeFileSchema.set("properties", writeFileProps);
        addTool(tools, "write_file", "Write to a file on the server", writeFileSchema);
        
        addTool(tools, "list_plugins", "List installed plugins", mapper.createObjectNode().put("type", "object"));
        addTool(tools, "get_logs", "Get recent log lines", mapper.createObjectNode().put("type", "object"));

        return result;
    }

    private void addTool(ArrayNode tools, String name, String description, JsonNode schema) {
        ObjectNode tool = tools.addObject();
        tool.put("name", name);
        tool.put("description", description);
        tool.set("inputSchema", schema);
    }

    public Object callTool(String name, JsonNode args) throws Exception {
        switch (name) {
            case "execute_command":
                return executeCommand(args.get("command").asText());
            case "read_file":
                return readFile(args.get("path").asText());
            case "write_file":
                return writeFile(args.get("path").asText(), args.get("content").asText());
            case "list_plugins":
                return listPlugins();
            case "get_logs":
                return getLogs();
            default:
                throw new IllegalArgumentException("Unknown tool: " + name);
        }
    }

    private ObjectNode createTextResult(String text) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        content.addObject().put("type", "text").put("text", text);
        return result;
    }

    private ObjectNode executeCommand(String command) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                McpCommandSender sender = new McpCommandSender(Bukkit.getConsoleSender());
                Bukkit.dispatchCommand(sender, command);
                String output = sender.getOutput();
                if (output.isEmpty()) {
                    output = "Command executed (no output captured).";
                }
                future.complete(output);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return createTextResult(future.get(10, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            return createTextResult("Command sent but response timed out. The command may still have executed.");
        }
    }

    private ObjectNode readFile(String pathStr) throws Exception {
        Path path = serverRoot.resolve(pathStr).normalize();
        if (!path.startsWith(serverRoot)) {
            throw new SecurityException("Access denied: Path is outside server root.");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + pathStr);
        }
        
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return createTextResult(content);
    }

    private ObjectNode writeFile(String pathStr, String content) throws Exception {
        Path path = serverRoot.resolve(pathStr).normalize();
        if (!path.startsWith(serverRoot)) {
            throw new SecurityException("Access denied: Path is outside server root.");
        }
        
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return createTextResult("File written successfully to " + pathStr);
    }

    private ObjectNode listPlugins() throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                StringBuilder sb = new StringBuilder();
                for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                    sb.append(p.getName()).append(" (").append(p.getDescription().getVersion()).append(")");
                    if (!p.isEnabled()) sb.append(" [DISABLED]");
                    sb.append("\n");
                }
                future.complete(sb.toString());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return createTextResult(future.get(10, TimeUnit.SECONDS));
    }

    private ObjectNode getLogs() throws Exception {
        Path logPath = serverRoot.resolve("logs/latest.log");
        if (!Files.exists(logPath)) {
             return createTextResult("No latest.log found.");
        }
        
        List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
        int start = Math.max(0, lines.size() - 100);
        String recentLogs = lines.subList(start, lines.size()).stream().collect(Collectors.joining("\n"));
        return createTextResult(recentLogs);
    }
}

