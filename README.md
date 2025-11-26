# 🧠 Minecraft Developer MCP
### *The Bridge Between Your AI and Your Blocky World*

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white) ![Spigot](https://img.shields.io/badge/Spigot-1.20.4-F7CF0D?style=for-the-badge&logo=minecraft&logoColor=white) ![MCP](https://img.shields.io/badge/Protocol-MCP-00A67E?style=for-the-badge&logo=network&logoColor=white)

**Control your Minecraft server directly from Claude, Cursor, or any MCP-compliant AI.** 
Stop alt-tabbing. Stop manually grepping logs. Just ask your AI to do it.

---

## ⚡ What Is This?

This is a Spigot/Paper plugin that turns your Minecraft Server into a **Model Context Protocol (MCP) Server**. 

It creates a secure, high-speed bridge that allows AI agents to:
1.  **👀 Read & Analyze Logs** in real-time.
2.  **⚡ Execute Commands** and see the *actual* output.
3.  **📂 Manage Files**, configs, and plugins without touching FTP.
4.  **🧩 Debug Errors** by letting the AI see the stack trace directly from the source.

---

## 🛠️ Capabilities

| Tool | Description | Example Prompt |
| :--- | :--- | :--- |
| `execute_command` | Run any console command with full output capture. | *"Give 'Notch' a diamond sword named 'Excalibur'."* |
| `read_file` | Read any text file (configs, logs, data) in the server dir. | *"Read plugins/Essentials/config.yml and tell me the chat format."* |
| `write_file` | Create or edit files. Perfect for config tweaks. | *"Create a new skript file called 'welcome.sk' that greets players."* |
| `list_plugins` | Get a clean list of all installed plugins & versions. | *"Check if WorldGuard is enabled and up to date."* |
| `get_logs` | Fetch the last 100 lines of `latest.log`. | *"Why did the server just lag? Check the logs."* |

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
Add this to your Cursor `mcp.json` config:

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

