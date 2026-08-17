package org.du.mcpserver.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");
    private static final String CONFIG_DIR_NAME = "MCPServer";
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int DEFAULT_HTTP_PORT = 8081;
    private static final boolean DEFAULT_SSL_ENABLED = false;
    private static final String DEFAULT_KEYSTORE_PATH = "config/MCPServer/mcpserver-keystore.jks";
    private static final String DEFAULT_KEYSTORE_PASSWORD = "mcpserver";
    private static final String DEFAULT_KEYSTORE_TYPE = "JKS";
    private static final String DEFAULT_CERT_PATH = "config/MCPServer/fullchain.pem";
    private static final String DEFAULT_KEY_PATH = "config/MCPServer/privkey.key";
    private static final String DEFAULT_TOKEN_MODE = "auto";  // auto | persistent
    private static final boolean DEFAULT_SHELL_ENABLED = false;
    private static final int DEFAULT_SHELL_TIMEOUT_MS = 30_000;

    private final MinecraftServer server;
    private JsonObject config;
    private boolean fileEditorEnabled = false;
    private int httpPort = DEFAULT_HTTP_PORT;
    private boolean sslEnabled = DEFAULT_SSL_ENABLED;
    private String keystorePath = DEFAULT_KEYSTORE_PATH;
    private String keystorePassword = DEFAULT_KEYSTORE_PASSWORD;
    private String keystoreType = DEFAULT_KEYSTORE_TYPE;
    private String certPath = DEFAULT_CERT_PATH;
    private String keyPath = DEFAULT_KEY_PATH;
    private String tokenMode = DEFAULT_TOKEN_MODE;
    private String persistentToken = null;
    private boolean shellEnabled = DEFAULT_SHELL_ENABLED;
    private int shellTimeoutMs = DEFAULT_SHELL_TIMEOUT_MS;

    public ConfigManager(MinecraftServer server) {
        this.server = server;
        loadConfig();
    }

    /** 重新从 config.json 加载配置（热重载） */
    public void reload() {
        int oldPort = httpPort;
        boolean oldSsl = sslEnabled;
        loadConfig();
        LOGGER.info("Config reloaded successfully");
        if (httpPort != oldPort) {
            LOGGER.warn("httpPort changed from {} to {} - restart server to take effect", oldPort, httpPort);
        }
        if (sslEnabled != oldSsl) {
            LOGGER.warn("sslEnabled changed from {} to {} - restart server to take effect", oldSsl, sslEnabled);
        }
    }

    private void loadConfig() {
        Path configPath = getConfigPath();
        if (Files.exists(configPath)) {
            try {
                String content = new String(Files.readAllBytes(configPath), java.nio.charset.StandardCharsets.UTF_8);
                // 使用 lenient 解析：容忍 // 注释与尾逗号，兼容本模组自己写出的带注释配置，
                // 也不会因为用户手滑多写逗号就直接炸服。
                JsonReader reader = new JsonReader(new java.io.StringReader(content));
                reader.setLenient(true);
                config = GSON.fromJson(reader, JsonObject.class);
                if (config == null) {
                    config = createDefaultConfig();
                }
                LOGGER.info("Loaded MCPServer config from: {}", configPath);
            } catch (Exception e) {
                LOGGER.error("Failed to parse config file: {}", configPath, e);
                LOGGER.error("Falling back to default configuration. You may delete the file to regenerate a clean one.");
                config = createDefaultConfig();
            }
        } else {
            config = createDefaultConfig();
            saveConfigWithComments();
        }

        fileEditorEnabled = config.has("fileEditor") && config.get("fileEditor").getAsBoolean();
        LOGGER.info("File editor tool is {}", fileEditorEnabled ? "ENABLED" : "DISABLED");

        if (config.has("httpPort")) {
            try {
                httpPort = config.get("httpPort").getAsInt();
                if (httpPort < 1 || httpPort > 65535) {
                    LOGGER.warn("Invalid port {} in config, using default port {}", httpPort, DEFAULT_HTTP_PORT);
                    httpPort = DEFAULT_HTTP_PORT;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to parse httpPort from config, using default port {}", DEFAULT_HTTP_PORT);
                httpPort = DEFAULT_HTTP_PORT;
            }
        }
        LOGGER.info("HTTP Server port: {}", httpPort);

        if (config.has("ssl") && config.get("ssl").isJsonObject()) {
            JsonObject sslConfig = config.getAsJsonObject("ssl");
            sslEnabled = sslConfig.has("enabled") && sslConfig.get("enabled").getAsBoolean();
            
            if (sslConfig.has("keystorePath")) {
                keystorePath = sslConfig.get("keystorePath").getAsString();
                LOGGER.info("Custom keystore path: {}", keystorePath);
            }
            
            if (sslConfig.has("keystorePassword")) {
                keystorePassword = sslConfig.get("keystorePassword").getAsString();
            }
            
            if (sslConfig.has("keystoreType")) {
                keystoreType = sslConfig.get("keystoreType").getAsString();
                LOGGER.info("Keystore type: {}", keystoreType);
            }
            
            if (sslConfig.has("certPath")) {
                certPath = sslConfig.get("certPath").getAsString();
                LOGGER.info("Certificate file path: {}", certPath);
            }
            
            if (sslConfig.has("keyPath")) {
                keyPath = sslConfig.get("keyPath").getAsString();
                LOGGER.info("Private key file path: {}", keyPath);
            }
        }
        LOGGER.info("SSL/HTTPS is {}", sslEnabled ? "ENABLED" : "DISABLED");

        if (config.has("tokenMode")) {
            tokenMode = config.get("tokenMode").getAsString();
            if (!"auto".equals(tokenMode) && !"persistent".equals(tokenMode)) {
                LOGGER.warn("Invalid tokenMode '{}', using default 'auto'", tokenMode);
                tokenMode = DEFAULT_TOKEN_MODE;
            }
        }
        LOGGER.info("Token mode: {}", tokenMode);

        if (config.has("persistentToken") && !config.get("persistentToken").isJsonNull()) {
            persistentToken = config.get("persistentToken").getAsString();
            LOGGER.info("Persistent token loaded from config");
        }

        if (config.has("shellEnabled")) {
            shellEnabled = config.get("shellEnabled").getAsBoolean();
            LOGGER.info("Shell executor: {}", shellEnabled ? "ENABLED" : "DISABLED");
        }
        if (config.has("shellTimeoutMs")) {
            try {
                int t = config.get("shellTimeoutMs").getAsInt();
                if (t >= 1000 && t <= 3_600_000) {
                    shellTimeoutMs = t;
                } else {
                    LOGGER.warn("Invalid shellTimeoutMs {} (must 1000..3600000), using default {}", t, DEFAULT_SHELL_TIMEOUT_MS);
                    shellTimeoutMs = DEFAULT_SHELL_TIMEOUT_MS;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to parse shellTimeoutMs, using default {}", DEFAULT_SHELL_TIMEOUT_MS);
                shellTimeoutMs = DEFAULT_SHELL_TIMEOUT_MS;
            }
        }
    }

    private JsonObject createDefaultConfig() {
        JsonObject defaultConfig = new JsonObject();
        defaultConfig.addProperty("fileEditor", false);
        defaultConfig.addProperty("httpPort", DEFAULT_HTTP_PORT);

        JsonObject sslConfig = new JsonObject();
        sslConfig.addProperty("enabled", false);
        sslConfig.addProperty("keystorePath", DEFAULT_KEYSTORE_PATH);
        sslConfig.addProperty("keystorePassword", DEFAULT_KEYSTORE_PASSWORD);
        sslConfig.addProperty("keystoreType", DEFAULT_KEYSTORE_TYPE);
        sslConfig.addProperty("certPath", DEFAULT_CERT_PATH);
        sslConfig.addProperty("keyPath", DEFAULT_KEY_PATH);
        defaultConfig.add("ssl", sslConfig);

        defaultConfig.addProperty("tokenMode", DEFAULT_TOKEN_MODE);
        defaultConfig.addProperty("persistentToken", (String) null);

        defaultConfig.addProperty("shellEnabled", DEFAULT_SHELL_ENABLED);
        defaultConfig.addProperty("shellTimeoutMs", DEFAULT_SHELL_TIMEOUT_MS);

        return defaultConfig;
    }

    private void saveConfig() {
        try {
            Path configPath = getConfigPath();
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(config, writer);
            }
            LOGGER.info("Saved MCPServer config to: {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    private void saveConfigWithComments() {
        try {
            Path configPath = getConfigPath();
            Files.createDirectories(configPath.getParent());
            
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  // ========== MCPServer 配置文件 ==========\n");
            sb.append("  // 路径: config/MCPServer/config.json\n");
            sb.append("  // 修改后需要重启服务器才能生效\n");
            sb.append("\n");
            
            sb.append("  // 是否启用文件编辑工具\n");
            sb.append("  // 启用后可以使用 file_read, file_write, file_append, file_delete, file_list 工具\n");
            sb.append("  // 默认值: false\n");
            sb.append("  \"fileEditor\": ").append(config.get("fileEditor").getAsBoolean()).append(",\n");
            sb.append("\n");
            
            sb.append("  // MCP HTTP 服务器监听端口\n");
            sb.append("  // 范围: 1-65535\n");
            sb.append("  // 默认值: 8081\n");
            sb.append("  \"httpPort\": ").append(config.get("httpPort").getAsInt()).append(",\n");
            sb.append("\n");
            
            sb.append("  // SSL/HTTPS 配置\n");
            sb.append("  \"ssl\": {\n");
            
            sb.append("    // 是否启用 SSL/HTTPS\n");
            sb.append("    // 启用后服务器将使用 HTTPS 协议\n");
            sb.append("    // 默认值: false\n");
            sb.append("    \"enabled\": ").append(config.getAsJsonObject("ssl").get("enabled").getAsBoolean()).append(",\n");
            sb.append("\n");
            
            sb.append("    // SSL 证书文件路径\n");
            sb.append("    // 支持绝对路径或相对于服务器根目录的相对路径\n");
            sb.append("    // 示例: \"config/MCPServer/my-cert.p12\"\n");
            sb.append("    \"keystorePath\": \"").append(config.getAsJsonObject("ssl").get("keystorePath").getAsString()).append("\",\n");
            sb.append("\n");
            
            sb.append("    // SSL 证书密码\n");
            sb.append("    \"keystorePassword\": \"").append(config.getAsJsonObject("ssl").get("keystorePassword").getAsString()).append("\",\n");
            sb.append("\n");
            
            sb.append("    // SSL 证书类型\n");
            sb.append("    // 支持: JKS, PKCS12\n");
            sb.append("    // 默认值: JKS\n");
            sb.append("    \"keystoreType\": \"").append(config.getAsJsonObject("ssl").get("keystoreType").getAsString()).append("\",\n");
            sb.append("\n");
            
            sb.append("    // === PEM 证书配置（优先于 keystore） ===\n");
            sb.append("    // 如果 certPath 和 keyPath 指向的文件存在，将优先使用 PEM 格式证书\n");
            sb.append("    // 适用于 Let's Encrypt 等颁发的证书（fullchain.pem + privkey.key）\n");
            sb.append("\n");
            
            sb.append("    // 完整证书链路径（包含服务器证书和中间CA证书）\n");
            sb.append("    // 示例: \"config/MCPServer/fullchain.pem\"\n");
            sb.append("    \"certPath\": \"").append(config.getAsJsonObject("ssl").get("certPath").getAsString()).append("\",\n");
            sb.append("\n");
            
            sb.append("    // 私钥文件路径\n");
            sb.append("    // 示例: \"config/MCPServer/privkey.key\"\n");
            sb.append("    \"keyPath\": \"").append(config.getAsJsonObject("ssl").get("keyPath").getAsString()).append("\"\n");

            sb.append("  },\n");
            sb.append("\n");

            sb.append("  // ========== Token 模式配置 ==========\n");
            sb.append("  // auto: 每次服务器启动自动生成新 token（默认）\n");
            sb.append("  // persistent: 使用固定 token，重启后保持不变\n");
            sb.append("  // 可通过命令 /mcpserver tokenmode <auto|persistent> 切换\n");
            sb.append("  \"tokenMode\": \"").append(config.get("tokenMode").getAsString()).append("\",\n");
            sb.append("\n");

            sb.append("  // 常驻 Token（仅 persistent 模式使用）\n");
            sb.append("  // 为 null 时首次启动会自动生成并保存到此文件\n");
            sb.append("  // 可通过命令 /mcpserver newtoken 重新生成\n");
            if (config.get("persistentToken").isJsonNull()) {
                sb.append("  \"persistentToken\": null,\n");
            } else {
                sb.append("  \"persistentToken\": \"").append(config.get("persistentToken").getAsString()).append("\",\n");
            }
            sb.append("\n");

            sb.append("  // ========== Shell 执行器（⚠ 高危功能） ==========\n");
            sb.append("  // 启用后，AI 可以执行系统级命令（cmd / bash）\n");
            sb.append("  // 默认: false。请确认风险后再启用。\n");
            sb.append("  \"shellEnabled\": ").append(config.get("shellEnabled").getAsBoolean()).append(",\n");
            sb.append("\n");

            sb.append("  // 单条 shell 命令超时时间（毫秒）\n");
            sb.append("  // 默认: 30000（30秒），范围 1000~3600000\n");
            sb.append("  \"shellTimeoutMs\": ").append(config.get("shellTimeoutMs").getAsInt()).append("\n");

            sb.append("}\n");
            
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                writer.write(sb.toString());
            }
            LOGGER.info("Saved MCPServer config with comments to: {}", configPath);
        } catch (IOException e) {
            LOGGER.error("Failed to save config: {}", e.getMessage());
        }
    }

    private Path getConfigPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("config").resolve(CONFIG_DIR_NAME).resolve(CONFIG_FILE_NAME);
    }

    public boolean isFileEditorEnabled() {
        return fileEditorEnabled;
    }

    public void setFileEditorEnabled(boolean enabled) {
        this.fileEditorEnabled = enabled;
        config.addProperty("fileEditor", enabled);
        saveConfig();
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        this.httpPort = port;
        config.addProperty("httpPort", port);
        saveConfig();
        LOGGER.info("HTTP Server port updated to: {}", port);
    }

    public boolean isSslEnabled() {
        return sslEnabled;
    }

    public void setSslEnabled(boolean enabled) {
        this.sslEnabled = enabled;
        if (!config.has("ssl") || !config.get("ssl").isJsonObject()) {
            config.add("ssl", new JsonObject());
        }
        config.getAsJsonObject("ssl").addProperty("enabled", enabled);
        saveConfig();
        LOGGER.info("SSL/HTTPS updated to: {}", enabled ? "ENABLED" : "DISABLED");
    }

    public Path getKeystorePath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(keystorePath);
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public String getKeystoreType() {
        return keystoreType;
    }

    public Path getCertPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(certPath);
    }

    public Path getKeyPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(keyPath);
    }

    public String getTokenMode() {
        return tokenMode;
    }

    public String getPersistentToken() {
        return persistentToken;
    }

    public void setTokenMode(String mode) {
        if (!"auto".equals(mode) && !"persistent".equals(mode)) {
            throw new IllegalArgumentException("tokenMode must be 'auto' or 'persistent'");
        }
        this.tokenMode = mode;
        config.addProperty("tokenMode", mode);
        saveConfig();
        LOGGER.info("Token mode updated to: {}", mode);
    }

    public void setPersistentToken(String token) {
        this.persistentToken = token;
        if (token == null) {
            config.add("persistentToken", com.google.gson.JsonNull.INSTANCE);
        } else {
            config.addProperty("persistentToken", token);
        }
        saveConfig();
        LOGGER.info("Persistent token saved to config");
    }

    public boolean isShellEnabled() {
        return shellEnabled;
    }

    public int getShellTimeoutMs() {
        return shellTimeoutMs;
    }

    public void setShellEnabled(boolean enabled) {
        this.shellEnabled = enabled;
        config.addProperty("shellEnabled", enabled);
        saveConfig();
        LOGGER.warn("Shell executor: {}", enabled ? "ENABLED ⚠ DANGER" : "DISABLED");
    }

    public void setShellTimeoutMs(int ms) {
        if (ms < 1000 || ms > 3_600_000) {
            throw new IllegalArgumentException("shellTimeoutMs must be between 1000 and 3600000");
        }
        this.shellTimeoutMs = ms;
        config.addProperty("shellTimeoutMs", ms);
        saveConfig();
        LOGGER.info("Shell timeout updated to: {}ms", ms);
    }
}