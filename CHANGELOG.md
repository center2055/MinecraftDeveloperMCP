# Changelog

## 2.0.0

The plugin is now called **DeveloperMCP** (it was MCPMinecraft). Delete the old `mcp-minecraft-*.jar` when you update. Your port and token are imported from `plugins/MCPMinecraft` automatically.

**If you never changed the default token, a new random one is generated.** Run `dmcp token` in the console and update your MCP client.

### Security

- The default token `changeme-secure-token-123` worked on every install. A random token is now generated on first start, and default or very short tokens are replaced.
- Every request body was written to the server log, including whole files and base64 jars. Only the tool name and command or path are logged now.
- Replaced the embedded Javalin/Jetty 11 web server (no longer receiving security updates) with the JDK's built-in HTTP server.
- Tokens are compared in constant time, and an address that sends 30 wrong tokens is locked out for 10 minutes.
- The token is no longer echoed back in the SSE endpoint URL.

### New

- Minecraft 26.1, 26.1.1, 26.1.2, 26.2 and 26.3
- `server_info` tool: version, players, TPS and MSPT (Paper), memory, uptime and worlds
- `delete_file` tool
- `read_file` can return a range of lines; `write_file` can append; `get_logs` can filter and return up to 2000 lines
- `/dmcp status`, `/dmcp reload`, `/dmcp token [regenerate]`
- `tools.disabled` to switch off tools, `server.host` to choose the network interface
- Tool annotations (read-only or destructive), so clients can auto-approve safe tools
- Supports MCP protocol versions 2024-11-05 through 2025-11-25
- The jar shrank from 8 MB to under 100 KB

### Fixed

- Output of vanilla commands such as `list` or `time query` was never returned
- The server thread was paused for 100 ms on every command
- `get_logs` loaded the whole log into memory and failed on logs with invalid UTF-8
- Reading a binary file as text returned garbage; it now points to `read_file_base64`
- Garbage base64 was silently written as an empty file
- Tool errors were sent as JSON-RPC parse errors, so AI clients couldn't see what went wrong
- Missing tool arguments crashed with a NullPointerException
- `GET /mcp` returned JSON instead of 405, which some clients treat as a broken stream
- CORS preflight requests were rejected, so browser-based clients couldn't connect
- Large numeric request ids were truncated
- The reported server version was out of date (1.2.0 / 1.2.3)

## 1.3.2

- Session-based authentication for SSE clients

## 1.3.1

- Streamable HTTP transport (`/mcp`)

## 1.2.0

- LuckPerms support, `/api` endpoint
