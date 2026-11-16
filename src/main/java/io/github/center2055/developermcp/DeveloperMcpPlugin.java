package io.github.center2055.developermcp;

import com.google.gson.JsonObject;
import io.github.center2055.developermcp.http.AuthGuard;
import io.github.center2055.developermcp.http.McpHttpServer;
import io.github.center2055.developermcp.protocol.McpProtocol;
import io.github.center2055.developermcp.tools.FileTools;
import io.github.center2055.developermcp.tools.PathSandbox;
import io.github.center2055.developermcp.tools.ServerTools;
import io.github.center2055.developermcp.tools.ToolRegistry;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public final class DeveloperMcpPlugin extends JavaPlugin {

    /** Name and data folder of 1.x, which was called MCPMinecraft. */
    private static final String LEGACY_NAME = "MCPMinecraft";
    /** Tokens shipped as examples in old configs and docs. Never accepted. */
    private static final Set<String> KNOWN_DEFAULT_TOKENS = Set.of(
            "changeme-secure-token-123", "changeme", "CHANGE-THIS-TO-A-SECURE-RANDOM-TOKEN", "YOUR-SECURE-TOKEN", "YOUR-TOKEN");
    static final int MIN_TOKEN_LENGTH = 16;

    private volatile Settings settings;
    private AuthGuard auth;
    private McpProtocol protocol;
    private McpHttpServer http;
    private String boundAddress;

    record Settings(String host, int port, String token, Set<String> disabledTools, long maxReadBytes, boolean logCalls) {
    }

    @Override
    public void onLoad() {
        importLegacyDataFolder();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        upgradeConfig();
        settings = loadSettings();

        Path serverRoot = Path.of("").toAbsolutePath().normalize();
        ToolRegistry registry = new ToolRegistry(() -> settings.disabledTools())
                .register(new ServerTools(this, serverRoot).tools())
                .register(new FileTools(new PathSandbox(serverRoot), () -> settings.maxReadBytes()).tools());
        warnAboutUnknownTools(registry);

        auth = new AuthGuard(settings.token(), getLogger());
        protocol = new McpProtocol(registry, version(), getLogger(), this::logCall);

        PluginCommand command = getCommand("developermcp");
        if (command != null) {
            DeveloperMcpCommand executor = new DeveloperMcpCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        // Start one tick later: by then every plugin is enabled, so an old MCPMinecraft jar can be
        // switched off first and its port freed.
        getServer().getScheduler().runTask(this, () -> {
            disableLegacyJar();
            startHttp();
        });
    }

    @Override
    public void onDisable() {
        stopHttp();
    }

    // ------------------------------------------------------------------ lifecycle

    synchronized void startHttp() {
        Settings s = settings;
        http = new McpHttpServer(getLogger(), protocol, auth);
        try {
            http.start(s.host(), s.port());
            boundAddress = (s.host().isBlank() || s.host().equals("0.0.0.0") ? "all interfaces" : s.host()) + ", port " + s.port();
            getLogger().info("MCP server listening on " + boundAddress + ". Endpoint: http://<server-ip>:" + s.port() + "/mcp");
        } catch (IOException e) {
            http = null;
            boundAddress = null;
            getLogger().severe("Could not start the MCP server on " + s.host() + ":" + s.port() + " (" + e.getMessage()
                    + "). Is the port already in use? Change server.port in plugins/" + getName() + "/config.yml and run /dmcp reload.");
        }
    }

    synchronized void stopHttp() {
        if (http != null) {
            http.stop();
            http = null;
            boundAddress = null;
        }
    }

    /** Re-reads config.yml. Restarts the HTTP server only if the address changed. */
    synchronized void reload() {
        Settings old = settings;
        reloadConfig();
        upgradeConfig();
        settings = loadSettings();
        auth.setToken(settings.token());
        if (http == null || !old.host().equals(settings.host()) || old.port() != settings.port()) {
            stopHttp();
            startHttp();
        }
    }

    synchronized String regenerateToken() {
        String token = newToken();
        getConfig().set("server.token", token);
        saveConfig();
        settings = loadSettings();
        auth.setToken(token);
        return token;
    }

    Settings settings() {
        return settings;
    }

    String status() {
        if (http == null) {
            return "not running";
        }
        int sse = http.openSessions();
        return "listening on " + boundAddress + (sse > 0 ? ", " + sse + " SSE client(s) connected" : "");
    }

    @SuppressWarnings("deprecation") // getDescription() works on every server; Paper only deprecates it
    String version() {
        return getDescription().getVersion();
    }

    // ------------------------------------------------------------------ config

    private Settings loadSettings() {
        FileConfiguration c = getConfig();
        String token = c.getString("server.token", "");
        token = token == null ? "" : token.trim();
        if (token.length() < MIN_TOKEN_LENGTH || KNOWN_DEFAULT_TOKENS.contains(token)) {
            boolean hadOne = !token.isEmpty();
            token = newToken();
            c.set("server.token", token);
            saveConfig();
            getLogger().warning(hadOne
                    ? "The token in config.yml was a default or shorter than " + MIN_TOKEN_LENGTH
                    + " characters, so a new random one was generated. Update your MCP client: run 'dmcp token' in the console."
                    : "Generated an access token. Run 'dmcp token' in the console to see it.");
        }
        Set<String> disabled = new HashSet<>();
        for (String name : c.getStringList("tools.disabled")) {
            disabled.add(name.trim().toLowerCase(Locale.ROOT));
        }
        int port = c.getInt("server.port", 25374);
        if (port < 1 || port > 65535) {
            getLogger().warning("server.port " + port + " is not a valid port. Using 25374.");
            port = 25374;
        }
        long maxReadMb = Math.max(1, Math.min(64, c.getLong("tools.maxReadSizeMb", 8)));
        return new Settings(c.getString("server.host", "0.0.0.0").trim(), port, token, Set.copyOf(disabled),
                maxReadMb * 1024 * 1024, c.getBoolean("tools.logCalls", true));
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Adds keys introduced in newer versions to an existing config. */
    private void upgradeConfig() {
        FileConfiguration config = getConfig();
        if (config.getDefaults() == null) {
            return;
        }
        for (String key : config.getDefaults().getKeys(true)) {
            if (!config.contains(key, true)) {
                config.options().copyDefaults(true);
                saveConfig();
                return;
            }
        }
    }

    private void warnAboutUnknownTools(ToolRegistry registry) {
        for (String name : settings.disabledTools()) {
            if (!registry.names().contains(name)) {
                getLogger().warning("tools.disabled lists '" + name + "', which is not a tool. Known tools: "
                        + String.join(", ", registry.names()));
            }
        }
    }

    // ------------------------------------------------------------------ audit log

    private void logCall(String client, String tool, JsonObject args) {
        if (!settings.logCalls()) {
            return;
        }
        String detail = "";
        if (args.has("command")) {
            detail = ": " + shorten(args.get("command").getAsString());
        } else if (args.has("path")) {
            detail = ": " + shorten(args.get("path").getAsString());
        }
        getLogger().info("[" + client + "] " + tool + detail);
    }

    private static String shorten(String s) {
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "..." : oneLine;
    }

    // ------------------------------------------------------------------ upgrade from 1.x

    /** 1.x stored its files in plugins/MCPMinecraft. Copy them once so the port and token carry over. */
    private void importLegacyDataFolder() {
        Path newFolder = getDataFolder().toPath();
        Path oldFolder = newFolder.resolveSibling(LEGACY_NAME);
        if (!Files.isDirectory(oldFolder) || Files.exists(newFolder)) {
            return;
        }
        try {
            Files.walkFileTree(oldFolder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(newFolder.resolve(oldFolder.relativize(dir).toString()));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.copy(file, newFolder.resolve(oldFolder.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                    return FileVisitResult.CONTINUE;
                }
            });
            getLogger().info("Imported your settings from plugins/" + LEGACY_NAME + " (version 1.x). You can delete that folder.");
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not import plugins/" + LEGACY_NAME, e);
        }
    }

    private void disableLegacyJar() {
        Plugin legacy = getServer().getPluginManager().getPlugin(LEGACY_NAME);
        if (legacy == null) {
            return;
        }
        getLogger().severe("The old MCPMinecraft 1.x jar is also installed. DeveloperMCP replaces it: delete "
                + "mcp-minecraft-*.jar / MCPMinecraft-*.jar from the plugins folder. Disabling the old one for this run.");
        if (legacy.isEnabled()) {
            getServer().getPluginManager().disablePlugin(legacy);
        }
    }
}
