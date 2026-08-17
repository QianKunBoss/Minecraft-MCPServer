# MCPServer API Documentation

## Overview

MCPServer is a Fabric mod that provides an MCP (Model Context Protocol) service interface for AI agents to monitor and analyze Minecraft Java Edition servers.

## Quick Start

1. Install the MCPServer mod in your Fabric server's `mods/` directory
2. Start the server - the mod will automatically generate an API key
3. Find the API key in the server console output
4. Use the MCP protocol or REST API to interact with the server

## Configuration

- **Default Port**: 8080
- **API Key**: Automatically generated on server startup, displayed in console
- **Session Timeout**: 1 hour

## MCP Protocol

The MCP protocol endpoint is available at `POST /mcp`. All requests follow JSON-RPC 2.0 format.

### Request Format

```json
{
  "jsonrpc": "2.0",
  "method": "METHOD_NAME",
  "params": { ... },
  "id": 1
}
```

### Response Format

```json
{
  "jsonrpc": "2.0",
  "result": { ... },
  "id": 1
}
```

### Error Format

```json
{
  "jsonrpc": "2.0",
  "error": {
    "code": -32601,
    "message": "Method not found"
  },
  "id": 1
}
```

### Protocol Methods

#### 1. initialize

Authenticate and create a session.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "initialize",
  "params": {
    "apiKey": "your-api-key-here"
  },
  "id": 1
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "result": {
    "sessionId": "uuid-string",
    "version": "1.0.0",
    "name": "MCPServer",
    "description": "MCP service for Minecraft server monitoring and analysis",
    "capabilities": {
      "streaming": false,
      "batch": false
    }
  },
  "id": 1
}
```

#### 2. tools/list

Get the list of available tools.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "tools/list",
  "params": {
    "sessionId": "your-session-id"
  },
  "id": 1
}
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "result": [
    {
      "name": "get_server_status",
      "description": "获取服务器整体状态信息",
      "parameters": {},
      "returns": "返回服务器运行状态、版本、在线玩家、TPS、内存使用、实体数量等综合信息"
    }
    // ... more tools
  ],
  "id": 1
}
```

#### 3. tools/call

Call a specific tool.

**Request:**
```json
{
  "jsonrpc": "2.0",
  "method": "tools/call",
  "params": {
    "name": "get_server_status",
    "arguments": {
      "sessionId": "your-session-id"
    }
  },
  "id": 1
}
```

## Available Tools

### Server Status Tools

| Tool Name | Description | Parameters |
|-----------|-------------|------------|
| `get_server_status` | 获取服务器整体状态信息 | None |
| `get_tps_metrics` | 获取TPS指标 | None |
| `get_memory_metrics` | 获取内存使用指标 | None |
| `get_entity_metrics` | 获取实体统计信息 | None |

### Log Monitoring Tools

| Tool Name | Description | Parameters |
|-----------|-------------|------------|
| `get_recent_logs` | 获取最近日志 | `limit` (int, optional, default 50), `filterType` (string, optional) |
| `get_player_events` | 获取玩家进出事件 | `limit` (int, optional, default 20) |
| `get_chat_messages` | 获取聊天消息 | `limit` (int, optional, default 20) |

### Spark Integration Tools

| Tool Name | Description | Parameters |
|-----------|-------------|------------|
| `spark_check_availability` | 检查Spark模组可用性 | None |
| `spark_start_profiler` | 启动Spark性能分析器 | `profilerType` (string, required), `duration` (int, optional, default 30) |
| `spark_stop_profiler` | 停止Spark性能分析器 | `profilerId` (string, required) |
| `spark_get_profiler_status` | 获取Spark分析器状态 | `profilerId` (string, required) |
| `spark_create_heap_dump` | 创建堆转储 | None |
| `spark_get_health_report` | 获取Spark健康报告 | None |
| `spark_get_tps_report` | 获取Spark TPS报告 | None |

### System Tools

| Tool Name | Description | Parameters |
|-----------|-------------|------------|
| `execute_command` | 执行服务器命令 | `command` (string, required) |

## REST API

The REST API requires an API key in the `X-API-Key` header.

### Health Check

```
GET /health
```

**Response:**
```json
{
  "status": "healthy",
  "timestamp": 1625097600000
}
```

### Server Status

```
GET /api/status
```

### TPS Metrics

```
GET /api/tps
```

**Response:**
```json
{
  "current": 20.0,
  "average": 19.8,
  "min": 19.5,
  "max": 20.0,
  "history": [20.0, 19.9, 20.0, ...]
}
```

### Memory Metrics

```
GET /api/memory
```

**Response:**
```json
{
  "heap": {
    "used": 512000000,
    "max": 2048000000,
    "committed": 1024000000,
    "init": 256000000,
    "usagePercent": 25.0
  },
  "nonHeap": { ... },
  "system": {
    "availableProcessors": 8,
    "systemLoadAverage": 0.5
  },
  "history": [...]
}
```

### Entity Metrics

```
GET /api/entities
```

**Response:**
```json
{
  "totalEntities": 1500,
  "totalPlayers": 10,
  "entityTypes": {
    "minecraft:player": 10,
    "minecraft:zombie": 50,
    "minecraft:cow": 30
  },
  "history": [...]
}
```

### Logs

```
GET /api/logs?type=ERROR&limit=50
```

**Parameters:**
- `type`: Filter by log type (GENERAL, PLAYER_JOIN, PLAYER_LEAVE, CHAT, COMMAND, ERROR, WARN)
- `limit`: Number of logs to return (default 50)

### Spark API

#### Check Availability

```
GET /api/spark/availability
```

#### Start Profiler

```
POST /api/spark/profiler/start?type=cpu&duration=30
```

**Parameters:**
- `type`: cpu, memory, allocs, sampler, default
- `duration`: seconds (default 30)

#### Stop Profiler

```
POST /api/spark/profiler/stop?profilerId=uuid
```

#### Get Profiler Status

```
POST /api/spark/profiler/status?profilerId=uuid
```

#### Create Heap Dump

```
POST /api/spark/heapdump
```

#### Get TPS Report

```
GET /api/spark/tps
```

#### Get Health Report

```
GET /api/spark/health
```

## Code Examples

### Python Example (MCP Protocol)

```python
import requests
import json

API_KEY = "your-api-key"
BASE_URL = "http://localhost:8080/mcp"

def mcp_request(method, params=None):
    payload = {
        "jsonrpc": "2.0",
        "method": method,
        "params": params or {},
        "id": 1
    }
    response = requests.post(BASE_URL, json=payload)
    return response.json()

# Initialize session
init_result = mcp_request("initialize", {"apiKey": API_KEY})
session_id = init_result["result"]["sessionId"]
print(f"Session ID: {session_id}")

# Get tools list
tools_result = mcp_request("tools/list", {"sessionId": session_id})
print(f"Available tools: {[t['name'] for t in tools_result['result']]}")

# Get server status
status_result = mcp_request("tools/call", {
    "name": "get_server_status",
    "arguments": {"sessionId": session_id}
})
print(f"Server status: {status_result['result']}")
```

### JavaScript Example (REST API)

```javascript
const API_KEY = "your-api-key";
const BASE_URL = "http://localhost:8080";

async function getServerStatus() {
    const response = await fetch(`${BASE_URL}/api/status`, {
        headers: {
            "X-API-Key": API_KEY
        }
    });
    return await response.json();
}

async function getTPSMetrics() {
    const response = await fetch(`${BASE_URL}/api/tps`, {
        headers: {
            "X-API-Key": API_KEY
        }
    });
    return await response.json();
}

async function startSparkProfiler(type, duration) {
    const response = await fetch(`${BASE_URL}/api/spark/profiler/start?type=${type}&duration=${duration}`, {
        method: "POST",
        headers: {
            "X-API-Key": API_KEY
        }
    });
    return await response.json();
}

// Usage
getServerStatus().then(status => console.log(status));
getTPSMetrics().then(tps => console.log(tps));
```

## Log Types

| Type | Description |
|------|-------------|
| GENERAL | General server logs |
| PLAYER_JOIN | Player join events |
| PLAYER_LEAVE | Player leave events |
| CHAT | Player chat messages |
| COMMAND | Server commands executed |
| ERROR | Error messages |
| WARN | Warning messages |

## Spark Profiler Types

| Type | Description |
|------|-------------|
| cpu | CPU usage profiling |
| memory | Memory usage profiling |
| allocs | Memory allocation profiling |
| sampler | Performance sampling |
| default | Default profiling mode |

## Notes

1. **API Key**: The API key is generated on server startup and displayed in the console. Keep it secure.
2. **Session Management**: Sessions expire after 1 hour of inactivity.
3. **Spark Mod**: Spark mod is optional but recommended for performance analysis features.
4. **Heap Dump**: Creating a heap dump may cause server lag, recommend running during low-traffic periods.
5. **Concurrency**: The HTTP server uses a thread pool with 8 threads for handling requests.
6. **Log Buffer**: The log monitor keeps up to 1000 recent log entries in memory.
7. **TPS History**: TPS metrics are stored for the last 60 seconds.
8. **Metrics History**: Memory and entity metrics are stored for the last 30 samples.
