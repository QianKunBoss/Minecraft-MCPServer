# MCPServer — Minecraft Fabric 模组的 MCP 服务端

一个基于 Fabric 的 Minecraft 服务端模组，把服务器能力以 **MCP (Model Context Protocol)** 的形式通过 HTTP/HTTPS 暴露给 AI 助手（如 Claude Desktop、Cursor 等），让 AI 可以查询服务器状态、读取日志、管理玩家、触发性能分析、执行命令、记录行为等。

## 核心特性

- **MCP over HTTP/HTTPS**：实现标准 MCP JSON-RPC 协议（版本 `2024-11-05`，Streamable HTTP 传输），兼容 Claude Desktop、Cursor 等 MCP 客户端
- **37 个 AI 可调用工具**：服务器监控、命令执行、Spark 性能分析、玩家行为记录、文件编辑、Shell 执行、远程假人集成
- **14 个 REST API 端点**：除 MCP 协议外，还提供等价的 REST 接口，方便非 MCP 客户端直接调用
- **Spark 性能集成**（可选）：安装 Spark 模组后，AI 可启动/停止 Profiler、获取健康报告、创建堆转储；未安装时优雅降级，不阻塞服务器
- **玩家行为记录**：高频采样玩家位置/生命/移动/背包等 23 个字段，异步写入磁盘（JSONL 格式），支持按时间范围查询与 CSV 导出
- **多版本 Minecraft 支持**：1.20.1 ~ 1.21.11 共 18 个真实版本，单一共享源码 + 每版本独立 Gradle 工程
- **优雅关服**：正确关闭 HTTP 线程池与后台守护线程，避免进程卡死
- **配置容错**：lenient JSON 解析，损坏的配置不会导致服务器崩溃
- **Token 认证**：支持 auto（每次启动随机）和 persistent（固定 token）两种模式
- **SSL/HTTPS**：支持 PEM 证书（Let's Encrypt 风格）和 Java Keystore 两种方式

## 安装

### 前置要求

- Minecraft 服务器端（Fabric Loader ≥ 0.14.0）
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java ≥ 17

### 步骤

1. 从 [Releases](https://github.com/QianKunBoss/Minecraft-MCPServer/releases) 下载对应版本的 jar（如 `MCPServer-1.3.0-1.20.1.jar`）
2. 将 jar 放入服务器的 `mods/` 目录
3. （可选）安装 [Spark](https://spark.lucko.me/)（≥ 1.10.0）获取性能分析能力
4. 启动服务器，模组会自动生成 `config/MCPServer/config.json`
5. 查看服务器控制台日志，会打印 **MCP Endpoint**、**API Key** 和客户端配置示例

## 配置

配置文件位于 `<服务器根目录>/config/MCPServer/config.json`，首次启动自动生成。支持 `//` 注释和尾逗号（lenient 解析）。

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `httpPort` | int | `8081` | MCP HTTP 服务器端口（1–65535，修改后需重启） |
| `ssl.enabled` | boolean | `false` | 是否启用 HTTPS（修改后需重启） |
| `ssl.keystorePath` | string | `config/MCPServer/mcpserver-keystore.jks` | Java Keystore 路径 |
| `ssl.keystorePassword` | string | `mcpserver` | Keystore 密码 |
| `ssl.keystoreType` | string | `JKS` | Keystore 类型（`JKS` / `PKCS12`） |
| `ssl.certPath` | string | `config/MCPServer/fullchain.pem` | PEM 证书链路径（优先于 keystore） |
| `ssl.keyPath` | string | `config/MCPServer/privkey.key` | PEM 私钥路径（支持 RSA / PKCS8） |
| `tokenMode` | string | `"auto"` | Token 模式：`auto`（每次启动随机生成）/ `persistent`（固定 token） |
| `persistentToken` | string | `null` | 固定 token（仅 `persistent` 模式使用，为空时首次启动自动生成并保存） |
| `fileEditor` | boolean | `false` | 是否启用文件编辑工具（`file_read`/`file_write`/`file_append`/`file_delete`/`file_list`） |
| `shellEnabled` | boolean | `false` | **高危**：是否启用 Shell 执行器（允许 AI 执行系统命令） |
| `shellTimeoutMs` | int | `30000` | Shell 命令超时毫秒（1000–3600000） |

### SSL 证书加载优先级

1. `certPath` 和 `keyPath` 文件都存在 → 使用 **PEM 证书**
2. `keystorePath` 存在 → 使用 **Java Keystore**
3. 都不存在 → 回退到 **HTTP** 模式并打印警告

### 配置示例

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

## 连接 AI 客户端

服务器启动后，控制台会自动打印客户端配置。将其复制到你的 AI 客户端配置文件中即可。

### Claude Desktop / Cursor

配置文件路径：
- Windows: `%APPDATA%\Claude\claude_desktop_config.json`
- macOS: `~/Library/Application Support/Claude/claude_desktop_config.json`

```json
{
  "mcpServers": {
    "minecraft-mcp": {
      "url": "http://localhost:8081/mcp",
      "transport": "http",
      "headers": {
        "X-API-Key": "<你的-token>"
      }
    }
  }
}
```

> Token 获取方式：查看服务器启动日志，或在游戏内执行 `/mcpserver token`。

## MCP 工具列表

AI 客户端连接后可通过 `tools/call` 调用以下工具。工具按条件注册：Spark 类工具需安装 Spark，文件类需 `fileEditor=true`，Shell 类需 `shellEnabled=true`。

### 服务器监控（始终可用）

| 工具 | 说明 | 参数 |
|------|------|------|
| `get_server_status` | 获取服务器整体状态（运行状态、版本、在线玩家、TPS、内存、实体数） | 无 |
| `get_tps_metrics` | 获取 TPS 指标（当前/平均/最小/最大/历史） | 无 |
| `get_memory_metrics` | 获取内存使用指标（堆/非堆内存、系统信息） | 无 |
| `get_entity_metrics` | 获取服务器各类实体数量统计 | 无 |
| `get_recent_logs` | 获取最近日志 | `limit` int (可选, 默认50)；`filterType` string (可选: GENERAL/PLAYER_JOIN/PLAYER_LEAVE/CHAT/COMMAND/ERROR/WARN) |
| `get_player_events` | 获取玩家进出事件 | `limit` int (可选, 默认20) |
| `get_chat_messages` | 获取聊天消息 | `limit` int (可选, 默认20) |
| `get_command_history` | 获取命令执行历史 | `limit` int (可选, 默认20) |
| `get_player_info` | 获取玩家综合信息（位置/群系/世界/背包/装备/生命/饥饿/经验） | `playerName` string (可选, 不填返回所有在线玩家) |

### 命令执行（始终可用）

| 工具 | 说明 | 参数 |
|------|------|------|
| `execute_command` | 在服务器执行命令（不含前导 `/`） | `command` string (必填)；`confirmationToken` string (可选, 高危命令二次确认) |

> **高危命令二次确认**：`stop`/`restart`/`reload`/`op`/`deop`/`ban`/`kick`/`kill`/`save-all`/`gamerule`/`whitelist` 等命令首次调用会返回确认令牌，30 秒内携带令牌再次调用才执行。

### Spark 性能分析（需安装 Spark）

| 工具 | 说明 | 参数 |
|------|------|------|
| `spark_check_availability` | 检查 Spark 是否可用（始终注册） | 无 |
| `spark_start_profiler` | 启动性能分析器 | `profilerType` string (可选: cpu/alloc/sampler)；`duration` int (可选, 建议秒数) |
| `spark_stop_profiler` | 停止分析器并保存结果 | `profilerId` string (必填) |
| `spark_get_profiler_status` | 获取分析器状态 | `profilerId` string (必填) |
| `spark_get_profiler_result` | 获取分析结果（Base64，可上传 spark.lucko.me） | `profilerId` string (必填) |
| `spark_create_heap_dump` | 创建堆转储 | 无 |
| `spark_get_health_report` | 获取健康报告（TPS/MSPT/CPU/GC） | 无 |
| `spark_get_tps_report` | 获取详细 TPS 报告 | 无 |

### 玩家行为记录（始终可用）

| 工具 | 说明 | 参数 |
|------|------|------|
| `behavior_recorder_status` | 获取记录器状态、配置、已记录条数 | 无 |
| `behavior_track_player` | 添加玩家到监测列表 | `playerName` string (必填, `*` = 全体) |
| `behavior_untrack_player` | 移除监测玩家 | `playerName` string (必填, `!all` = 清空) |
| `behavior_clear_history` | 清空行为记录 | `playerName` string (可选, 不填清空全部) |
| `behavior_get_latest` | 获取玩家最新行为快照 | `playerName` string (必填) |
| `behavior_query_history` | 按时间查询行为历史 | `playerName` string (必填)；`fromTs` long；`toTs` long；`limit` int (默认100)；`format` string (json/csv) |

### 远程假人集成（始终注册，需 RemoteFakePlayer 模组）

| 工具 | 说明 |
|------|------|
| `get_fake_player_count` | 获取假人数量 |
| `get_bound_container_count` | 获取绑定容器数量 |
| `get_total_item_count` | 获取仓库物品总数 |
| `get_item_type_count` | 获取物品种类数量 |
| `get_item_stats` | 获取物品详细统计 |
| `get_fake_players` | 获取假人列表 |
| `get_bound_containers` | 获取绑定容器列表 |

### 文件编辑（需 `fileEditor=true`）

| 工具 | 说明 | 参数 |
|------|------|------|
| `file_read` | 读取服务器目录下文件 | `filePath` string (必填) |
| `file_write` | 写入文件（覆盖） | `filePath` string；`content` string |
| `file_append` | 追加内容到文件 | `filePath` string；`content` string |
| `file_delete` | 删除文件 | `filePath` string |
| `file_list` | 列出目录内容 | `directory` string (可选, 默认服务器根目录) |

### Shell 执行（需 `shellEnabled=true`）

| 工具 | 说明 | 参数 |
|------|------|------|
| `execute_shell` | 执行系统命令（Windows: `cmd /c`，Linux: `bash -c`） | `command` string；`workingDir` string (可选)；`timeoutMs` int (可选, 默认30000) |

## REST API

除 MCP 协议外，还提供以下 REST 端点，认证方式同为 `X-API-Key` 请求头（`/health` 除外）。

| 路径 | 方法 | 说明 |
|------|------|------|
| `/mcp` | POST | MCP JSON-RPC 协议端点 |
| `/api/status` | GET | 服务器状态 |
| `/api/tps` | GET | TPS 指标 |
| `/api/memory` | GET | 内存指标 |
| `/api/entities` | GET | 实体统计 |
| `/api/logs` | GET | 最近日志（参数 `type`、`limit`） |
| `/api/spark/availability` | GET | Spark 可用性 |
| `/api/spark/tps` | GET | Spark TPS 报告 |
| `/api/spark/health` | GET | Spark 健康报告 |
| `/api/spark/profiler/start` | GET | 启动 Profiler（参数 `type`、`duration`） |
| `/api/spark/profiler/stop` | GET | 停止 Profiler（参数 `profilerId`） |
| `/api/spark/profiler/status` | GET | Profiler 状态（参数 `profilerId`） |
| `/api/spark/heapdump` | GET | 创建堆转储 |
| `/health` | GET | 健康检查（无需认证） |

### 请求示例

```bash
# 获取服务器状态
curl -H "X-API-Key: <your-token>" http://localhost:8081/api/status

# 通过 MCP 协议调用工具
curl -X POST http://localhost:8081/mcp \
  -H "X-API-Key: <your-token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"get_server_status","arguments":{}}}'
```

## 游戏内命令

### `/mcpserver`（需要 OP 等级 4）

| 命令 | 说明 |
|------|------|
| `/mcpserver token` | 显示当前 Token（可点击复制） |
| `/mcpserver newtoken` | 生成新 Token（`persistent` 模式会保存到配置文件） |
| `/mcpserver tokenmode` | 查看当前 Token 模式 |
| `/mcpserver tokenmode <auto\|persistent>` | 切换 Token 模式 |
| `/mcpserver reload` | 热重载配置文件 |
| `/mcpserver shell status` | 查看 Shell 执行器状态 |
| `/mcpserver shell enable` | 启用 Shell 执行器 |
| `/mcpserver shell disable` | 禁用 Shell 执行器 |
| `/mcpserver shell timeout <ms>` | 设置 Shell 超时 |
| `/mcpserver shell <command>` | 直接执行一条 Shell 命令 |

### `/behavior`（需要 OP 等级 2）

| 命令 | 说明 |
|------|------|
| `/behavior status` | 显示记录器状态 |
| `/behavior track <player>` | 添加监测玩家（`*` = 全体，`!all` = 清空） |
| `/behavior untrack <player>` | 移除监测玩家（`!all` = 清空） |
| `/behavior clear [player]` | 清空历史（不填 = 全部） |
| `/behavior latest <player>` | 查看玩家最新记录 |
| `/behavior query <player> [limit]` | 查询玩家历史（默认 50 条） |

## 玩家行为记录

行为记录器以高频采样（默认 500ms）记录被监测玩家的完整状态快照，异步写入磁盘。

### 记录的字段

每条记录包含以下信息：

- **基础**：时间戳、玩家名、UUID
- **状态**：生命值、最大生命值、饱食度、饱和度
- **位置**：XYZ 坐标、偏航角、俯仰角、维度
- **移动**：移动速度、是否移动/着地/疾跑/潜行/飞行
- **交互**：主手/副手物品 ID 与数量、脚下方块 ID
- **背包**：完整背包快照（低频采样，每 5 秒一次），含每个槽位的物品 ID、数量、耐久

### 数据存储

- **内存**：每玩家最多保留 7200 条（约 1 小时），超出丢弃最旧
- **磁盘**：`config/MCPServer/behave.log`，JSONL 格式（每行一条 JSON），追加模式
- **线程**：采样线程 + 写入线程双守护线程异步工作，服务器关闭时等待落盘完成

## 从源码构建

### 1. 克隆并还原 Gradle Wrapper

```bash
git clone https://github.com/QianKunBoss/Minecraft-MCPServer.git
cd Minecraft-MCPServer
python tools/restore_wrappers.py
```

> 二进制 `gradle-wrapper.jar` 以 base64 文本形式随仓库分发，克隆后需运行还原脚本。

### 2. 构建

```bash
# 构建根工程（1.20.1）
./gradlew clean remapJar

# 全量构建所有版本
python tools/build_all.py

# 全量构建（跳过缓存）
python tools/build_all.py --clean
```

构建产物位于各工程的 `build/libs/MCPServer-*.jar`。

### 支持的 Minecraft 版本

| 版本组 | 版本 | Java |
|--------|------|------|
| 1.20.x | 1.20.1 / 1.20.2 / 1.20.3 / 1.20.4 / 1.20.5 / 1.20.6 | 17 |
| 1.21.x | 1.21 / 1.21.1 ~ 1.21.11 | 17+ |

根工程对应 1.20.1，其余版本在 `versions/<版本号>/` 目录下各有独立 Gradle 工程，共享 `src/` 源码。

## 目录结构

```
.
├── src/main/java/org/du/mcpserver/   # 共享源码
│   ├── Mcpserver.java                 # 模组入口
│   ├── http/MCPHttpServer.java        # HTTP/HTTPS 服务器
│   ├── mcp/MCPProtocolHandler.java     # MCP 协议处理器
│   ├── monitor/                       # 监控模块
│   │   ├── LogMonitor.java            #   日志监控
│   │   ├── ServerMetrics.java         #   服务器指标
│   │   ├── PlayerInfoManager.java     #   玩家信息
│   │   └── behavior/                  #   行为记录
│   ├── spark/SparkIntegration.java    # Spark 集成
│   ├── command/                       # 游戏内命令
│   └── util/                          # 工具类（配置、安全等）
├── tools/                             # 构建脚本与工具
│   ├── build_all.py                   # 全量构建脚本
│   └── restore_wrappers.py            # Wrapper jar 还原脚本
├── versions/<mc-version>/             # 各 MC 版本独立 Gradle 工程
├── gradle/wrapper/                    # Gradle Wrapper
└── src/main/resources/fabric.mod.json # 模组元数据
```

## 安全提示

- **Token 是访问凭据**：`config.json` 中的 `persistentToken` 和启动日志中打印的 Token 是访问服务器的钥匙，请妥善保管。
- **Shell 执行器高危**：`shellEnabled=true` 允许 AI 执行任意系统命令，仅在可信环境使用。
- **文件编辑器**：`fileEditor=true` 允许 AI 读写服务器目录下文件，谨慎启用。
- **SSL 配置**：对外暴露服务时务必启用 SSL 并配置强 Token。
- **配置文件不入库**：`config.json`、`*.jks`、`*.pem`、`*.key` 均已被 `.gitignore` 排除。
- **高危命令二次确认**：`stop`/`op`/`ban` 等命令需 30 秒内二次确认才会执行。

## 依赖

| 依赖 | 类型 | 版本 |
|------|------|------|
| Fabric Loader | 必须 | ≥ 0.14.0 |
| Fabric API | 必须 | * |
| Java | 必须 | ≥ 17 |
| [Spark](https://spark.lucko.me/) | 可选 | ≥ 1.10.0 |

## 许可证

本项目基于 [GPL-3.0 License](LICENSE) 开源。
