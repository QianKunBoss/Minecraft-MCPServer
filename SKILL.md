---
name: minecraft-spark-profiling
description: 通过 Minecraft MCP 工具对服务器进行 Spark CPU 性能分析，包括启动分析、等待采集、获取结果、解码二进制数据、从调用树中提取实体堆积与高负载区域信息、生成完整性能报告。当用户要求 Spark 性能分析、CPU profiling、TPS 诊断、服务器卡顿排查时使用。
version: 1.2.0
---

# Minecraft Spark CPU Profiling

通过 MCP 工具完成 Spark CPU 性能分析的完整流程。

## Prerequisites

- Minecraft MCP server 已连接（提供 `spark_*` 等工具）
- 服务器已安装 Spark 模组（Fabric/Forge）
- 调用前先通过 `qw_mcp_list` 确认以下 MCP 工具可用：
  - `spark_check_availability`
  - `spark_start_profiler`
  - `spark_stop_profiler`
  - `spark_get_profiler_result`
  - `spark_get_health_report`

## Workflow

### Step 1: 检查 Spark 可用性

调用 `spark_check_availability`，确认 Spark 插件已加载且可用。如果不可用，告知用户检查服务器是否安装了 Spark 模组。

### Step 2: 启动 Profiler

调用 `spark_start_profiler`，参数：
- `duration`：采集秒数（默认 30，用户可指定 60 或 120）
- `profilerType`：默认 `"cpu"`

**记录返回的 `profilerId`**，后续步骤都需要它。

### Step 3: 等待采集完成

使用 `sleep` 等待 `duration + 2` 秒（留 2 秒缓冲）。例如 duration=30 则 sleep 32 秒。

### Step 4: 停止 Profiler

调用 `spark_stop_profiler`，传入 `profilerId`。

### Step 5: 等待结果文件就绪

停止后 `sleep 5` 秒，然后进入获取结果流程。

### Step 6: 获取 Profiler 结果（含重试）

调用 `spark_get_profiler_result`，传入 `profilerId`。

**重试逻辑**：
1. 首次调用可能返回"不可用"或仅返回 `activity.json`（活动记录，base64 解码后是 JSON 数组）
2. 如果返回的不是实际分析数据，`sleep 10` 后重试
3. 最多重试 3~4 次，间隔递增（5s → 10s → 15s）
4. 返回的有效数据是 base64 编码的 `.sparkprofile` 二进制文件

### Step 7: 获取健康报告

调用 `spark_get_health_report`，获取 TPS、MSPT、CPU、GC 等实时指标。此步骤可与 Step 6 并行执行。

### Step 8: 通过 API 解码 Base64 数据

将 Step 6 获取的 base64 字符串通过 API 解码：

```bash
curl -X POST 'https://uapis.cn/api/v1/text/base64/decode' \
  -H 'Content-Type: application/json' \
  -d '{"text": "<base64_string>"}'
```

API 返回解码后的数据（即 `.sparkprofile` 二进制内容）。如果 base64 数据较大，可分段发送请求。

### Step 9: 分析二进制数据

对 Step 8 解码后的二进制数据进行分析。

`.sparkprofile` 是 Spark 的 protobuf 二进制格式，解析要点：
- 文件包含元数据（服务器信息、Java 版本、模组列表等）
- 包含线程采样数据，重点关注 **"Server thread"** 段
- 方法名以可读字符串形式存储，旁边有 IEEE 754 double 类型的耗时值
- 用正则提取可读字符串段（`re.findall(b'[\x20-\x7e]{10,}', data)`），结合附近的 double 值定位方法耗时

从调用树中重点提取以下信息：

**实体相关方法**：
- `ServerEntityManager` 相关方法（实体管理、tick、shouldTick 等）
- `SpawnHelper` / `SpawnDensityCapper` / `tickSpawners`（生物生成系统）
- `EntityTracker` / `ChunkHolder` 相关方法（实体追踪与区块持有）
- 任何包含 `entity`、`spawn`、`mob` 关键字的方法

**区块与区域负载方法**：
- `ChunkTicketManager`（区块票据管理，purge / getTickedChunkCount 等）
- `ThreadedAnvilChunkStorage`（区块存储与保存）
- `ServerChunkManager` / `MainThreadExecutor`（区块调度）
- `WorldTickScheduler`（区块内 tick 调度）
- `LevelStorage` / `NbtIo`（存档 I/O，可能指示大面积保存操作）

**分析要点**：
- 如果实体相关方法累计耗时占比高（>15%），说明存在实体堆积或生成压力过大
- 如果区块管理方法耗时异常（如 ChunkTicketManager >100ms），说明加载区块过多或票据堆积
- 如果出现大量 `save` / `NbtIo.writeCompressed` 方法，说明正在进行大规模存档 I/O
- 将实体/区块相关耗时与总 tick 时间对比，判断是否为性能瓶颈
- 在报告中明确指出哪些实体/区块子系统是瓶颈，并给出具体优化建议（如降低 simulation-distance、限制实体生成等）

### Step 10: 生成分析报告

综合所有数据，按以下模板生成报告。

## Report Template

报告应包含以下部分，使用 Markdown 表格呈现：

### TPS 状态表
| 时间窗口 | 当前值 | 状态 |
|---------|--------|------|
| 5 秒 | — | ✅/⚠️/🔴 |
| 10 秒 | — | ✅/⚠️/🔴 |
| 1 分钟 | — | ✅/⚠️/🔴 |
| 5 分钟 | — | ✅/⚠️/🔴 |
| 15 分钟 | — | ✅/⚠️/🔴 |

状态判定：≥19 ✅ | 15~19 ⚠️ | <15 🔴

### MSPT 表
| 时间窗口 | 均值 | 中位数 | 95分位 | 99分位 | 最大值 |
|---------|------|--------|--------|--------|--------|

### CPU 与 GC 摘要
- 进程 CPU / 系统 CPU
- GC 类型、次数、平均耗时、总耗时
- 堆内存使用情况

### 实体与区域负载分析（来自 Step 9，基于二进制数据）
- 实体相关方法耗时排名及占比
- 区块管理相关方法耗时排名及占比
- 是否存在存档 I/O 压力
- 判断实体/区块子系统是否为性能瓶颈

### 调用树耗时分布
按耗时降序排列，至少列出 Top 15：
| 排名 | 模块 | 方法 | 耗时 |

### 分析与优化建议
- 识别主要性能瓶颈
- 与历史数据对比（如有）
- 给出具体的 `server.properties` 或 JVM 参数调整建议
- 如果发现实体堆积或区块压力，指出具体子系统并建议优化措施

## Pitfalls

- **不要自行读取磁盘文件**：必须通过 MCP 工具获取数据，不要主动去服务器目录找 `.sparkprofile` 文件
- **结果文件延迟**：`spark_get_profiler_result` 可能多次返回"不可用"或仅返回 `activity.json`，需要 sleep 后重试
- **Windows 平台限制**：`async-profiler` 不支持 Windows，Spark 回退到内置 Java 引擎，数据格式可能不同
- **Profiler 自身开销**：Spark 采样本身占用约 60~150ms CPU 时间，分析完成后建议关闭，不要在分析期间做其他重操作
- **空服务器偏差**：没有玩家在线时，区块加载不完整，实体相关方法耗时可能偏低，报告需注明这一限制
