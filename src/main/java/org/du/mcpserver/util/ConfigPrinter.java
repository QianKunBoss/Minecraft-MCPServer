package org.du.mcpserver.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ConfigPrinter {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");

    public static void printClientConfig(int httpPort, String apiKey, boolean sslEnabled) {
        String protocol = sslEnabled ? "https" : "http";
        String baseUrl = protocol + "://localhost:" + httpPort;

        LOGGER.info("╔══════════════════════════════════════════════════════════════════╗");
        LOGGER.info("║           MCP Client 配置文件 (可直接复制使用)                    ║");
        LOGGER.info("╚══════════════════════════════════════════════════════════════════╝");
        LOGGER.info("");

        if (sslEnabled) {
            LOGGER.info("⚠ SSL已启用 - 使用自签名证书，客户端需信任证书或忽略证书验证");
            LOGGER.info("  证书位置: config/mcpserver-keystore.jks");
            LOGGER.info("");
        }

        LOGGER.info("==================== Claude Desktop / Cursor 配置 ====================");
        LOGGER.info("文件位置: %%APPDATA%%\\Claude\\claude_desktop_config.json 或 ~/.cursor/mcp.json");
        LOGGER.info("");
        LOGGER.info(buildClaudeDesktopConfig(baseUrl, apiKey, sslEnabled));
        LOGGER.info("");
        LOGGER.info("==================== 通用 MCP Client 配置 (Streamable HTTP) ====================");
        LOGGER.info(buildGenericStreamableHttpConfig(baseUrl, apiKey, sslEnabled));
        LOGGER.info("");
        LOGGER.info("==================== 配置参数说明 ====================");
        LOGGER.info("  url        - MCP服务器端点地址");
        LOGGER.info("  transport  - 传输协议: \"http\" (Streamable HTTP)");
        LOGGER.info("  headers    - HTTP请求头，包含API密钥认证信息");
        LOGGER.info("  X-API-Key  - 认证密钥，用于API访问授权");
        if (sslEnabled) {
            LOGGER.info("  SSL        - 已启用，使用HTTPS协议和自签名证书");
        }
        LOGGER.info("");
        LOGGER.info("==================== 可用端点 ====================");
        LOGGER.info("  MCP协议端点:    {}/mcp (POST, Streamable HTTP)", baseUrl);
        LOGGER.info("  REST API端点:   {}/api/*", baseUrl);
        LOGGER.info("  健康检查端点:   {}/health (无需认证)", baseUrl);
        LOGGER.info("");
        LOGGER.info("==================== 可用工具列表 ====================");
        LOGGER.info("  get_server_status        - 获取服务器整体状态");
        LOGGER.info("  get_tps_metrics          - 获取TPS指标");
        LOGGER.info("  get_memory_metrics       - 获取内存使用指标");
        LOGGER.info("  get_entity_metrics       - 获取实体统计信息");
        LOGGER.info("  get_recent_logs          - 获取最近日志");
        LOGGER.info("  get_player_events        - 获取玩家进出事件");
        LOGGER.info("  get_chat_messages        - 获取聊天消息");
        LOGGER.info("  spark_check_availability - 检查Spark模组可用性");
        LOGGER.info("  spark_start_profiler     - 启动性能分析器");
        LOGGER.info("  spark_stop_profiler      - 停止性能分析器");
        LOGGER.info("  spark_get_profiler_status- 获取分析器状态");
        LOGGER.info("  spark_create_heap_dump   - 创建堆转储");
        LOGGER.info("  spark_get_health_report  - 获取健康报告");
        LOGGER.info("  spark_get_tps_report     - 获取TPS报告");
        LOGGER.info("  execute_command          - 执行服务器命令");
        LOGGER.info("╔══════════════════════════════════════════════════════════════════╗");
        LOGGER.info("║           配置输出完成 - 请选择适合您客户端的配置格式             ║");
        LOGGER.info("╚══════════════════════════════════════════════════════════════════╝");
    }

    private static String buildClaudeDesktopConfig(String baseUrl, String apiKey, boolean sslEnabled) {
        String config = "{\n" +
                "  \"mcpServers\": {\n" +
                "    \"minecraft-mcp\": {\n" +
                "      \"url\": \"" + baseUrl + "/mcp\",\n" +
                "      \"transport\": \"http\",\n" +
                "      \"headers\": {\n" +
                "        \"X-API-Key\": \"" + apiKey + "\"\n" +
                "      }";
        if (sslEnabled) {
            config += ",\n      \"ignoreSslErrors\": true";
        }
        config += "\n    }\n" +
                "  }\n" +
                "}";
        return config;
    }

    private static String buildGenericStreamableHttpConfig(String baseUrl, String apiKey, boolean sslEnabled) {
        String config = "{\n" +
                "  \"server\": {\n" +
                "    \"name\": \"minecraft-mcp\",\n" +
                "    \"url\": \"" + baseUrl + "/mcp\",\n" +
                "    \"transport\": \"http\",\n" +
                "    \"protocolVersion\": \"2024-11-05\",\n" +
                "    \"headers\": {\n" +
                "      \"Content-Type\": \"application/json\",\n" +
                "      \"X-API-Key\": \"" + apiKey + "\"\n" +
                "    }";
        if (sslEnabled) {
            config += ",\n    \"ssl\": {\n" +
                    "      \"enabled\": true,\n" +
                    "      \"ignoreCertErrors\": true\n" +
                    "    }";
        }
        config += "\n  }\n" +
                "}";
        return config;
    }
}