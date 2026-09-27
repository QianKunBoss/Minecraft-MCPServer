# MCPServer — MCP Server Mod for Minecraft Fabric

[中文文档](README.md)

A Fabric-based Minecraft server-side mod that exposes server capabilities to AI assistants (such as Claude Desktop, Cursor, etc.) over **MCP (Model Context Protocol)** via HTTP/HTTPS — letting AI query server status, read logs, manage players, trigger performance profiling, execute commands, and record player behavior.

<p align="center">
  <img src="icon.png" alt="MCPServer icon" width="200">
</p>

## Core Features

- **MCP over HTTP/HTTPS**: Implements the standard MCP JSON-RPC protocol (version `2024-11-05`, Streamable HTTP transport), compatible with MCP clients like Claude Desktop and Cursor
- **37 AI-callable tools**: server monitoring, command execution, Spark performance profiling, player behavior recording, file editing, Shell execution, and remote fake-player integration
- **14 REST API endpoints**: besides the MCP protocol, equivalent REST interfaces are provided for non-MCP clients
- **Spark integration** (optional): with the Spark mod installed, AI can start/stop the profiler, fetch health reports, and create heap dumps; degrades gracefully without blocking the server when Spark is absent
- **Player behavior recording**: high-frequency sampling of 23 fields (position/health/movement/inventory, etc.), written async to disk (JSONL), queryable by time range and exportable to CSV
- **Multi-version Minecraft support**: 18 real versions from 1.20.1 to 1.21.11, with a single shared source tree and an independent Gradle project per version
- **Graceful shutdown**: correctly stops the HTTP thread pool and background daemon threads to avoid process hangs
- **Config fault tolerance**: lenient JSON parsing so a corrupted config won't crash the server
- **Token authentication**: supports `auto` (random per startup) and `persistent` (fixed token) modes
- **SSL/HTTPS**: supports both PEM certificates (Let's Encrypt style) and Java Keystore

## Installation

### Prerequisites

- Minecraft server (Fabric Loader ≥ 0.14.0)
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java ≥ 17

### Steps

1. Download the jar for your version from [Releases](https://github.com/QianKunBoss/Minecraft-MCPServer/releases) (e.g. `MCPServer-1.3.0-1.20.1.jar`)
2. Place the jar into the server's `mods/` directory
3. (Optional) install [Spark](https://spark.lucko.me/) (≥ 1.10.0) for performance profiling
4. Start the server — the mod auto-generates `<server-root>/config/MCPServer/config.json`
5. Check the server console log for the printed **MCP Endpoint**, **API Key**, and a client config example

## Configuration

The config file lives at `<server-root>/config/MCPServer/config.json` and is auto-generated on first launch. `//` comments and trailing commas are supported (lenient parsing).

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `httpPort` | int | `8081` | MCP HTTP server port (1–65535, restart required after change) |
| `ssl.enabled` | boolean | `false` | Enable HTTPS (restart required after change) |
| `ssl.keystorePath` | string | `config/MCPServer/mcpserver-keystore.jks` | Java Keystore path |
| `ssl.keystorePassword` | string | `mcpserver` | Keystore password |
| `ssl.keystoreType` | string | `JKS` | Keystore type (`JKS` / `PKCS12`) |
| `ssl.certPath` | string | `config/MCPServer/fullchain.pem` | PEM certificate chain path (takes priority over keystore) |
| `ssl.keyPath` | string | `config/MCPServer/privkey.key` | PEM private key path (RSA / PKCS8) |
| `tokenMode` | string | `"auto"` | Token mode: `auto` (random each startup) / `persistent` (fixed token) |
| `persistentToken` | string | `null` | Fixed token (used only in `persistent` mode; auto-generated and saved on first launch if empty) |
| `fileEditor` | boolean | `false` | Enable file-editing tools (`file_read`/`file_write`/`file_append`/`file_delete`/`file_list`) |
| `shellEnabled` | boolean | `false` | **High risk**: enable the Shell executor (lets AI run system commands) |
| `shellTimeoutMs` | int | `30000` | Shell command timeout in ms (1000–3600000) |

### SSL certificate loading priority

1. Both `certPath` and `keyPath` exist → use **PEM certificate**
2. `keystorePath` exists → use **Java Keystore**
3. Neither exists → fall back to **HTTP** mode and print a warning

### Config example

```json
{
  "fileEditor": false,
  "httpPort": 8081,
  "ssl": {
    "enabled": false,
    "keystorePath": "config/MCPServer/mcpserver-keystore.jks",
    "keystorePassword": "mcpserver",
    "keystoreType": "JKS",
    "certPath": "config/MCPServer/fullchain.pem",
    "keyPath": "config/MCPServer/privkey.key"
  },
  "tokenMode": "auto",
  "persistentToken": null,
  "shellEnabled": false,
  "shellTimeoutMs": 30000
}
```

## Connecting AI Clients

After the server starts, the console prints a client config automatically. Copy it into your AI client's config file.

### Claude Desktop / Cursor

Config file paths:
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "minecraft-mcp": {
      "url": "http://localhost:8081/mcp",
      "transport": "http",
      "headers": {
        "X-API-Key": "<your-token>"
      }
    }
  }
}
```

> How to get the token: check the server startup log, or run `/mcpserver token` in-game.

## MCP Tools

After an AI client connects, the following tools can be called via `tools/call`. Tools are registered conditionally: Spark tools require Spark; file tools require `fileEditor=true`; Shell tools require `shellEnabled=true`.

### Server monitoring (always available)

| Tool | Description | Parameters |
|------|-------------|------------|
| `get_server_status` | Overall server status (running state, version, online players, TPS, memory, entity count) | none |
| `get_tps_metrics` | TPS metrics (current/avg/min/max/history) | none |
| `get_memory_metrics` | Memory metrics (heap/non-heap, system info) | none |
| `get_entity_metrics` | Entity count statistics by category | none |
| `get_recent_logs` | Recent logs | `limit` int (optional, default 50); `filterType` string (optional: GENERAL/PLAYER_JOIN/PLAYER_LEAVE/CHAT/COMMAND/ERROR/WARN) |
| `get_player_events` | Player join/leave events | `limit` int (optional, default 20) |
| `get_chat_messages` | Chat messages | `limit` int (optional, default 20) |
| `get_command_history` | Command execution history | `limit` int (optional, default 20) |
| `get_player_info` | Comprehensive player info (position/biome/world/inventory/equipment/health/hunger/xp) | `playerName` string (optional, returns all online players if omitted) |

### Command execution (always available)

| Tool | Description | Parameters |
|------|-------------|------------|
| `execute_command` | Run a command on the server (no leading `/`) | `command` string (required); `confirmationToken` string (optional, for high-risk command re-confirmation) |

> **High-risk command re-confirmation**: commands such as `stop`/`restart`/`reload`/`op`/`deop`/`ban`/`kick`/`kill`/`save-all`/`gamerule`/`whitelist` return a confirmation token on first call; you must call again within 30 seconds with that token to execute.

### Spark profiling (requires Spark)

| Tool | Description | Parameters |
|------|-------------|------------|
| `spark_check_availability` | Check whether Spark is available (always registered) | none |
| `spark_start_profiler` | Start the profiler | `profilerType` string (optional: cpu/alloc/sampler); `duration` int (optional, seconds recommended) |
| `spark_stop_profiler` | Stop the profiler and save results | `profilerId` string (required) |
| `spark_get_profiler_status` | Get profiler status | `profilerId` string (required) |
| `spark_get_profiler_result` | Get analysis result (Base64, uploadable to spark.lucko.me) | `profilerId` string (required) |
| `spark_create_heap_dump` | Create a heap dump | none |
| `spark_get_health_report` | Get health report (TPS/MSPT/CPU/GC) | none |
| `spark_get_tps_report` | Get detailed TPS report | none |

### Player behavior recording (always available)

| Tool | Description | Parameters |
|------|-------------|------------|
| `behavior_recorder_status` | Recorder status, config, and recorded count | none |
| `behavior_track_player` | Add a player to the watch list | `playerName` string (required, `*` = all) |
| `behavior_untrack_player` | Remove a watched player | `playerName` string (required, `!all` = clear) |
| `behavior_clear_history` | Clear behavior history | `playerName` string (optional, clears all if omitted) |
| `behavior_get_latest` | Get the latest behavior snapshot | `playerName` string (required) |
| `behavior_query_history` | Query behavior history by time range | `playerName` string (required); `fromTs` long; `toTs` long; `limit` int (default 100); `format` string (json/csv) |

### Remote fake-player integration (always registered, requires RemoteFakePlayer mod)

| Tool | Description |
|------|-------------|
| `get_fake_player_count` | Number of fake players |
| `get_bound_container_count` | Number of bound containers |
| `get_total_item_count` | Total items in storage |
| `get_item_type_count` | Number of distinct item types |
| `get_item_stats` | Detailed item statistics |
| `get_fake_players` | List of fake players |
| `get_bound_containers` | List of bound containers |

### File editing (requires `fileEditor=true`)

| Tool | Description | Parameters |
|------|-------------|------------|
| `file_read` | Read a file under the server directory | `filePath` string (required) |
| `file_write` | Write a file (overwrite) | `filePath` string; `content` string |
| `file_append` | Append content to a file | `filePath` string; `content` string |
| `file_delete` | Delete a file | `filePath` string |
| `file_list` | List directory contents | `directory` string (optional, default server root) |

### Shell execution (requires `shellEnabled=true`)

| Tool | Description | Parameters |
|------|-------------|------------|
| `execute_shell` | Run a system command (Windows: `cmd /c`, Linux: `bash -c`) | `command` string; `workingDir` string (optional); `timeoutMs` int (optional, default 30000) |

## REST API

Besides the MCP protocol, the following REST endpoints are provided, authenticated via the same `X-API-Key` header (except `/health`).

| Path | Method | Description |
|------|--------|-------------|
| `/mcp` | POST | MCP JSON-RPC protocol endpoint |
| `/api/status` | GET | Server status |
| `/api/tps` | GET | TPS metrics |
| `/api/memory` | GET | Memory metrics |
| `/api/entities` | GET | Entity statistics |
| `/api/logs` | GET | Recent logs (params `type`, `limit`) |
| `/api/spark/availability` | GET | Spark availability |
| `/api/spark/tps` | GET | Spark TPS report |
| `/api/spark/health` | GET | Spark health report |
| `/api/spark/profiler/start` | GET | Start profiler (params `type`, `duration`) |
| `/api/spark/profiler/stop` | GET | Stop profiler (param `profilerId`) |
| `/api/spark/profiler/status` | GET | Profiler status (param `profilerId`) |
| `/api/spark/heapdump` | GET | Create heap dump |
| `/health` | GET | Health check (no auth) |

### Request examples

```bash
# Get server status
curl -H "X-API-Key: <your-token>" http://localhost:8081/api/status

# Call a tool via the MCP protocol
curl -X POST http://localhost:8081/mcp \
  -H "X-API-Key: <your-token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_server_status","arguments":{}}}'
```

## In-game Commands

### `/mcpserver` (requires OP level 4)

| Command | Description |
|---------|-------------|
| `/mcpserver token` | Show the current token (click to copy) |
| `/mcpserver newtoken` | Generate a new token (`persistent` mode saves to config) |
| `/mcpserver tokenmode` | Show the current token mode |
| `/mcpserver tokenmode <auto\|persistent>` | Switch token mode |
| `/mcpserver reload` | Hot-reload the config file |
| `/mcpserver shell status` | Show Shell executor status |
| `/mcpserver shell enable` | Enable the Shell executor |
| `/mcpserver shell disable` | Disable the Shell executor |
| `/mcpserver shell timeout <ms>` | Set Shell timeout |
| `/mcpserver shell <command>` | Run a single Shell command directly |

### `/behavior` (requires OP level 2)

| Command | Description |
|---------|-------------|
| `/behavior status` | Show recorder status |
| `/behavior track <player>` | Add a watched player (`*` = all, `!all` = clear) |
| `/behavior untrack <player>` | Remove a watched player (`!all` = clear) |
| `/behavior clear [player]` | Clear history (omit = all) |
| `/behavior latest <player>` | View the player's latest record |
| `/behavior query <player> [limit]` | Query player history (default 50) |

## Player Behavior Recording

The behavior recorder samples complete state snapshots of watched players at high frequency (default 500ms) and writes them to disk asynchronously.

### Recorded fields

Each record includes:

- **Basic**: timestamp, player name, UUID
- **Status**: health, max health, saturation, saturation level
- **Position**: XYZ coordinates, yaw, pitch, dimension
- **Movement**: movement speed, moving/on-ground/sprinting/sneaking/flying
- **Interaction**: main-hand/off-hand item ID and count, block ID under feet
- **Inventory**: full inventory snapshot (low-frequency, every 5s), with item ID, count, and durability per slot

### Data storage

- **Memory**: up to 7200 records per player (~1 hour), oldest dropped when exceeded
- **Disk**: `<server-root>/config/MCPServer/behave.log`, JSONL format (one JSON per line), append mode
- **Threads**: sampling thread + writing thread, both daemon, wait for flush on server shutdown

## Build from Source

### 1. Clone and restore the Gradle Wrapper

```bash
git clone https://github.com/QianKunBoss/Minecraft-MCPServer.git
cd Minecraft-MCPServer
python tools/restore_wrappers.py
```

> The binary `gradle-wrapper.jar` is distributed as base64 text with the repo; run the restore script after cloning.

### 2. Build

```bash
# Build the root project (1.20.1)
./gradlew clean remapJar

# Build all versions
python tools/build_all.py

# Build all versions (skip cache)
python tools/build_all.py --clean
```

Build artifacts are at `build/libs/MCPServer-*.jar` in each project.

### Supported Minecraft versions

| Version group | Versions | Java |
|---------------|----------|------|
| 1.20.x | 1.20.1 / 1.20.2 / 1.20.3 / 1.20.4 / 1.20.5 / 1.20.6 | 17 |
| 1.21.x | 1.21 / 1.21.1 ~ 1.21.11 | 17+ |

The root project corresponds to 1.20.1; the other versions each have an independent Gradle project under `versions/<mc-version>/`, sharing the `src/` source tree.

## Directory Structure

```
.
├── src/main/java/org/du/mcpserver/   # Shared source
│   ├── Mcpserver.java                 # Mod entry point
│   ├── http/MCPHttpServer.java        # HTTP/HTTPS server
│   ├── mcp/MCPProtocolHandler.java     # MCP protocol handler
│   ├── monitor/                       # Monitoring modules
│   │   ├── LogMonitor.java            #   log monitoring
│   │   ├── ServerMetrics.java         #   server metrics
│   │   ├── PlayerInfoManager.java     #   player info
│   │   └── behavior/                  #   behavior recording
│   ├── spark/SparkIntegration.java     # Spark integration
│   ├── command/                       # In-game commands
│   └── util/                          # Utilities (config, security, etc.)
├── tools/                             # Build scripts and tools
│   ├── build_all.py                   # Full build script
│   └── restore_wrappers.py            # Wrapper jar restore script
├── versions/<mc-version>/             # Independent Gradle project per MC version
├── gradle/wrapper/                    # Gradle Wrapper
└── src/main/resources/fabric.mod.json # Mod metadata
```

## Security Notes

- **Token is a credential**: the `persistentToken` in `config.json` and the token printed in the startup log are keys to your server — keep them safe.
- **Shell executor is high risk**: `shellEnabled=true` lets AI run arbitrary system commands; use only in trusted environments.
- **File editor**: `fileEditor=true` lets AI read/write files under the server directory; enable with caution.
- **SSL configuration**: when exposing the service publicly, always enable SSL and use a strong token.
- **Config files are not committed**: `config.json`, `*.jks`, `*.pem`, `*.key` are all excluded by `.gitignore`.
- **High-risk command re-confirmation**: commands like `stop`/`op`/`ban` require a second confirmation within 30 seconds.

## Dependencies

| Dependency | Type | Version |
|------------|------|---------|
| Fabric Loader | Required | ≥ 0.14.0 |
| Fabric API | Required | * |
| Java | Required | ≥ 17 |
| [Spark](https://spark.lucko.me/) | Optional | ≥ 1.10.0 |

## License

This project is open source under the [GPL-3.0 License](LICENSE).
