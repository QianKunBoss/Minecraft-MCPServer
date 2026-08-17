package org.du.mcpserver;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import org.du.mcpserver.command.MCPServerCommand;
import org.du.mcpserver.http.MCPHttpServer;
import org.du.mcpserver.monitor.ConnectionManager;
import org.du.mcpserver.monitor.LogMonitor;
import org.du.mcpserver.monitor.PlayerInfoManager;
import org.du.mcpserver.monitor.ServerMetrics;
import org.du.mcpserver.monitor.behavior.PlayerBehaviorRecorder;
import org.du.mcpserver.spark.SparkIntegration;
import org.du.mcpserver.util.ConfigManager;
import org.du.mcpserver.util.ConfigPrinter;
import org.du.mcpserver.util.FileEditor;
import org.du.mcpserver.util.SecurityUtils;
import org.du.mcpserver.util.VersionCompat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

public class Mcpserver implements ModInitializer {

    public static final String MOD_ID = "mcpserver";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private static Mcpserver instance;

    private LogMonitor logMonitor;
    private ServerMetrics serverMetrics;
    private PlayerInfoManager playerInfoManager;
    private SparkIntegration sparkIntegration;
    private MCPHttpServer httpServer;
    private ConnectionManager connectionManager;
    private ConfigManager configManager;
    private FileEditor fileEditor;
    private PlayerBehaviorRecorder behaviorRecorder;

    private volatile String apiKey;

    @Override
    public void onInitialize() {
        instance = this;

        LOGGER.info("========================================");
        LOGGER.info("MCPServer Mod Initializing");
        LOGGER.info("Minecraft Version: {}", VersionCompat.getMinecraftVersion());
        LOGGER.info("========================================");

        checkSparkModPresence();

        registerCommands();

        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    private void registerCommands() {
        if (VersionCompat.isCommandV2Available()) {
            CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
                MCPServerCommand.register(dispatcher);
                org.du.mcpserver.command.BehaviorRecorderCommand.register(dispatcher);
            });
        } else {
            LOGGER.info("Command API v2 not available, skipping command registration");
        }
    }

    private void checkSparkModPresence() {
        Optional<ModContainer> sparkMod = FabricLoader.getInstance().getModContainer("spark");
        if (sparkMod.isPresent()) {
            String sparkVersion = sparkMod.get().getMetadata().getVersion().getFriendlyString();
            LOGGER.info("Spark mod detected: version {}", sparkVersion);
        } else {
            LOGGER.warn("Spark mod not found! Some features may not work correctly.");
            LOGGER.warn("For full functionality, install Spark mod (version >= 1.10.0).");
        }
    }

    private void onServerStarting(MinecraftServer server) {
        LOGGER.info("Server starting, initializing modules...");

        configManager = new ConfigManager(server);
        fileEditor = new FileEditor(server);

        // 根据 token 模式初始化 apiKey
        initApiKey();

        connectionManager = new ConnectionManager();

        logMonitor = new LogMonitor();
        logMonitor.initialize(server);

        serverMetrics = new ServerMetrics();
        serverMetrics.initialize(server);

        playerInfoManager = new PlayerInfoManager(server);

        behaviorRecorder = new PlayerBehaviorRecorder(server);
        behaviorRecorder.start();

        sparkIntegration = new SparkIntegration();
    }

    /**
     * 根据 config.json 中的 tokenMode 初始化 apiKey：
     * - auto: 每次启动生成新 token
     * - persistent: 从配置读取固定 token，不存在则生成并保存
     */
    private void initApiKey() {
        String mode = configManager.getTokenMode();
        if ("persistent".equals(mode)) {
            String saved = configManager.getPersistentToken();
            if (saved != null && !saved.isBlank()) {
                apiKey = saved;
                LOGGER.info("[Token] Loaded persistent token from config");
            } else {
                apiKey = SecurityUtils.generateApiKey();
                configManager.setPersistentToken(apiKey);
                LOGGER.info("[Token] Generated new persistent token and saved to config");
            }
        } else {
            apiKey = SecurityUtils.generateApiKey();
            LOGGER.info("[Token] Generated new auto-rotating token (mode=auto)");
        }
    }

    private void onServerStarted(MinecraftServer server) {
        sparkIntegration.initialize(server);

        if (!FabricLoader.getInstance().isModLoaded("spark")) {
            LOGGER.warn("========================================");
            LOGGER.warn("Spark mod is NOT installed - Spark performance-analysis tools are disabled.");
            LOGGER.warn(org.du.mcpserver.spark.SparkIntegration.SPARK_UNAVAILABLE_MESSAGE);
            LOGGER.warn("========================================");
        } else {
            LOGGER.info("Spark mod detected; Spark integration initializing (background if API not yet ready).");
        }

        int port = configManager.getHttpPort();
        httpServer = new MCPHttpServer(port, apiKey, logMonitor, serverMetrics, playerInfoManager,
                sparkIntegration, connectionManager, configManager, fileEditor);
        httpServer.start();

        LOGGER.info("========================================");
        LOGGER.info("MCPServer is ready!");
        String protocol = configManager.isSslEnabled() ? "https" : "http";
        LOGGER.info("MCP Endpoint: {}://localhost:{}/mcp", protocol, port);
        LOGGER.info("API Key: {}", apiKey);
        LOGGER.info("SSL/HTTPS: {}", configManager.isSslEnabled() ? "ENABLED" : "DISABLED");
        LOGGER.info("Spark Integration: {}", sparkIntegration.isSparkAvailable() ? "ENABLED" : "DISABLED");
        LOGGER.info("Minecraft Version: {}", VersionCompat.getMinecraftVersion());
        LOGGER.info("========================================");

        ConfigPrinter.printClientConfig(port, apiKey, configManager.isSslEnabled());
    }

    private void onServerStopping(MinecraftServer server) {
        LOGGER.info("Server stopping, cleaning up...");

        if (httpServer != null) {
            httpServer.stop();
            try {
                httpServer.getProtocolHandler().shutdown();
            } catch (Exception ignored) {
                // 忽略清理线程关闭异常
            }
        }

        if (behaviorRecorder != null) {
            behaviorRecorder.stop();
        }

        if (logMonitor != null) {
            logMonitor.shutdown();
        }

        SecurityUtils.cleanupExpiredSessions();
    }

    private void onServerTick(MinecraftServer server) {
        if (serverMetrics != null) {
            serverMetrics.onTick();
        }
    }

    public static Mcpserver getInstance() {
        return instance;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        if (httpServer != null) {
            httpServer.updateApiKey(apiKey);
        }
        // 如果是 persistent 模式，同时更新 config 中的持久化 token
        if (configManager != null && "persistent".equals(configManager.getTokenMode())) {
            configManager.setPersistentToken(apiKey);
        }
        LOGGER.info("API Key has been updated");
    }

    public ConnectionManager getConnectionManager() {
        return connectionManager;
    }

    public int getHttpPort() {
        return configManager != null ? configManager.getHttpPort() : 8081;
    }

    public LogMonitor getLogMonitor() {
        return logMonitor;
    }

    public ServerMetrics getServerMetrics() {
        return serverMetrics;
    }

    public SparkIntegration getSparkIntegration() {
        return sparkIntegration;
    }

    public PlayerBehaviorRecorder getBehaviorRecorder() {
        return behaviorRecorder;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }
}