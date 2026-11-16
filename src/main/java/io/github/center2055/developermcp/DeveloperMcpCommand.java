package io.github.center2055.developermcp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /dmcp status | reload | token [regenerate] */
final class DeveloperMcpCommand implements TabExecutor {

    private static final String PREFIX = "§8[§aDeveloperMCP§8] §r";
    private final DeveloperMcpPlugin plugin;

    DeveloperMcpCommand(DeveloperMcpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                DeveloperMcpPlugin.Settings s = plugin.settings();
                sender.sendMessage(PREFIX + "§fv" + plugin.version() + " §7- " + plugin.status());
                sender.sendMessage("§7Streamable HTTP: §fhttp://<server-ip>:" + s.port() + "/mcp");
                sender.sendMessage("§7Older clients (SSE): §fhttp://<server-ip>:" + s.port() + "/sse");
                if (!s.disabledTools().isEmpty()) {
                    sender.sendMessage("§7Disabled tools: §f" + String.join(", ", s.disabledTools()));
                }
                sender.sendMessage("§7Commands: §f/" + label + " reload§7, §f/" + label + " token§7 (console only)");
            }
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(PREFIX + "§aReloaded. §7Server is " + plugin.status() + ".");
            }
            case "token" -> {
                // The token gives full control of the server. Only show it where nobody can look over a player's shoulder.
                if (!(sender instanceof ConsoleCommandSender)) {
                    sender.sendMessage(PREFIX + "§cFor safety, the token can only be shown in the server console.");
                    return true;
                }
                if (args.length > 1 && args[1].equalsIgnoreCase("regenerate")) {
                    String token = plugin.regenerateToken();
                    sender.sendMessage(PREFIX + "New token: " + token);
                    sender.sendMessage("The old token stops working now. Update your MCP client config.");
                } else {
                    sender.sendMessage(PREFIX + "Token: " + plugin.settings().token());
                    sender.sendMessage("Send it as the header 'Authorization: Bearer <token>'. Make a new one with: dmcp token regenerate");
                }
            }
            default -> sender.sendMessage(PREFIX + "§7Usage: /" + label + " <status|reload|token [regenerate]>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = args.length == 1 ? List.of("status", "reload", "token")
                : args.length == 2 && args[0].equalsIgnoreCase("token") ? List.of("regenerate") : List.of();
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))) {
                out.add(o);
            }
        }
        return out;
    }
}
