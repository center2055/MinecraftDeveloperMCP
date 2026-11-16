package io.github.center2055.developermcp.tools;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Keeps file tools inside the server folder. */
public final class PathSandbox {

    private final Path root;

    public PathSandbox(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** Resolves a path given by the AI. Relative paths start at the server folder; nothing outside it is allowed. */
    public Path resolve(String requested) throws ToolException {
        String text = requested == null ? "" : requested.trim();
        if (text.isEmpty() || text.equals("/") || text.equals("\\")) {
            text = ".";
        }
        Path path;
        try {
            path = root.resolve(text).normalize();
        } catch (InvalidPathException e) {
            throw new ToolException("Invalid path '" + requested + "': " + e.getReason());
        }
        if (!path.startsWith(root)) {
            throw new ToolException("Access denied: '" + requested + "' is outside the server folder. "
                    + "Use paths relative to the server folder, e.g. plugins/Essentials/config.yml.");
        }
        return path;
    }

    /** Path relative to the server folder, with forward slashes, for messages. */
    public String display(Path path) {
        Path rel = root.relativize(path.toAbsolutePath().normalize());
        String s = rel.toString().replace('\\', '/');
        return s.isEmpty() ? "." : s;
    }
}
