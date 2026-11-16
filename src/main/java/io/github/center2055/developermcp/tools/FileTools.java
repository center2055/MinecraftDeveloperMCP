package io.github.center2055.developermcp.tools;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/** File tools. Every path is checked against the server folder first. Has no Bukkit dependency. */
public final class FileTools {

    private static final int MAX_LIST_ENTRIES = 1000;

    private final PathSandbox sandbox;
    private final LongSupplier maxReadBytes;

    public FileTools(PathSandbox sandbox, LongSupplier maxReadBytes) {
        this.sandbox = sandbox;
        this.maxReadBytes = maxReadBytes;
    }

    public List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool("list_directory", "List directory",
                "List the files and folders in a directory of the Minecraft server, with file sizes. "
                        + "Paths are relative to the server folder (where server.properties is).",
                Args.Schema.object()
                        .string("path", "Directory to list, e.g. \"plugins\" or \"world\". Defaults to the server folder.", false)
                        .build(),
                Tool.readOnly(), this::listDirectory));
        tools.add(new Tool("read_file", "Read text file",
                "Read a text file from the server, such as a config, script or log. "
                        + "For big files, read part of it with startLine and lineCount.",
                Args.Schema.object()
                        .string("path", "File path relative to the server folder, e.g. \"plugins/Essentials/config.yml\".", true)
                        .integer("startLine", "First line to return, starting at 1. Optional.", false)
                        .integer("lineCount", "How many lines to return from startLine. Optional.", false)
                        .build(),
                Tool.readOnly(), this::readFile));
        tools.add(new Tool("write_file", "Write text file",
                "Create or overwrite a text file on the server (missing folders are created). "
                        + "Set append to true to add to the end instead. Many plugins need /reload or their own reload "
                        + "command before they notice config changes.",
                Args.Schema.object()
                        .string("path", "File path relative to the server folder.", true)
                        .string("content", "The complete new file content (or the text to append).", true)
                        .bool("append", "Append to the file instead of replacing it. Defaults to false.", false)
                        .build(),
                Tool.changesThings(true, false), this::writeFile));
        tools.add(new Tool("read_file_base64", "Read binary file",
                "Download any file, including binary ones such as jars, images or world data, as base64.",
                Args.Schema.object()
                        .string("path", "File path relative to the server folder.", true)
                        .build(),
                Tool.readOnly(), this::readFileBase64));
        tools.add(new Tool("write_file_base64", "Write binary file",
                "Upload a binary file, such as a plugin jar, from base64. Overwrites an existing file. "
                        + "New plugin jars are loaded on the next server restart.",
                Args.Schema.object()
                        .string("path", "File path relative to the server folder, e.g. \"plugins/MyPlugin.jar\".", true)
                        .string("content", "The file content, base64 encoded.", true)
                        .build(),
                Tool.changesThings(true, true), this::writeFileBase64));
        tools.add(new Tool("delete_file", "Delete file",
                "Delete a single file from the server, for example an outdated plugin jar. Folders are not deleted.",
                Args.Schema.object()
                        .string("path", "File path relative to the server folder.", true)
                        .build(),
                Tool.changesThings(true, true), this::deleteFile));
        return tools;
    }

    ToolResult listDirectory(JsonObject args) throws Exception {
        Path dir = sandbox.resolve(Args.optString(args, "path", "."));
        if (!Files.exists(dir)) {
            throw new ToolException("Directory not found: " + sandbox.display(dir));
        }
        if (!Files.isDirectory(dir)) {
            throw new ToolException(sandbox.display(dir) + " is a file, not a directory. Use read_file to read it.");
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                entries.add(p);
            }
        }
        entries.sort(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));

        StringBuilder sb = new StringBuilder(sandbox.display(dir)).append('/').append('\n');
        int shown = 0;
        for (Path p : entries) {
            if (shown++ >= MAX_LIST_ENTRIES) {
                sb.append("... and ").append(entries.size() - MAX_LIST_ENTRIES).append(" more\n");
                break;
            }
            String name = p.getFileName().toString();
            if (Files.isDirectory(p)) {
                sb.append("[DIR]  ").append(name).append("/\n");
            } else {
                sb.append("[FILE] ").append(name);
                try {
                    sb.append(" (").append(formatSize(Files.size(p))).append(')');
                } catch (IOException ignored) {
                    // size unknown
                }
                sb.append('\n');
            }
        }
        if (entries.isEmpty()) {
            sb.append("(empty)\n");
        }
        return ToolResult.ok(sb.toString());
    }

    ToolResult readFile(JsonObject args) throws Exception {
        Path file = existingFile(Args.requireString(args, "path"));
        int startLine = Args.optInt(args, "startLine", 1, 1, Integer.MAX_VALUE);
        int lineCount = Args.optInt(args, "lineCount", -1, 1, Integer.MAX_VALUE);
        boolean partial = args.has("startLine") || args.has("lineCount");

        long size = Files.size(file);
        long limit = maxReadBytes.getAsLong();
        if (!partial && size > limit) {
            throw new ToolException(sandbox.display(file) + " is " + formatSize(size) + ", more than the "
                    + formatSize(limit) + " limit. Read it in parts with startLine and lineCount"
                    + (sandbox.display(file).endsWith(".log") ? ", or use get_logs." : "."));
        }
        if (size > Math.max(limit, 64L * 1024 * 1024)) {
            throw new ToolException(sandbox.display(file) + " is too large to read (" + formatSize(size) + ").");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (looksBinary(bytes)) {
            throw new ToolException(sandbox.display(file) + " looks like a binary file. Use read_file_base64 instead.");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!partial) {
            return ToolResult.ok(text);
        }
        String[] lines = text.split("\r?\n", -1);
        int from = Math.min(startLine - 1, lines.length);
        int to = lineCount < 0 ? lines.length : (int) Math.min((long) from + lineCount, lines.length);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(lines[i]).append('\n');
            if (sb.length() > limit) {
                throw new ToolException("That range is larger than the " + formatSize(limit) + " limit. Ask for fewer lines.");
            }
        }
        sb.append("[lines ").append(from + 1).append('-').append(to).append(" of ").append(lines.length).append(']');
        return ToolResult.ok(sb.toString());
    }

    ToolResult writeFile(JsonObject args) throws Exception {
        Path file = sandbox.resolve(Args.requireString(args, "path"));
        String content = Args.requireString(args, "content");
        boolean append = Args.optBool(args, "append", false);
        ensureWritable(file);
        Files.createDirectories(file.getParent());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (append) {
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
        return ToolResult.ok((append ? "Appended " : "Wrote ") + formatSize(bytes.length) + " to " + sandbox.display(file));
    }

    ToolResult readFileBase64(JsonObject args) throws Exception {
        Path file = existingFile(Args.requireString(args, "path"));
        long size = Files.size(file);
        long limit = maxReadBytes.getAsLong();
        if (size > limit) {
            throw new ToolException(sandbox.display(file) + " is " + formatSize(size) + ", more than the "
                    + formatSize(limit) + " limit (tools.maxReadSizeMb in the DeveloperMCP config).");
        }
        return ToolResult.ok(Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
    }

    ToolResult writeFileBase64(JsonObject args) throws Exception {
        Path file = sandbox.resolve(Args.requireString(args, "path"));
        String content = Args.requireString(args, "content");
        byte[] bytes = decodeBase64(content);
        ensureWritable(file);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return ToolResult.ok("Wrote " + formatSize(bytes.length) + " to " + sandbox.display(file));
    }

    ToolResult deleteFile(JsonObject args) throws Exception {
        Path file = sandbox.resolve(Args.requireString(args, "path"));
        if (Files.isDirectory(file)) {
            throw new ToolException(sandbox.display(file) + " is a folder. Only single files can be deleted.");
        }
        if (!Files.deleteIfExists(file)) {
            throw new ToolException("File not found: " + sandbox.display(file));
        }
        return ToolResult.ok("Deleted " + sandbox.display(file));
    }

    private Path existingFile(String requested) throws ToolException {
        Path file = sandbox.resolve(requested);
        if (!Files.exists(file)) {
            throw new ToolException("File not found: " + sandbox.display(file) + ". Use list_directory to see what is there.");
        }
        if (Files.isDirectory(file)) {
            throw new ToolException(sandbox.display(file) + " is a directory. Use list_directory instead.");
        }
        return file;
    }

    private void ensureWritable(Path file) throws ToolException {
        if (file.equals(sandbox.root())) {
            throw new ToolException("Give a file path, not the server folder itself.");
        }
        if (Files.isDirectory(file)) {
            throw new ToolException(sandbox.display(file) + " is a directory.");
        }
    }

    /** Strict base64: whitespace and a data: URL prefix are allowed, anything else outside the alphabet is an error. */
    static byte[] decodeBase64(String content) throws ToolException {
        String s = content.strip();
        if (s.startsWith("data:") && s.contains(";base64,")) {
            s = s.substring(s.indexOf(";base64,") + ";base64,".length());
        }
        s = s.replaceAll("\\s+", "");
        try {
            return s.indexOf('-') >= 0 || s.indexOf('_') >= 0 ? Base64.getUrlDecoder().decode(s) : Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException e) {
            throw new ToolException("content is not valid base64: " + e.getMessage());
        }
    }

    static boolean looksBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 8192);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
