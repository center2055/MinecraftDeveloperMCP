# 🧠 Minecraft Developer MCP
### *Your Server's New AI Superpower.*

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white) ![Spigot/Paper](https://img.shields.io/badge/Spigot%20%2F%20Paper-1.20%2B-F7CF0D?style=for-the-badge&logo=minecraft&logoColor=white) ![MCP](https://img.shields.io/badge/Protocol-MCP-00A67E?style=for-the-badge&logo=network&logoColor=white)

**Turn your Minecraft server into an AI-controllable powerhouse.**
Directly connect Claude, Cursor, or any MCP-compliant AI to your **Spigot or Paper** server console and files. No FTP. No alt-tabbing. Just pure, context-aware control.

---

## ⚡ What Can It Do?

Imagine having a senior developer sitting inside your server console, ready to execute your every command instantly.

*   **🔍 Debug Faster:** "Read the latest log error and tell me which plugin caused it."
*   **🛠️ Configure Instantly:** "Change the server MOTD to something festive."
*   **📦 Manage Plugins:** "Check if Vault is installed and list its version."
*   **📝 Write Code:** "Create a Skript that gives players a diamond when they join."

---

## 🛠️ Tools & Examples

| Tool | Description | Example Prompt |
| :--- | :--- | :--- |
| `execute_command` | Run any console command with full output capture. Works with all plugins including LuckPerms! | *"Give 'Notch' a diamond sword named 'Excalibur'."* |
| `read_file` | Read any text file (configs, logs, data) in the server dir. | *"Read plugins/Essentials/config.yml and tell me the chat format."* |
| `write_file` | Create or edit files. Perfect for config tweaks. | *"Create a new skript file called 'welcome.sk' that greets players."* |
| `list_plugins` | Get a clean list of all installed plugins & versions. | *"Check if WorldGuard is enabled and up to date."* |
| `get_logs` | Fetch the last 100 lines of `latest.log`. | *"Why did the server just lag? Check the logs."* |
| `write_file_base64` | Upload binary files (JARs, images) via base64. | *"Upload this new 'SuperSword.jar' to the plugins folder."* |
| `read_file_base64` | Download binary files as base64 strings. | *"Get the 'world/icon.png' file so I can analyze it."* |
| `list_directory` | List files in a folder with file sizes. | *"List all files in the 'world/region' directory."* |

### 💡 Configuration Examples

**1. Fixing a Broken Config:**
> **You:** "I messed up my `plugins/LuckPerms/config.yml`. The server says there's a YAML error on line 42. Can you read it and fix the indentation?"
> **AI:** *Reads file -> Detects tab character -> Writes corrected file.* "Fixed! I replaced the tab with 2 spaces."

**2. Tuning Gameplay:**
> **You:** "Read `bukkit.yml`. I want to increase the monster spawn limit to 100. Update the file."
> **AI:** *Reads `bukkit.yml` -> Locates `spawn-limits` -> Updates value -> Saves file.* "Done. Run `/reload` to apply."

**3. Setting up a New Plugin:**
> **You:** "I just installed Essentials. Create a `kit.yml` configuration that gives new players a stone sword and some bread."
> **AI:** *Writes the exact YAML structure needed for Essentials kits.*

---

## 🆕 What's New in v1.2.0

- **🔧 LuckPerms Support:** Commands now work with ALL plugins, including LuckPerms and others that previously rejected custom command senders
- **⚡ Fast `/api` Endpoint:** New synchronous HTTP endpoint for instant responses (no SSE overhead)
- **📁 Binary File Support:** Upload/download JARs, images, and other binary files via base64
- **📂 Directory Listing:** Browse server files with sizes

---

## 🚀 Quick Start

### 1. Install the Plugin
Download the latest JAR from the [Releases Page](https://github.com/center2055/MinecraftDeveloperMCP/releases) and drop it into your `plugins/` folder.

### 2. Configure & Secure
Start the server once to generate the config. Edit `plugins/MCPMinecraft/config.yml`:

```yaml
server:
  port: 25374 # Choose an open port (ensure your host allows it!)
  token: "CHANGE-THIS-TO-A-SECURE-RANDOM-TOKEN"
```
> **⚠️ SECURITY WARNING:** Anyone with this token has **Console Access**. Make it long and random!

### 3. Connect Your AI (Cursor Example)
Add this to your Cursor `mcp.json` config (usually at `~/.cursor/mcp.json`):

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

> **💡 Pro Tip:** For scripting/automation, you can also use the `/api` endpoint directly for synchronous requests:
> ```bash
> curl -X POST "http://YOUR-SERVER-IP:25374/api?token=YOUR-TOKEN" \
>   -H "Content-Type: application/json" \
>   -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_plugins","arguments":{}}}'
> ```

### 4. Unleash the AI
Reload your AI client. You can now say things like:

> *"Hey, create a backup of the whitelist.json, then add 'jeb_' to the whitelist."*

> *"I'm getting a YAML error in my LuckPerms config. Read the file and fix the indentation."*

---

## 🤖 Why?

Because **Context is King**. 
When you paste a log snippet into ChatGPT, it lacks context. It doesn't know your other plugins, your config values, or your server version. 
With **Minecraft Developer MCP**, the AI *lives* in your server. It can "look around," check dependencies, and fix problems autonomously.

---

## 📦 Build from Source

```bash
git clone https://github.com/center2055/MinecraftDeveloperMCP.git
cd MinecraftDeveloperMCP
mvn clean package
```
The ready-to-use JAR will be in `target/`.

---
*Built with ❤️ for the blockiest developers.*

