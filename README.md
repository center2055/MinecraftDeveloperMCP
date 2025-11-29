# Minecraft Developer MCP

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white) ![Spigot/Paper](https://img.shields.io/badge/Spigot%20%2F%20Paper-1.20%2B-F7CF0D?style=for-the-badge&logo=minecraft&logoColor=white) ![MCP](https://img.shields.io/badge/Protocol-MCP-00A67E?style=for-the-badge&logo=network&logoColor=white)

A lightweight MCP bridge for Spigot and Paper servers. It exposes console access, file reads/writes, plugin info, and log retrieval over HTTP so external tooling can automate server administration without FTP or screen sharing.

---

## What It Can Do
- Run console commands and return captured output.
- Read or edit text files anywhere inside the server directory.
- Upload or download binary files via base64 (ideal for JARs or images).
- List installed plugins with their versions.
- Grab the last 100 lines from `logs/latest.log`.
- List files in any directory with sizes.

---

## Tools & Example Requests

| Tool | Description | Example request |
| :--- | :--- | :--- |
| `execute_command` | Run any console command with output capture. | `Give 'Notch' a diamond sword named 'Excalibur'.` |
| `read_file` | Read any text file (configs, logs, data) in the server dir. | `Read plugins/Essentials/config.yml and show the chat format.` |
| `write_file` | Create or edit files in place. | `Create plugins/Skript/scripts/welcome.sk that greets players on join.` |
| `list_plugins` | List installed plugins and versions. | `Check if WorldGuard is enabled and up to date.` |
| `get_logs` | Fetch the last 100 lines of `latest.log`. | `Grab recent logs to see why the server lagged.` |
| `write_file_base64` | Upload binary files via base64. | `Upload SuperSword.jar to plugins/ and confirm size.` |
| `read_file_base64` | Download binary files as base64. | `Return world/icon.png as base64.` |
| `list_directory` | List files in a folder with sizes. | `List everything in world/region.` |

---

## Quick Start

### 1. Install the Plugin
Download the latest JAR from the [Releases Page](https://github.com/center2055/MinecraftDeveloperMCP/releases) and drop it into your `plugins/` folder.

### 2. Configure and Secure
Start the server once to generate the config. Edit `plugins/MCPMinecraft/config.yml`:

```yaml
server:
  port: 25374 # Choose an open port (ensure your host allows it)
  token: "CHANGE-THIS-TO-A-SECURE-RANDOM-TOKEN"
```

> SECURITY WARNING: Anyone with this token has console access. Make it long and random.

### 3. Connect an MCP Client (example config)
Place this in your `mcp.json` (usually at `~/.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "minecraft": {
      "url": "http://YOUR-SERVER-IP:25374/sse?token=YOUR-SECURE-TOKEN",
      "transport": "sse"
    }
  }
}
```

For scripting or automation you can also use the synchronous `/api` endpoint:

```bash
curl -X POST "http://YOUR-SERVER-IP:25374/api?token=YOUR-TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_plugins","arguments":{}}}'
```

### 4. Use It
- Backup `whitelist.json`, then add `jeb_`.
- Read `bukkit.yml` and adjust monster spawn limits.
- Tail the latest log entries before and after running a command.

---

## Troubleshooting: No Extra Port?

If your host does not allow opening an extra port (e.g., some free providers), use a tunnel such as playit.gg:
1. Install the [playit.gg plugin](https://playit.gg/) on your server.
2. Create a Custom TCP Tunnel pointing to `127.0.0.1:25374` (or whatever local port you configured).
3. Use the public address from playit (for example `agent-tunnel.playit.gg:12345`) in your `mcp.json`.

---

## Build from Source

```bash
git clone https://github.com/center2055/MinecraftDeveloperMCP.git
cd MinecraftDeveloperMCP
mvn clean package
```

The shaded JAR will be in `target/`.
