Let AI assistants like **Claude**, **Cursor** and **VS Code Copilot** run your Minecraft server: console commands, config files, logs and plugin jars, through the [Model Context Protocol](https://modelcontextprotocol.io).

Ask "why did the server lag at 8pm?", "set up LuckPerms groups for builders" or "fix the YAML error in my Essentials config", and the AI reads the logs, edits the files and runs the commands itself.

## Features

- **Console commands with real output.** Vanilla and plugin commands return exactly what they print in the console, including plugins that answer late such as LuckPerms.
- **Files.** Read, write, append and delete files in the server folder, read big files in parts, and upload or download binary files such as jars.
- **Server overview.** Version, players, TPS and MSPT (Paper), memory, worlds and plugins in one call.
- **Logs.** The end of `latest.log`, optionally only lines containing a word like `ERROR`.
- **Secure by default.** A random token is generated on first start, it is checked in constant time, and addresses that keep guessing get locked out. Nothing outside the server folder is reachable.
- **Audit log.** Every tool call is logged to the console with the command or path (never file contents).
- **Works with every MCP client.** Streamable HTTP (`/mcp`) for current clients, plus the older SSE transport (`/sse`).
- **Tiny.** No bundled web server or libraries. The jar is under 100 KB.
- **1.20 to 26.x** on Paper, Purpur, Spigot and Bukkit.

## Tools

| Tool | What it does |
| --- | --- |
| `execute_command` | Run a console command and get its output |
| `server_info` | Version, players, TPS/MSPT, memory, uptime, worlds |
| `list_plugins` | Installed plugins with version, status and authors |
| `get_logs` | Last lines of `latest.log`, optionally filtered |
| `list_directory` | Files and folders with sizes |
| `read_file` | Read a text file, optionally a range of lines |
| `write_file` | Create, overwrite or append to a text file |
| `read_file_base64` | Download any file as base64 |
| `write_file_base64` | Upload a binary file, e.g. a plugin jar |
| `delete_file` | Delete a single file |

Switch off any of them in the config, for example to make the AI read-only.

## Setup

1. Put the jar in `plugins/` and restart. The plugin listens on port **25374**.
2. Run `dmcp token` in the **server console** to get your access token.
3. Make sure port 25374 is reachable from the computer running your AI client (open it on your host or firewall, or see "No extra port?" below).
4. Add the server to your AI client. Replace `SERVER-IP` and `TOKEN`:

<details>
<summary>Claude Code</summary>

```bash
claude mcp add --transport http minecraft http://SERVER-IP:25374/mcp --header "Authorization: Bearer TOKEN"
```

</details>

<details>
<summary>Cursor (~/.cursor/mcp.json)</summary>

```json
{
  "mcpServers": {
    "minecraft": {
      "url": "http://SERVER-IP:25374/mcp",
      "headers": { "Authorization": "Bearer TOKEN" }
    }
  }
}
```

</details>

<details>
<summary>VS Code (.vscode/mcp.json)</summary>

```json
{
  "servers": {
    "minecraft": {
      "type": "http",
      "url": "http://SERVER-IP:25374/mcp",
      "headers": { "Authorization": "Bearer TOKEN" }
    }
  }
}
```

</details>

<details>
<summary>Claude Desktop (claude_desktop_config.json, needs Node.js)</summary>

```json
{
  "mcpServers": {
    "minecraft": {
      "command": "npx",
      "args": ["mcp-remote", "http://SERVER-IP:25374/mcp", "--allow-http", "--header", "Authorization:${AUTH}"],
      "env": { "AUTH": "Bearer TOKEN" }
    }
  }
}
```

</details>

Clients that only speak the older SSE transport can use `http://SERVER-IP:25374/sse` with the same header.

## Commands

All need `developermcp.admin` (op by default). `/dmcp` is short for `/developermcp`.

| Command | What it does |
| --- | --- |
| `/dmcp status` | Whether the MCP server is running, its address and connected clients |
| `/dmcp reload` | Reload `config.yml` |
| `/dmcp token` | Show the access token (console only) |
| `/dmcp token regenerate` | Make a new token; the old one stops working right away |

## Configuration

<details>
<summary>config.yml</summary>

```yaml
server:
  # 0.0.0.0 = every network interface. Use 127.0.0.1 if the AI client runs on the same machine.
  host: "0.0.0.0"
  port: 25374
  # Generated on first start. Show it with "dmcp token" in the console.
  token: ""

tools:
  # Switch off tools, e.g. [execute_command, write_file_base64, delete_file]
  disabled: []
  # Largest file read_file and read_file_base64 will return, in megabytes (1-64).
  maxReadSizeMb: 8
  # Log each tool call to the console (tool and command or path, never file contents).
  logCalls: true
```

</details>

## Security

This plugin gives whoever holds the token full control of your server: they can run any console command and change any file in the server folder.

- Keep the token secret. If it leaks, run `dmcp token regenerate`.
- Traffic is plain HTTP. On an untrusted network, put the port behind a VPN, an SSH tunnel or an HTTPS reverse proxy, or bind to `127.0.0.1` when the AI runs on the same machine.
- AI assistants make mistakes. Keep backups, and switch off tools you don't need.

## No extra port?

If your host can't open another port, use a tunnel such as [playit.gg](https://playit.gg): create a TCP tunnel to `127.0.0.1:25374` and use the address playit gives you in your client config.

## Upgrading from 1.x (MCPMinecraft)

Version 1.x was called MCPMinecraft. Delete the old `mcp-minecraft-*.jar` and add the new one. Your port and token are copied from `plugins/MCPMinecraft` automatically. If you were still using the default token from 1.x, a new random one is generated: run `dmcp token` and update your client. Old SSE URLs with `?token=` keep working, but the `Authorization` header is recommended.

## Support

Bugs and ideas: [GitHub issues](https://github.com/center2055/MinecraftDeveloperMCP/issues) or [Discord](https://discord.gg/3HZZ353Tzg).
