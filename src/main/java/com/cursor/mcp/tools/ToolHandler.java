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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
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
        commandSchema.set("required", mapper.createArrayNode().add("command"));
        ObjectNode commandProps = mapper.createObjectNode();
        commandProps.set("command", mapper.createObjectNode().put("type", "string"));
        commandSchema.set("properties", commandProps);
        addTool(tools, "execute_command", "Execute a Minecraft command", commandSchema);

        ObjectNode readFileSchema = mapper.createObjectNode();
        readFileSchema.put("type", "object");
        readFileSchema.set("required", mapper.createArrayNode().add("path"));
        ObjectNode readFileProps = mapper.createObjectNode();
        readFileProps.set("path", mapper.createObjectNode().put("type", "string"));
        readFileSchema.set("properties", readFileProps);
        addTool(tools, "read_file", "Read a file from the server", readFileSchema);

        ObjectNode writeFileSchema = mapper.createObjectNode();
        writeFileSchema.put("type", "object");
        writeFileSchema.set("required", mapper.createArrayNode().add("path").add("content"));
        ObjectNode writeFileProps = mapper.createObjectNode();
        writeFileProps.set("path", mapper.createObjectNode().put("type", "string"));
        writeFileProps.set("content", mapper.createObjectNode().put("type", "string"));
        writeFileSchema.set("properties", writeFileProps);
        addTool(tools, "write_file", "Write to a file on the server", writeFileSchema);
        
        addTool(tools, "list_plugins", "List installed plugins", mapper.createObjectNode().put("type", "object"));
        addTool(tools, "get_logs", "Get recent log lines", mapper.createObjectNode().put("type", "object"));

        // Binary file tools
        ObjectNode readBinarySchema = mapper.createObjectNode();
        readBinarySchema.put("type", "object");
        readBinarySchema.set("required", mapper.createArrayNode().add("path"));
        ObjectNode readBinaryProps = mapper.createObjectNode();
        readBinaryProps.set("path", mapper.createObjectNode().put("type", "string"));
        readBinarySchema.set("properties", readBinaryProps);
        addTool(tools, "read_file_base64", "Read a binary file and return as base64", readBinarySchema);

        ObjectNode writeBinarySchema = mapper.createObjectNode();
        writeBinarySchema.put("type", "object");
        writeBinarySchema.set("required", mapper.createArrayNode().add("path").add("content"));
        ObjectNode writeBinaryProps = mapper.createObjectNode();
        writeBinaryProps.set("path", mapper.createObjectNode().put("type", "string"));
        writeBinaryProps.set("content", mapper.createObjectNode().put("type", "string").put("description", "Base64 encoded file content"));
        writeBinarySchema.set("properties", writeBinaryProps);
        addTool(tools, "write_file_base64", "Write a binary file from base64 encoded content", writeBinarySchema);

        // List directory tool
        ObjectNode listDirSchema = mapper.createObjectNode();
        listDirSchema.put("type", "object");
        listDirSchema.set("required", mapper.createArrayNode().add("path"));
        ObjectNode listDirProps = mapper.createObjectNode();
        listDirProps.set("path", mapper.createObjectNode().put("type", "string"));
        listDirSchema.set("properties", listDirProps);
        addTool(tools, "list_directory", "List files and directories in a path", listDirSchema);

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
            case "read_file_base64":
                return readFileBase64(args.get("path").asText());
            case "write_file_base64":
                return writeFileBase64(args.get("path").asText(), args.get("content").asText());
            case "list_directory":
                return listDirectory(args.has("path") ? args.get("path").asText() : ".");
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
                // First try with custom sender (captures output directly)
                McpCommandSender sender = new McpCommandSender(Bukkit.getConsoleSender());
                try {
                    Bukkit.dispatchCommand(sender, command);
                    String output = sender.getOutput();
                    if (output.isEmpty()) {
                        output = "Command executed (no output captured).";
                    }
                    future.complete(output);
                } catch (IllegalArgumentException e) {
                    // Some plugins (like LuckPerms) reject custom senders
                    // Fall back to real console sender with log capture
                    if (e.getMessage() != null && e.getMessage().contains("vanilla command listener")) {
                        String output = executeWithLogCapture(command);
                        future.complete(output);
                    } else {
                        throw e;
                    }
                }
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

    private String executeWithLogCapture(String command) {
        // Capture log output during command execution
        List<String> capturedLogs = new CopyOnWriteArrayList<>();
        
        Handler logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getMessage() != null) {
                    capturedLogs.add(record.getMessage());
                }
            }
            @Override
            public void flush() {}
            @Override
            public void close() throws SecurityException {}
        };
        
        // Add handler to capture logs
        java.util.logging.Logger bukkitLogger = Bukkit.getLogger();
        bukkitLogger.addHandler(logHandler);
        
        try {
            // Execute with real console sender
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            
            // Small delay to let async log messages come through
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            
            if (capturedLogs.isEmpty()) {
                return "Command executed successfully (via console).";
            }
            return String.join("\n", capturedLogs);
        } finally {
            bukkitLogger.removeHandler(logHandler);
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

    private ObjectNode readFileBase64(String pathStr) throws Exception {
        Path path = serverRoot.resolve(pathStr).normalize();
        if (!path.startsWith(serverRoot)) {
            throw new SecurityException("Access denied: Path is outside server root.");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + pathStr);
        }
        
        byte[] bytes = Files.readAllBytes(path);
        String base64 = Base64.getEncoder().encodeToString(bytes);
        return createTextResult(base64);
    }

    private ObjectNode writeFileBase64(String pathStr, String base64Content) throws Exception {
        Path path = serverRoot.resolve(pathStr).normalize();
        if (!path.startsWith(serverRoot)) {
            throw new SecurityException("Access denied: Path is outside server root.");
        }
        
        byte[] bytes = Base64.getDecoder().decode(base64Content);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return createTextResult("Binary file written successfully to " + pathStr + " (" + bytes.length + " bytes)");
    }

    private ObjectNode listDirectory(String pathStr) throws Exception {
        Path path = serverRoot.resolve(pathStr).normalize();
        if (!path.startsWith(serverRoot)) {
            throw new SecurityException("Access denied: Path is outside server root.");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Directory not found: " + pathStr);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Not a directory: " + pathStr);
        }
        
        StringBuilder sb = new StringBuilder();
        try (var stream = Files.list(path)) {
            stream.sorted().forEach(p -> {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    sb.append("[DIR]  ").append(name).append("/\n");
                } else {
                    try {
                        long size = Files.size(p);
                        sb.append("[FILE] ").append(name).append(" (").append(formatSize(size)).append(")\n");
                    } catch (Exception e) {
                        sb.append("[FILE] ").append(name).append("\n");
                    }
                }
            });
        }
        return createTextResult(sb.toString());
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}

