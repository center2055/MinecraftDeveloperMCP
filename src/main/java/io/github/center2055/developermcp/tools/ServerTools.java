package io.github.center2055.developermcp.tools;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandException;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Tools that talk to the running server. Bukkit calls happen on the main thread. */
public final class ServerTools {

    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 10;
    private static final long LOG_TAIL_MAX_BYTES = 16L * 1024 * 1024;

    private final Plugin plugin;
    private final Path serverRoot;

    public ServerTools(Plugin plugin, Path serverRoot) {
        this.plugin = plugin;
        this.serverRoot = serverRoot;
    }

    public List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool("execute_command", "Run console command",
                "Run a command in the Minecraft server console, exactly as if typed there, and return the console "
                        + "output it produced. No leading slash needed. Runs with full console permissions, so it works "
                        + "for every vanilla and plugin command (e.g. \"list\", \"lp user Steve info\", \"gamerule keepInventory true\").",
                Args.Schema.object()
                        .string("command", "The command to run, e.g. \"time set day\".", true)
                        .build(),
                Tool.changesThings(true, false), this::executeCommand));
        tools.add(new Tool("server_info", "Server info",
                "Minecraft version, server software, online players, TPS and MSPT (on Paper), memory, uptime and loaded worlds.",
                Args.Schema.object().build(),
                Tool.readOnly(), args -> serverInfo()));
        tools.add(new Tool("list_plugins", "List plugins",
                "List installed plugins with their version, whether they are enabled, and their authors.",
                Args.Schema.object().build(),
                Tool.readOnly(), args -> listPlugins()));
        tools.add(new Tool("get_logs", "Read server log",
                "Return the last lines of logs/latest.log, optionally only lines containing some text "
                        + "(for example \"ERROR\", \"Exception\" or a plugin name).",
                Args.Schema.object()
                        .integer("lines", "How many lines to return (default 100, max 2000).", false)
                        .string("contains", "Only return lines containing this text (case-insensitive). Optional.", false)
                        .build(),
                Tool.readOnly(), this::getLogs));
        return tools;
    }

    ToolResult executeCommand(JsonObject args) throws Exception {
        String command = Args.requireString(args, "command").trim();
        while (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isEmpty()) {
            throw new ToolException("command is empty.");
        }
        String toRun = command;
        try (ConsoleCapture capture = ConsoleCapture.attach()) {
            boolean known;
            try {
                known = onMainThread(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), toRun));
            } catch (ToolException e) {
                return ToolResult.error(e.getMessage() + " The command may still have run.");
            } catch (CommandException e) {
                // Paper 26.x throws syntax errors (e.g. a wrong argument) instead of printing them. Show the
                // Minecraft message, such as "Can't find element ... at position 18: ...ry daytime<--[HERE]".
                String printed = capture.text().strip();
                return ToolResult.error("The command failed: " + rootMessage(e) + (printed.isEmpty() ? "" : "\n" + printed));
            }
            // Some plugins (LuckPerms, for example) answer from their own thread a moment later.
            waitForQuiet(capture);
            String output = capture.text().strip();
            if (output.isEmpty()) {
                return known
                        ? ToolResult.ok("Command ran. It printed nothing to the console.")
                        : ToolResult.error("Unknown command: " + toRun.split(" ")[0] + ". Run \"help\" to list commands.");
            }
            return ToolResult.ok(output);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }

    private static void waitForQuiet(ConsoleCapture capture) throws InterruptedException {
        long start = System.nanoTime();
        long maxWait = TimeUnit.MILLISECONDS.toNanos(capture.isEmpty() ? 1500 : 1000);
        long quiet = TimeUnit.MILLISECONDS.toNanos(200);
        while (System.nanoTime() - start < maxWait) {
            Thread.sleep(50);
            if (!capture.isEmpty() && System.nanoTime() - capture.lastLineNanos() > quiet) {
                return;
            }
        }
    }

    ToolResult serverInfo() throws Exception {
        return ToolResult.ok(onMainThread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Server: ").append(Bukkit.getName()).append(' ').append(Bukkit.getVersion()).append('\n');
            sb.append("Bukkit API: ").append(Bukkit.getBukkitVersion()).append('\n');
            sb.append("Java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor"))
                    .append("), OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.arch")).append('\n');
            sb.append("Uptime: ").append(formatDuration(Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()))).append('\n');

            double[] tps = paperTps();
            if (tps != null) {
                sb.append("TPS (1m, 5m, 15m): ");
                for (int i = 0; i < tps.length && i < 3; i++) {
                    sb.append(i > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.2f", Math.min(20.0, tps[i])));
                }
                sb.append('\n');
            }
            Double mspt = paperMspt();
            if (mspt != null) {
                sb.append("MSPT: ").append(String.format(Locale.ROOT, "%.2f", mspt)).append(" ms\n");
            }
            Runtime rt = Runtime.getRuntime();
            long used = rt.totalMemory() - rt.freeMemory();
            sb.append("Memory: ").append(used / (1024 * 1024)).append(" MB used, ")
                    .append(rt.maxMemory() / (1024 * 1024)).append(" MB max\n");
            sb.append("Online mode: ").append(Bukkit.getOnlineMode())
                    .append(", whitelist: ").append(Bukkit.hasWhitelist()).append('\n');

            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            sb.append("Players: ").append(names.size()).append('/').append(Bukkit.getMaxPlayers());
            if (!names.isEmpty()) {
                sb.append(" - ").append(String.join(", ", names.subList(0, Math.min(names.size(), 100))));
                if (names.size() > 100) {
                    sb.append(", ...");
                }
            }
            sb.append('\n');

            sb.append("Worlds:\n");
            for (World w : Bukkit.getWorlds()) {
                sb.append("  ").append(w.getName()).append(" (").append(w.getEnvironment())
                        .append(", ").append(w.getLoadedChunks().length).append(" chunks loaded, ")
                        .append(w.getEntities().size()).append(" entities, ")
                        .append(w.getPlayers().size()).append(" players)\n");
            }
            long enabled = Arrays.stream(Bukkit.getPluginManager().getPlugins()).filter(Plugin::isEnabled).count();
            sb.append("Plugins: ").append(Bukkit.getPluginManager().getPlugins().length).append(" (").append(enabled).append(" enabled)");
            return sb.toString();
        }));
    }

    @SuppressWarnings("deprecation") // getDescription() works on every server; Paper only deprecates it
    ToolResult listPlugins() throws Exception {
        return ToolResult.ok(onMainThread(() -> {
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            Arrays.sort(plugins, Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)));
            StringBuilder sb = new StringBuilder();
            for (Plugin p : plugins) {
                sb.append(p.getName()).append(' ').append(p.getDescription().getVersion());
                if (!p.isEnabled()) {
                    sb.append(" [DISABLED]");
                }
                List<String> authors = p.getDescription().getAuthors();
                if (authors != null && !authors.isEmpty()) {
                    sb.append(" by ").append(String.join(", ", authors));
                }
                sb.append('\n');
            }
            sb.append(plugins.length).append(" plugins");
            return sb.toString();
        }));
    }

    ToolResult getLogs(JsonObject args) throws Exception {
        int lines = Args.optInt(args, "lines", 100, 1, 2000);
        String contains = Args.optString(args, "contains", null);
        Path log = serverRoot.resolve("logs").resolve("latest.log");
        if (!Files.isRegularFile(log)) {
            throw new ToolException("logs/latest.log does not exist.");
        }
        List<String> tail = LogTail.tail(log, lines, contains, LOG_TAIL_MAX_BYTES);
        if (tail.isEmpty()) {
            return ToolResult.ok(contains == null ? "The log is empty." : "No recent log lines contain \"" + contains + "\".");
        }
        return ToolResult.ok(String.join("\n", tail));
    }

    private <T> T onMainThread(Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }
        if (!plugin.isEnabled()) {
            throw new ToolException("DeveloperMCP is shutting down.");
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ToolException("The server did not respond within " + MAIN_THREAD_TIMEOUT_SECONDS
                    + " seconds (it may be lagging or frozen).");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new ToolException(String.valueOf(cause));
        }
    }

    private static double[] paperTps() {
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getTPS");
            Object r = m.invoke(Bukkit.getServer());
            return r instanceof double[] d ? d : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Double paperMspt() {
        try {
            Method m = Bukkit.getServer().getClass().getMethod("getAverageTickTime");
            Object r = m.invoke(Bukkit.getServer());
            return r instanceof Double d ? d : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static String formatDuration(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m " + d.toSecondsPart() + "s";
    }
}
