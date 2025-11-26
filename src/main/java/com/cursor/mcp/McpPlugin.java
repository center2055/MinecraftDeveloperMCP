package com.cursor.mcp;

import org.bukkit.plugin.java.JavaPlugin;

public class McpPlugin extends JavaPlugin {
    private McpServer server;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int port = getConfig().getInt("server.port", 8080);
        String token = getConfig().getString("server.token", "changeme");
        
        // Start the server
        // We pass 'this' to allow tools to access the plugin instance for scheduling tasks
        server = new McpServer(this, port, token);
        server.start();
        
        getLogger().info("MCP Server started on port " + port);
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop();
        }
    }
}


