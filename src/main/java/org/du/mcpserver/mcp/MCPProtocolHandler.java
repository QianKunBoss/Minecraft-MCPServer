package org.du.mcpserver.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.du.mcpserver.monitor.LogMonitor;
import org.du.mcpserver.monitor.PlayerInfoManager;
import org.du.mcpserver.monitor.ServerMetrics;
import org.du.mcpserver.spark.SparkIntegration;
import org.du.mcpserver.util.ConfigManager;
import org.du.mcpserver.util.FileEditor;
import org.du.mcpserver.util.JsonUtils;
import org.du.mcpserver.util.MCPProtocolValidator;
import org.du.mcpserver.util.MCCompat;
import org.du.mcpserver.util.ShellExecutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MCPProtocolHandler {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");

    private static final String[] DANGEROUS_COMMANDS = {
            "stop", "restart", "reload", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip",
            "kick", "kill", "save-all", "save-off", "save-on", "worldborder", "gamerule",
            "scoreboard", "team", "whitelist", "forge", "fabric"
    };

    private static final long CONFIRMATION_TIMEOUT_SECONDS = 30;

    private final LogMonitor logMonitor;
    private final ServerMetrics serverMetrics;
    private final PlayerInfoManager playerInfoManager;
    private final SparkIntegration sparkIntegration;
    private volatile String apiKey;
    private final ConfigManager configManager;
    private final FileEditor fileEditor;
    private volatile ShellExecutor shellExecutor;
    private final Map<String, PendingCommand> pendingCommands = new ConcurrentHashMap<>();
    private Thread cleanupThread;

    public MCPProtocolHandler(LogMonitor logMonitor, ServerMetrics serverMetrics,
                              PlayerInfoManager playerInfoManager, SparkIntegration sparkIntegration,
                              String apiKey, ConfigManager configManager, FileEditor fileEditor) {
        this.logMonitor = logMonitor;
        this.serverMetrics = serverMetrics;
        this.playerInfoManager = playerInfoManager;
        this.sparkIntegration = sparkIntegration;
        this.apiKey = apiKey;
        this.configManager = configManager;
        this.fileEditor = fileEditor;
        this.shellExecutor = serverMetrics != null && serverMetrics.getServer() != null && configManager != null
                ? new ShellExecutor(serverMetrics.getServer(), configManager) : null;
        startCleanupTask();
    }

    private void startCleanupTask() {
        cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10000);
                    long now = System.currentTimeMillis();
                    pendingCommands.entrySet().removeIf(entry ->
                            now - entry.getValue().timestamp > CONFIRMATION_TIMEOUT_SECONDS * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "MCPServer-Confirmation-Cleanup");
        // 设为守护线程，即使未被显式关闭也不会阻止 JVM 退出（避免关服卡死）
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /** 关服时中断清理线程 */
    public void shutdown() {
        if (cleanupThread != null) {
            cleanupThread.interrupt();
            cleanupThread = null;
        }
    }

    private String generateConfirmationToken(String command) {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private boolean isDangerousCommand(String command) {
        String cmdLower = command.trim().toLowerCase();
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (cmdLower.startsWith(dangerous + " ") || cmdLower.equals(dangerous)) {
                return true;
            }
        }
        return false;
    }

    private record PendingCommand(String command, long timestamp) {}

    private CommandResult executeCommand(net.minecraft.server.MinecraftServer server, String command) {
        java.util.List<String> outputLines = new java.util.ArrayList<>();
        net.minecraft.server.command.ServerCommandSource source = MCCompat.withLevel(server.getCommandSource(), 4)
                .withOutput(new net.minecraft.server.command.CommandOutput() {
                    @Override
                    public void sendMessage(net.minecraft.text.Text message) {
                        outputLines.add(message.getString());
                    }
                    @Override
                    public boolean shouldReceiveFeedback() { return true; }
                    @Override
                    public boolean shouldTrackOutput() { return true; }
                    @Override
                    public boolean shouldBroadcastConsoleToOps() { return false; }
                });
        int code = org.du.mcpserver.util.MCCompat.executeCommand(server, source, command);
        return new CommandResult(code, outputLines);
    }

    /** 命令执行结果：状态码（>0 成功）+ 输出行列表 */
    private static class CommandResult {
        final int statusCode;
        final java.util.List<String> output;
        CommandResult(int statusCode, java.util.List<String> output) {
            this.statusCode = statusCode;
            this.output = output;
        }
    }

    private JsonObject getRemoteFakePlayerAPIResult(String methodName) {
        try {
            Class<?> apiClass = Class.forName("org.du.remotefakeplayer.api.RemoteFakePlayerAPI");
            java.lang.reflect.Method method = apiClass.getMethod(methodName);
            Object result = method.invoke(null);

            JsonObject jsonResult = new JsonObject();
            if (result instanceof Integer) {
                jsonResult.addProperty("value", (Integer) result);
            } else if (result instanceof List<?> list) {
                JsonArray array = new JsonArray();
                for (Object item : list) {
                    array.add(item.toString());
                }
                jsonResult.add("value", array);
            } else if (result instanceof Map<?, ?> map) {
                JsonObject mapJson = new JsonObject();
                for (Object key : map.keySet()) {
                    Object value = map.get(key);
                    if (value instanceof Number) {
                        mapJson.addProperty(key.toString(), ((Number) value).intValue());
                    } else {
                        mapJson.addProperty(key.toString(), value.toString());
                    }
                }
                jsonResult.add("value", mapJson);
            } else {
                jsonResult.addProperty("value", result != null ? result.toString() : "null");
            }
            jsonResult.addProperty("success", true);
            return jsonResult;
        } catch (ClassNotFoundException e) {
            return createAPIError("RemoteFakePlayer API 类未找到，请确保已安装远程假人模组");
        } catch (NoSuchMethodException e) {
            return createAPIError("RemoteFakePlayer API 方法不存在: " + methodName);
        } catch (Exception e) {
            LOGGER.error("Error calling RemoteFakePlayer API: {}", e.getMessage());
            return createAPIError("调用 RemoteFakePlayer API 失败: " + e.getMessage());
        }
    }

    private JsonObject getRemoteFakePlayerAPIResultWithServer(String methodName) {
        try {
            Class<?> apiClass = Class.forName("org.du.remotefakeplayer.api.RemoteFakePlayerAPI");
            java.lang.reflect.Method method = apiClass.getMethod(methodName, net.minecraft.server.MinecraftServer.class);
            net.minecraft.server.MinecraftServer mcServer = serverMetrics.getServer();
            
            if (mcServer == null || !mcServer.isRunning()) {
                return createAPIError("服务器未运行");
            }

            Object result = method.invoke(null, mcServer);

            JsonObject jsonResult = new JsonObject();
            if (result instanceof Integer) {
                jsonResult.addProperty("value", (Integer) result);
            } else if (result instanceof Map<?, ?> map) {
                JsonObject mapJson = new JsonObject();
                for (Object key : map.keySet()) {
                    Object value = map.get(key);
                    if (value instanceof Number) {
                        mapJson.addProperty(key.toString(), ((Number) value).intValue());
                    } else {
                        mapJson.addProperty(key.toString(), value.toString());
                    }
                }
                jsonResult.add("value", mapJson);
            } else {
                jsonResult.addProperty("value", result != null ? result.toString() : "null");
            }
            jsonResult.addProperty("success", true);
            return jsonResult;
        } catch (ClassNotFoundException e) {
            return createAPIError("RemoteFakePlayer API 类未找到，请确保已安装远程假人模组");
        } catch (NoSuchMethodException e) {
            return createAPIError("RemoteFakePlayer API 方法不存在: " + methodName);
        } catch (Exception e) {
            LOGGER.error("Error calling RemoteFakePlayer API with server: {}", e.getMessage());
            return createAPIError("调用 RemoteFakePlayer API 失败: " + e.getMessage());
        }
    }

    private JsonObject createAPIError(String message) {
        JsonObject result = new JsonObject();
        result.addProperty("success", false);
        result.addProperty("error", message);
        return result;
    }

    private org.du.mcpserver.monitor.behavior.PlayerBehaviorRecorder getBehaviorRecorder() {
        try {
            org.du.mcpserver.Mcpserver inst = org.du.mcpserver.Mcpserver.getInstance();
            return inst != null ? inst.getBehaviorRecorder() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 懒初始化 ShellExecutor（构造函数执行时 server 可能还没准备好） */
    private ShellExecutor ensureShellExecutor() {
        if (shellExecutor == null && serverMetrics != null && serverMetrics.getServer() != null && configManager != null) {
            synchronized (this) {
                if (shellExecutor == null) {
                    shellExecutor = new ShellExecutor(serverMetrics.getServer(), configManager);
                }
            }
        }
        return shellExecutor;
    }

    public void updateApiKey(String newApiKey) {
        this.apiKey = newApiKey;
    }

    public JsonObject handleRequest(String requestBody) {
        try {
            MCPProtocolValidator.ValidationResult validation = MCPProtocolValidator.validateRequest(requestBody);
            if (!validation.valid) {
                String errorMsg = String.join("; ", validation.errors);
                LOGGER.warn("MCP request validation failed: {}", errorMsg);
                return JsonUtils.createErrorResponse(-32602, "Invalid params: " + errorMsg, 1);
            }

            JsonObject request = JsonParser.parseString(requestBody).getAsJsonObject();

            String method = request.get("method").getAsString();
            JsonObject params = request.has("params") ? request.get("params").getAsJsonObject() : null;
            boolean isNotification = !request.has("id");
            long requestId = isNotification ? 0 : request.get("id").getAsLong();

            if (isNotification) {
                handleNotification(method, params);
                return null;
            }

            return handleMethod(method, params, requestId);
        } catch (Exception e) {
            LOGGER.error("Failed to handle MCP request: {}", e.getMessage());
            return JsonUtils.createErrorResponse(-32700, "Parse error", 1);
        }
    }

    private JsonObject handleMethod(String method, JsonObject params, long requestId) {
        switch (method) {
            case "initialize":
                return handleInitialize(params, requestId);
            case "tools/list":
                return handleToolsList(params, requestId);
            case "tools/call":
                return handleToolsCall(params, requestId);
            default:
                return JsonUtils.createErrorResponse(-32601, "Method not found", requestId);
        }
    }

    private void handleNotification(String method, JsonObject params) {
        switch (method) {
            case "notifications/initialized":
                LOGGER.info("MCP client initialization completed");
                break;
            case "notifications/cancelled":
                LOGGER.info("MCP request cancelled");
                break;
            case "notifications/progress":
                break;
            default:
                LOGGER.debug("Received unknown notification: {}", method);
                break;
        }
    }

    private JsonObject handleInitialize(JsonObject params, long requestId) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");

        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanges", false);
        capabilities.add("tools", tools);

        JsonObject resources = new JsonObject();
        resources.addProperty("subscribe", false);
        resources.addProperty("listChanges", false);
        capabilities.add("resources", resources);

        JsonObject prompts = new JsonObject();
        prompts.addProperty("listChanges", false);
        capabilities.add("prompts", prompts);

        result.add("capabilities", capabilities);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "MCPServer");
        serverInfo.addProperty("version", "1.0.0");
        result.add("serverInfo", serverInfo);

        return JsonUtils.createSuccessResponse(result, requestId);
    }

    private JsonObject handleToolsList(JsonObject params, long requestId) {
        JsonArray tools = new JsonArray();

        tools.add(createTool(
                "get_server_status",
                "获取服务器整体状态信息",
                Map.of(),
                "返回服务器运行状态、版本、在线玩家、TPS、内存使用、实体数量等综合信息"
        ));

        tools.add(createTool(
                "get_tps_metrics",
                "获取TPS指标",
                Map.of(),
                "返回当前TPS、平均TPS、最小/最大TPS及历史数据"
        ));

        tools.add(createTool(
                "get_memory_metrics",
                "获取内存使用指标",
                Map.of(),
                "返回堆内存、非堆内存使用情况及系统信息"
        ));

        tools.add(createTool(
                "get_entity_metrics",
                "获取实体统计信息",
                Map.of(),
                "返回服务器中各类实体的数量统计"
        ));

        tools.add(createTool(
                "get_recent_logs",
                "获取最近日志",
                Map.of(
                        "limit", "integer (optional) - 返回日志条数，默认50",
                        "filterType", "string (optional) - 日志类型过滤: GENERAL, PLAYER_JOIN, PLAYER_LEAVE, CHAT, COMMAND, ERROR, WARN"
                ),
                "返回服务器最近的日志记录，支持按类型过滤"
        ));

        tools.add(createTool(
                "get_player_events",
                "获取玩家进出事件",
                Map.of(
                        "limit", "integer (optional) - 返回事件条数，默认20"
                ),
                "返回最近的玩家加入和离开事件"
        ));

        tools.add(createTool(
                "get_chat_messages",
                "获取聊天消息",
                Map.of(
                        "limit", "integer (optional) - 返回消息条数，默认20"
                ),
                "返回最近的玩家聊天消息"
        ));

        tools.add(createTool(
                "get_command_history",
                "获取命令执行历史",
                Map.of(
                        "limit", "integer (optional) - 返回命令条数，默认20"
                ),
                "返回最近玩家执行的服务器命令记录"
        ));

        tools.add(createTool(
                "get_player_info",
                "获取玩家综合信息",
                Map.of(
                        "playerName", "string (optional) - 玩家名称，不提供则返回所有在线玩家"
                ),
                "返回玩家详细信息，包括位置、群系、世界、背包物品、装备、生命值、饥饿值、经验等级等"
        ));

        tools.add(createTool(
                "spark_check_availability",
                "检查Spark模组可用性",
                Map.of(),
                "检查服务器是否安装了Spark性能分析模组"
        ));

        // Spark 未安装时，隐藏所有 Spark 数据/动作类工具，仅保留 spark_check_availability 供 AI 探测
        if (sparkIntegration.isSparkAvailable()) {
        tools.add(createTool(
                "spark_start_profiler",
                "启动Spark性能分析器",
                Map.of(
                        "profilerType", "string (optional) - 分析类型: cpu(默认), alloc/memory(内存分配), sampler(Java采样器)",
                        "duration", "integer (optional) - 建议分析持续时间(秒)，AI应据此判断何时调用stop"
                ),
                "启动Spark性能分析器。AI应在合适时机调用spark_stop_profiler停止分析。"
        ));

        tools.add(createTool(
                "spark_stop_profiler",
                "停止分析器并保存结果到文件",
                Map.of(
                        "profilerId", "string (required) - 分析器ID"
                ),
                "停止分析器，执行--save-to-file保存结果。停止后调用spark_get_profiler_result获取数据。"
        ));

        tools.add(createTool(
                "spark_get_profiler_status",
                "获取分析器状态和文件就绪状态",
                Map.of(
                        "profilerId", "string (required) - 分析器ID"
                ),
                "检查profiler是否仍在运行、结果文件是否已就绪"
        ));

        tools.add(createTool(
                "spark_create_heap_dump",
                "创建堆转储",
                Map.of(),
                "使用Spark创建堆转储文件，可能导致服务器短暂卡顿"
        ));

        tools.add(createTool(
                "spark_get_health_report",
                "获取Spark健康报告",
                Map.of(),
                "使用Spark生成服务器健康报告"
        ));

        tools.add(createTool(
                "spark_get_tps_report",
                "获取Spark TPS报告",
                Map.of(),
                "使用Spark获取详细的TPS报告"
        ));

        tools.add(createTool(
                "spark_get_profiler_result",
                "获取Profiler结果(Base64二进制数据)",
                Map.of(
                        "profilerId", "string (required) - 分析器ID"
                ),
                "返回profiler结果文件的Base64编码二进制数据。AI解码后可上传到spark.lucko.me查看火焰图或自行解析。文件未就绪时返回错误，需等待后重试。"
        ));
        }

        tools.add(createTool(
                "execute_command",
                "执行服务器命令",
                Map.of(
                        "command", "string (required) - 要执行的命令，不含前导斜杠",
                        "confirmationToken", "string (optional) - 高危命令确认令牌，首次调用高危命令时返回"
                ),
                "在服务器上执行指定命令，需要相应权限。高危命令(如stop/op/restart等)需二次确认：首次调用返回needs_confirmation状态和令牌，携带令牌30秒内再次调用即可执行。"
        ));

        tools.add(createTool(
                "get_fake_player_count",
                "获取远程假人数量",
                Map.of(),
                "获取已绑定的假人数量"
        ));

        tools.add(createTool(
                "get_bound_container_count",
                "获取绑定容器数量",
                Map.of(),
                "获取已绑定的容器数量"
        ));

        tools.add(createTool(
                "get_total_item_count",
                "获取仓库物品总数",
                Map.of(),
                "获取仓库中所有物品的总数"
        ));

        tools.add(createTool(
                "get_item_type_count",
                "获取物品种类数量",
                Map.of(),
                "获取仓库中物品种类的数量"
        ));

        tools.add(createTool(
                "get_item_stats",
                "获取物品详细统计",
                Map.of(),
                "获取物品详细统计（物品 ID → 数量）"
        ));

        tools.add(createTool(
                "get_fake_players",
                "获取假人列表",
                Map.of(),
                "获取已绑定的假人列表"
        ));

        tools.add(createTool(
                "get_bound_containers",
                "获取绑定容器列表",
                Map.of(),
                "获取已绑定的容器列表"
        ));

        tools.add(createTool(
                "behavior_recorder_status",
                "玩家行为记录器状态",
                Map.of(),
                "获取记录器运行状态、配置参数、已记录条数及被监测玩家列表"
        ));

        tools.add(createTool(
                "behavior_track_player",
                "添加玩家到监测列表",
                Map.of(
                        "playerName", "string (required) - 玩家名称，或使用 '*' 监测所有在线玩家"
                ),
                "将指定玩家加入行为记录监测列表；传入 playerName='*' 表示开启全体在线玩家监测模式"
        ));

        tools.add(createTool(
                "behavior_untrack_player",
                "从监测列表移除玩家",
                Map.of(
                        "playerName", "string (required) - 玩家名称，或使用 '!all' 清空所有监测规则"
                ),
                "停止监测指定玩家；传入 '!all' 清空名单并关闭全体监测"
        ));

        tools.add(createTool(
                "behavior_clear_history",
                "清空行为记录历史",
                Map.of(
                        "playerName", "string (optional) - 指定玩家名，清空该玩家历史；不提供则清空全部历史"
                ),
                "清空已记录的行为数据，可按玩家名删除或全部清空"
        ));

        tools.add(createTool(
                "behavior_get_latest",
                "获取玩家最新行为记录",
                Map.of(
                        "playerName", "string (required) - 玩家名称"
                ),
                "返回该玩家最近一次采样的完整行为快照（状态/位置/移动/交互/背包）"
        ));

        tools.add(createTool(
                "behavior_query_history",
                "查询玩家行为历史",
                Map.of(
                        "playerName", "string (required) - 玩家名称",
                        "fromTs", "long (optional) - 起始时间戳（毫秒），不指定则不限",
                        "toTs", "long (optional) - 结束时间戳（毫秒），不指定则不限",
                        "limit", "int (optional) - 最多返回条数，默认100",
                        "format", "string (optional) - 输出格式: json(默认) / csv"
                ),
                "按时间范围查询玩家行为历史，返回 JSON 或 CSV 格式的标准化数据"
        ));

        if (configManager != null && configManager.isFileEditorEnabled()) {
            tools.add(createTool(
                    "file_read",
                    "读取服务器文件",
                    Map.of(
                            "filePath", "string (required) - 文件路径，相对于服务器目录"
                    ),
                    "读取服务器目录下的文件内容，如server.properties"
            ));

            tools.add(createTool(
                    "file_write",
                    "写入服务器文件",
                    Map.of(
                            "filePath", "string (required) - 文件路径，相对于服务器目录",
                            "content", "string (required) - 文件内容"
                    ),
                    "写入文件内容，会覆盖原有内容。仅限服务器目录下的文件。"
            ));

            tools.add(createTool(
                    "file_append",
                    "追加内容到服务器文件",
                    Map.of(
                            "filePath", "string (required) - 文件路径，相对于服务器目录",
                            "content", "string (required) - 要追加的内容"
                    ),
                    "在文件末尾追加内容"
            ));

            tools.add(createTool(
                    "file_delete",
                    "删除服务器文件",
                    Map.of(
                            "filePath", "string (required) - 文件路径，相对于服务器目录"
                    ),
                    "删除服务器目录下的文件"
            ));

            tools.add(createTool(
                    "file_list",
                    "列出服务器目录文件",
                    Map.of(
                            "directory", "string (optional) - 目录路径，默认为服务器根目录"
                    ),
                    "列出服务器目录下的文件和子目录"
            ));
        }

        if (configManager != null && configManager.isShellEnabled()) {
            tools.add(createTool(
                    "execute_shell",
                    "执行系统级命令 (cmd / bash)",
                    Map.of(
                            "command", "string (required) - 要执行的命令，例如 'dir /b' 或 'ls -la'",
                            "workingDir", "string (optional) - 工作目录，默认为服务器根目录",
                            "timeoutMs", "number (optional) - 超时毫秒数，默认 30000"
                    ),
                    "在服务器所在主机上执行系统命令（Windows 自动使用 cmd.exe /c，Linux/macOS 自动使用 bash -c 或 sh -c）。" +
                    "返回 exitCode、stdout、stderr、elapsedMs。单次执行不得超过超时时间。"
            ));
        }

        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return JsonUtils.createSuccessResponse(result, requestId);
    }

    private JsonObject createTool(String name, String description, Map<String, String> parameters, String returns) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();

        parameters.forEach((key, desc) -> {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", "string");
            prop.addProperty("description", desc);
            properties.add(key, prop);

            if (desc.contains("(required)")) {
                required.add(key);
            }
        });

        inputSchema.add("properties", properties);
        if (required.size() > 0) {
            inputSchema.add("required", required);
        }
        tool.add("inputSchema", inputSchema);

        return tool;
    }

    private JsonObject handleToolsCall(JsonObject params, long requestId) {
        if (params == null) {
            return JsonUtils.createErrorResponse(-32602, "Missing params", requestId);
        }

        if (!params.has("name")) {
            return JsonUtils.createErrorResponse(-32602, "Missing tool name", requestId);
        }

        String toolName = params.get("name").getAsString();
        JsonObject toolParams = params.has("arguments") ? params.get("arguments").getAsJsonObject() : null;

        return executeTool(toolName, toolParams, requestId);
    }

    private JsonObject executeTool(String toolName, JsonObject params, long requestId) {
        try {
            JsonObject toolResult = switch (toolName) {
                case "get_server_status" -> serverMetrics.getServerStatus();
                case "get_tps_metrics" -> serverMetrics.getTPSMetrics();
                case "get_memory_metrics" -> serverMetrics.getMemoryMetrics();
                case "get_entity_metrics" -> serverMetrics.getEntityMetrics();
                case "get_recent_logs" -> {
                    int limit = params != null && params.has("limit") ? params.get("limit").getAsInt() : 50;
                    String filterType = params != null && params.has("filterType") ? params.get("filterType").getAsString() : null;
                    yield logMonitor.getRecentLogs(limit, filterType);
                }
                case "get_player_events" -> {
                    int limit = params != null && params.has("limit") ? params.get("limit").getAsInt() : 20;
                    yield logMonitor.getPlayerEvents(limit);
                }
                case "get_chat_messages" -> {
                    int limit = params != null && params.has("limit") ? params.get("limit").getAsInt() : 20;
                    yield logMonitor.getChatMessages(limit);
                }
                case "get_command_history" -> {
                    int limit = params != null && params.has("limit") ? params.get("limit").getAsInt() : 20;
                    yield logMonitor.getCommandHistory(limit);
                }
                case "get_player_info" -> {
                    String playerName = params != null && params.has("playerName") ? params.get("playerName").getAsString() : null;
                    if (playerName != null && !playerName.isEmpty()) {
                        yield playerInfoManager.getPlayerInfo(playerName);
                    } else {
                        yield playerInfoManager.getAllPlayersInfo();
                    }
                }
                case "spark_check_availability" -> sparkIntegration.checkAvailability();
                case "spark_start_profiler" -> {
                    String type = params != null && params.has("profilerType") ? params.get("profilerType").getAsString() : "default";
                    Integer duration = params != null && params.has("duration") ? params.get("duration").getAsInt() : null;
                    yield sparkIntegration.startProfiler(type, duration);
                }
                case "spark_stop_profiler" -> {
                    String profilerId = params != null && params.has("profilerId") ? params.get("profilerId").getAsString() : null;
                    if (profilerId == null) {
                        yield null;
                    } else {
                        yield sparkIntegration.stopProfiler(profilerId);
                    }
                }
                case "spark_get_profiler_status" -> {
                    String profilerId = params != null && params.has("profilerId") ? params.get("profilerId").getAsString() : null;
                    if (profilerId == null) {
                        yield null;
                    } else {
                        yield sparkIntegration.getProfilerStatus(profilerId);
                    }
                }
                case "spark_create_heap_dump" -> sparkIntegration.createHeapDump();
                case "spark_get_health_report" -> sparkIntegration.getHealthReport();
                case "spark_get_tps_report" -> sparkIntegration.getTPSMetrics();
                case "spark_get_profiler_result" -> {
                    String profilerId = params != null && params.has("profilerId") ? params.get("profilerId").getAsString() : null;
                    if (profilerId == null) {
                        yield null;
                    } else {
                        yield sparkIntegration.getProfilerResult(profilerId);
                    }
                }
                case "execute_command" -> {
                    String command = params != null && params.has("command") ? params.get("command").getAsString() : null;
                    String confirmationToken = params != null && params.has("confirmationToken") ? params.get("confirmationToken").getAsString() : null;
                    if (command == null) {
                        yield null;
                    }

                    if (isDangerousCommand(command)) {
                        if (confirmationToken == null || confirmationToken.isEmpty()) {
                            String token = generateConfirmationToken(command);
                            pendingCommands.put(token, new PendingCommand(command, System.currentTimeMillis()));
                            JsonObject confirmResult = new JsonObject();
                            confirmResult.addProperty("command", command);
                            confirmResult.addProperty("status", "needs_confirmation");
                            confirmResult.addProperty("confirmationToken", token);
                            confirmResult.addProperty("message", "此命令为高危操作，请询问用户是否确认该操作，限30秒内携带confirmationToken再次调用以确认执行");
                            yield confirmResult;
                        } else {
                            PendingCommand pending = pendingCommands.remove(confirmationToken);
                            if (pending == null) {
                                JsonObject errResult = new JsonObject();
                                errResult.addProperty("command", command);
                                errResult.addProperty("status", "error");
                                errResult.addProperty("error", "确认令牌无效或已过期，请重新发起命令");
                                yield errResult;
                            }
                            if (!pending.command.equals(command)) {
                                JsonObject errResult = new JsonObject();
                                errResult.addProperty("command", command);
                                errResult.addProperty("status", "error");
                                errResult.addProperty("error", "确认令牌与命令不匹配");
                                yield errResult;
                            }
                        }
                    }

                    net.minecraft.server.MinecraftServer mcServer = serverMetrics.getServer();
                    if (mcServer == null || !mcServer.isRunning()) {
                        JsonObject errResult = new JsonObject();
                        errResult.addProperty("command", command);
                        errResult.addProperty("status", "error");
                        errResult.addProperty("error", "服务器未运行");
                        yield errResult;
                    }
                    try {
                        CommandResult cmdResult = executeCommand(mcServer, command);
                        JsonObject result = new JsonObject();
                        result.addProperty("command", command);
                        result.addProperty("status", cmdResult.statusCode > 0 ? "executed" : "failed");
                        result.addProperty("success", cmdResult.statusCode > 0);
                        JsonArray outputArr = new JsonArray();
                        for (String line : cmdResult.output) {
                            outputArr.add(line);
                        }
                        if (outputArr.size() > 0) {
                            result.add("output", outputArr);
                        }
                        yield result;
                    } catch (Exception e) {
                        LOGGER.error("Failed to execute command '{}': {}", command, e.getMessage());
                        JsonObject errResult = new JsonObject();
                        errResult.addProperty("command", command);
                        errResult.addProperty("status", "error");
                        errResult.addProperty("error", e.getMessage());
                        yield errResult;
                    }
                }
                case "get_fake_player_count" -> getRemoteFakePlayerAPIResult("getFakePlayerCount");
                case "get_bound_container_count" -> getRemoteFakePlayerAPIResult("getBoundContainerCount");
                case "get_total_item_count" -> getRemoteFakePlayerAPIResultWithServer("getTotalItemCount");
                case "get_item_type_count" -> getRemoteFakePlayerAPIResultWithServer("getItemTypeCount");
                case "get_item_stats" -> getRemoteFakePlayerAPIResultWithServer("getItemStats");
                case "get_fake_players" -> getRemoteFakePlayerAPIResult("getFakePlayers");
                case "get_bound_containers" -> getRemoteFakePlayerAPIResult("getBoundContainers");
                case "file_read" -> {
                    String filePath = params != null && params.has("filePath") ? params.get("filePath").getAsString() : null;
                    if (filePath == null) {
                        yield null;
                    }
                    yield fileEditor.readFile(filePath);
                }
                case "file_write" -> {
                    String filePath = params != null && params.has("filePath") ? params.get("filePath").getAsString() : null;
                    String content = params != null && params.has("content") ? params.get("content").getAsString() : null;
                    if (filePath == null || content == null) {
                        yield null;
                    }
                    yield fileEditor.writeFile(filePath, content);
                }
                case "file_append" -> {
                    String filePath = params != null && params.has("filePath") ? params.get("filePath").getAsString() : null;
                    String content = params != null && params.has("content") ? params.get("content").getAsString() : null;
                    if (filePath == null || content == null) {
                        yield null;
                    }
                    yield fileEditor.appendToFile(filePath, content);
                }
                case "file_delete" -> {
                    String filePath = params != null && params.has("filePath") ? params.get("filePath").getAsString() : null;
                    if (filePath == null) {
                        yield null;
                    }
                    yield fileEditor.deleteFile(filePath);
                }
                case "file_list" -> {
                    String directory = params != null && params.has("directory") ? params.get("directory").getAsString() : ".";
                    yield fileEditor.listFiles(directory);
                }
                case "execute_shell" -> {
                    String shellCmd = params != null && params.has("command") ? params.get("command").getAsString() : null;
                    String shellCwd = params != null && params.has("workingDir") && !params.get("workingDir").isJsonNull()
                            ? params.get("workingDir").getAsString() : null;
                    Integer shellTimeout = params != null && params.has("timeoutMs") && !params.get("timeoutMs").isJsonNull()
                            ? params.get("timeoutMs").getAsInt() : null;
                    if (shellCmd == null || shellCmd.isBlank()) {
                        JsonObject err = new JsonObject();
                        err.addProperty("status", "error");
                        err.addProperty("error", "缺少 command 参数");
                        yield err;
                    }
                    if (configManager == null || !configManager.isShellEnabled()) {
                        JsonObject err = new JsonObject();
                        err.addProperty("status", "error");
                        err.addProperty("error", "Shell executor is disabled. 在 config.json 中设置 shellEnabled: true 以启用，或使用 /mcpserver shell enable 命令");
                        yield err;
                    }
                    ShellExecutor se = ensureShellExecutor();
                    if (se == null) {
                        JsonObject err = new JsonObject();
                        err.addProperty("status", "error");
                        err.addProperty("error", "Shell executor 未初始化");
                        yield err;
                    }
                    yield se.execute(shellCmd, shellCwd, shellTimeout);
                }
                case "behavior_recorder_status" -> {
                    org.du.mcpserver.monitor.behavior.PlayerBehaviorRecorder br = getBehaviorRecorder();
                    JsonObject result = new JsonObject();
                    if (br == null) {
                        result.addProperty("success", false);
                        result.addProperty("error", "Behavior Recorder 未初始化");
                    } else {
                        result.add("data", br.getStatus());
                        result.addProperty("success", true);
                    }
                    yield result;
                }
                case "behavior_track_player" -> {
                    String playerName = params != null && params.has("playerName") ? params.get("playerName").getAsString() : null;
                    JsonObject result = new JsonObject();
                    org.du.mcpserver.monitor.behavior.PlayerBehaviorRecorder br = getBehaviorRecorder();
                    if (br == null) {
                        result.addProperty("success", false);
                        result.addProperty("error", "Behavior Recorder 未初始化");
                    } else if (playerName == null || playerName.isEmpty()) {
                        result.addProperty("success", false);
                        result.addProperty("error", "缺少 playerName 参数");
                    } else if ("*".equals(playerName)) {
                        br.trackAllPlayers(true);
                        result.addProperty("success", true);
                        result.addProperty("message", "已开启全体在线玩家监测模式");
                    } else {
                        boolean ok = br.addTrackingPlayer(playerName);
                        result.addProperty("success", ok);
                        result.addProperty("message", ok ? "已添加监测：" + playerName : "添加失败");
                    }
                    yield result;
                }
                case "behavior_untrack_player" -> {
                    String playerName = params != null && params.has("playerName") ? params.get("playerName").getAsString() : null;
                    JsonObject result = new JsonObject();
                    org.du.mcpserver.monitor.behavior.PlayerBehaviorRecorder br = getBehaviorRecorder();
                    if (br == null) {
                        result.addProperty("success", false);
                        result.addProperty("error", "Behavior Recorder 未初始化");
                    } else if (playerName == null || playerName.isEmpty()) {
                        result.addProperty("success", false);
                        result.addProperty("error", "缺少 playerName 参数");
                    } else if ("!all".equals(playerName)) {
                        br.clearAllTracking();
                        result.addProperty("success", true);
                        result.addProperty("message", "已清空所有监测规则");
                    } else {
                        br.removeTrackingPlayer(playerName);
                        result.addProperty("success", true);
                        result.addProperty("message", "已停止监测：" + playerName);
                    }
                    yield result;
                }
                case "behavior_clear_history" -> {
                    String playerName = params != null && params.has("playerName") ? params.get("playerName").getAsString() : null;
                    JsonObject result = new JsonObject();
                    org.du.mcpserver.monitor.behavior.PlayerBehaviorRecorder br = getBehaviorRecorder();
                    if (br == null) {
                        result.addProperty("success", false);
                        result.addProperty("error", "Behavior Recorder 未初始化");
                    } else {
                        br.clearHistory(playerName);
                        result.addProperty("success", true);
                        result.addProperty("message", playerName == null ? "已清空全部历史" : "已清空历史：" + playerName);
                    }
                    yield result;
                }
                case "behavior_get_latest" -> {
                    String playerName = params != null && params.has("playerName") ? params.get("playerName").getAsString() : null;
                    JsonObject result = new JsonObject();
                    if (playerName == null || playerName.isEmpty()) {
                        result.addProperty("success", false);
                        result.addProperty("error", "缺少 playerName 参数");
                        yield result;
                    }
                    org.du.mcpserver.monitor.behavior.PlayerBehaviorRecorder br = getBehaviorRecorder();
                    if (br == null) {
                        result.addProperty("success", false);
                        result.addProperty("error", "Behavior Recorder 未初始化");
                    } else {
                        org.du.mcpserver.monitor.behavior.PlayerBehaviorRecord latest = br.getLatestRecord(playerName);
                        if (latest == null) {
                            result.addProperty("success", false);
                            result.addProperty("error", "该玩家暂无记录");
                        } else {
                            result.addProperty("success", true);
                            result.add("record", org.du.mcpserver.monitor.behavior.PlayerBehaviorRecorder.recordToJson(latest));
                        }
                    }
                    yield result;
                }
                case "behavior_query_history" -> {
                    String playerName = params != null && params.has("playerName") ? params.get("playerName").getAsString() : null;
                    JsonObject result = new JsonObject();
                    if (playerName == null || playerName.isEmpty()) {
                        result.addProperty("success", false);
                        result.addProperty("error", "缺少 playerName 参数");
                        yield result;
                    }
                    Long fromTs = params != null && params.has("fromTs") ? params.get("fromTs").getAsLong() : null;
                    Long toTs = params != null && params.has("toTs") ? params.get("toTs").getAsLong() : null;
                    Integer limit = params != null && params.has("limit") ? params.get("limit").getAsInt() : 100;
                    String format = params != null && params.has("format") ? params.get("format").getAsString() : "json";
                    org.du.mcpserver.monitor.behavior.PlayerBehaviorRecorder br = getBehaviorRecorder();
                    if (br == null) {
                        result.addProperty("success", false);
                        result.addProperty("error", "Behavior Recorder 未初始化");
                    } else {
                        java.util.List<org.du.mcpserver.monitor.behavior.PlayerBehaviorRecord> records =
                                br.queryHistory(playerName, fromTs, toTs, limit);
                        result.addProperty("success", true);
                        if ("csv".equalsIgnoreCase(format)) {
                            result.addProperty("format", "csv");
                            result.addProperty("data", br.exportRecordsToCsv(records, true));
                        } else {
                            result.addProperty("format", "json");
                            result.add("data", br.exportRecordsToJson(records));
                        }
                    }
                    yield result;
                }
                default -> {
                    yield null;
                }
            };

            if (toolResult == null) {
                if ("spark_stop_profiler".equals(toolName) || "spark_get_profiler_status".equals(toolName) || "spark_get_profiler_result".equals(toolName)) {
                    return JsonUtils.createErrorResponse(-32602, "Missing profilerId", requestId);
                }
                if ("execute_command".equals(toolName)) {
                    return JsonUtils.createErrorResponse(-32602, "Missing command", requestId);
                }
                if ("file_read".equals(toolName) || "file_delete".equals(toolName)) {
                    return JsonUtils.createErrorResponse(-32602, "Missing filePath", requestId);
                }
                if ("file_write".equals(toolName) || "file_append".equals(toolName)) {
                    return JsonUtils.createErrorResponse(-32602, "Missing filePath or content", requestId);
                }
                if ("behavior_track_player".equals(toolName) || "behavior_untrack_player".equals(toolName)
                        || "behavior_get_latest".equals(toolName) || "behavior_query_history".equals(toolName)) {
                    return JsonUtils.createErrorResponse(-32602, "Missing playerName", requestId);
                }
                return JsonUtils.createErrorResponse(-32601, "Tool not found: " + toolName, requestId);
            }

            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", JsonUtils.toJson(toolResult));
            content.add(textContent);

            JsonObject result = new JsonObject();
            result.add("content", content);
            return JsonUtils.createSuccessResponse(result, requestId);
        } catch (Exception e) {
            LOGGER.error("Error executing tool {}: {}", toolName, e.getMessage());
            return JsonUtils.createErrorResponse(-32603, "Internal error: " + e.getMessage(), requestId);
        }
    }
}