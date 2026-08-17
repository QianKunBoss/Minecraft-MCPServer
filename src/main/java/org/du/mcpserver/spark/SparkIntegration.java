package org.du.mcpserver.spark;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.lucko.spark.api.Spark;
import me.lucko.spark.api.SparkProvider;
import me.lucko.spark.api.gc.GarbageCollector;
import me.lucko.spark.api.statistic.misc.DoubleAverageInfo;
import me.lucko.spark.api.statistic.types.DoubleStatistic;
import me.lucko.spark.api.statistic.types.GenericStatistic;
import me.lucko.spark.api.statistic.StatisticWindow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import org.du.mcpserver.util.MCCompat;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SparkIntegration {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");

    private static final long RETRY_DELAY_MS = 1000;

    /** Spark 未安装时，AI 调用 Spark 相关工具/API 收到的统一提示语 */
    public static final String SPARK_UNAVAILABLE_MESSAGE = "未安装spark模组，如需性能分析，请安装spark性能分析模组";

    private MinecraftServer server;
    private Spark sparkApi;
    private boolean sparkAvailable = false;
    private final ConcurrentHashMap<String, ProfilerSession> profilerSessions = new ConcurrentHashMap<>();

    public static class ProfilerSession {
        public final String profilerId;
        public final String type;
        public final long startTime;
        public volatile long endTime;
        public volatile boolean running;
        public volatile String outputFilePath;

        public ProfilerSession(String profilerId, String type, long startTime) {
            this.profilerId = profilerId;
            this.type = type;
            this.startTime = startTime;
            this.endTime = 0;
            this.running = true;
            this.outputFilePath = null;
        }
    }

    public void initialize(MinecraftServer minecraftServer) {
        this.server = minecraftServer;

        // 关键：本方法在服务器主线程（SERVER_STARTED 生命周期回调）中同步执行。
        // 若此处阻塞，会卡住游戏主循环，触发看门狗强制关闭服务器。
        // 因此绝不能在主线程上做长时间等待。
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("spark")) {
            // Spark 模组根本没装：立即降级，不等待、不阻塞、不报错关闭服务器
            this.sparkAvailable = false;
            this.sparkApi = null;
            LOGGER.warn("Spark mod is NOT installed. Spark performance-analysis tools are disabled. {}",
                    SPARK_UNAVAILABLE_MESSAGE);
            return;
        }

        // Spark 模组已安装，但 SparkProvider API 可能还没就绪，先快速试一次（无阻塞）。
        try {
            this.sparkApi = SparkProvider.get();
            this.sparkAvailable = true;
            LOGGER.info("Spark integration initialized (immediate).");
            return;
        } catch (IllegalStateException e) {
            // API 尚未就绪，放到后台线程等待，避免阻塞服务器主线程
        } catch (NoClassDefFoundError e) {
            this.sparkAvailable = false;
            this.sparkApi = null;
            LOGGER.warn("Spark API classes not found - Spark features disabled.");
            return;
        }

        // 后台守护线程中等待 Spark API 就绪（最多 60s），不阻塞服务器主线程
        Thread initThread = new Thread(this::waitForSparkToInitialize, "MCPServer-SparkInit");
        initThread.setDaemon(true);
        initThread.start();
        LOGGER.info("Spark mod detected; initializing Spark integration in background...");
    }

    private void waitForSparkToInitialize() {
        int attempts = 0;
        while (attempts < 60) {
            try {
                sparkApi = SparkProvider.get();
                sparkAvailable = true;
                LOGGER.info("Spark mod detected via API after {} attempts", attempts + 1);
                return;
            } catch (IllegalStateException e) {
                attempts++;
                if (attempts % 10 == 0) {
                    LOGGER.info("Waiting for Spark to initialize... attempt {}/{}", attempts, 60);
                }
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            } catch (NoClassDefFoundError e) {
                sparkAvailable = false;
                sparkApi = null;
                LOGGER.warn("Spark API classes not found - Spark features disabled.");
                return;
            }
        }

        sparkAvailable = false;
        sparkApi = null;
        LOGGER.warn("Spark API not ready after {} attempts - Spark features disabled. {}",
                60, SPARK_UNAVAILABLE_MESSAGE);
    }

    public void checkSparkAvailability() {
        try {
            sparkApi = SparkProvider.get();
            sparkAvailable = true;
            LOGGER.info("Spark mod detected via API");
        } catch (IllegalStateException e) {
            sparkAvailable = false;
            sparkApi = null;
            LOGGER.error("Spark mod is required but not available: {}", e.getMessage());
        } catch (NoClassDefFoundError e) {
            sparkAvailable = false;
            sparkApi = null;
            LOGGER.error("Spark API classes not found - please install Spark mod");
        }
    }

    public boolean isSparkAvailable() {
        return sparkAvailable;
    }

    public JsonObject checkAvailability() {
        JsonObject result = new JsonObject();
        result.addProperty("available", sparkAvailable);
        result.addProperty("message", sparkAvailable ? "Spark mod is available" : SPARK_UNAVAILABLE_MESSAGE);
        return result;
    }

    public JsonObject getTPSMetrics() {
        JsonObject result = new JsonObject();

        if (!sparkAvailable || sparkApi == null) {
            result.addProperty("error", SPARK_UNAVAILABLE_MESSAGE);
            return result;
        }

        try {
            DoubleStatistic<StatisticWindow.TicksPerSecond> tps = sparkApi.tps();
            if (tps != null) {
                result.addProperty("tps_5s", tps.poll(StatisticWindow.TicksPerSecond.SECONDS_5));
                result.addProperty("tps_10s", tps.poll(StatisticWindow.TicksPerSecond.SECONDS_10));
                result.addProperty("tps_1m", tps.poll(StatisticWindow.TicksPerSecond.MINUTES_1));
                result.addProperty("tps_5m", tps.poll(StatisticWindow.TicksPerSecond.MINUTES_5));
                result.addProperty("tps_15m", tps.poll(StatisticWindow.TicksPerSecond.MINUTES_15));
            }
            result.addProperty("status", "success");
        } catch (Exception e) {
            result.addProperty("error", "获取TPS指标失败: " + e.getMessage());
        }

        return result;
    }

    public JsonObject getMSPTMetrics() {
        JsonObject result = new JsonObject();

        if (!sparkAvailable || sparkApi == null) {
            result.addProperty("error", SPARK_UNAVAILABLE_MESSAGE);
            return result;
        }

        try {
            GenericStatistic<DoubleAverageInfo, StatisticWindow.MillisPerTick> mspt = sparkApi.mspt();
            if (mspt != null) {
                DoubleAverageInfo mspt10s = mspt.poll(StatisticWindow.MillisPerTick.SECONDS_10);
                DoubleAverageInfo mspt1m = mspt.poll(StatisticWindow.MillisPerTick.MINUTES_1);
                DoubleAverageInfo mspt5m = mspt.poll(StatisticWindow.MillisPerTick.MINUTES_5);

                JsonObject mspt10sObj = new JsonObject();
                mspt10sObj.addProperty("mean", mspt10s.mean());
                mspt10sObj.addProperty("min", mspt10s.min());
                mspt10sObj.addProperty("max", mspt10s.max());
                mspt10sObj.addProperty("median", mspt10s.median());
                mspt10sObj.addProperty("percentile95th", mspt10s.percentile95th());
                mspt10sObj.addProperty("percentile99th", mspt10s.percentile(0.99));
                result.add("mspt_10s", mspt10sObj);

                JsonObject mspt1mObj = new JsonObject();
                mspt1mObj.addProperty("mean", mspt1m.mean());
                mspt1mObj.addProperty("min", mspt1m.min());
                mspt1mObj.addProperty("max", mspt1m.max());
                mspt1mObj.addProperty("median", mspt1m.median());
                mspt1mObj.addProperty("percentile95th", mspt1m.percentile95th());
                mspt1mObj.addProperty("percentile99th", mspt1m.percentile(0.99));
                result.add("mspt_1m", mspt1mObj);

                JsonObject mspt5mObj = new JsonObject();
                mspt5mObj.addProperty("mean", mspt5m.mean());
                mspt5mObj.addProperty("min", mspt5m.min());
                mspt5mObj.addProperty("max", mspt5m.max());
                mspt5mObj.addProperty("median", mspt5m.median());
                mspt5mObj.addProperty("percentile95th", mspt5m.percentile95th());
                mspt5mObj.addProperty("percentile99th", mspt5m.percentile(0.99));
                result.add("mspt_5m", mspt5mObj);
            }
            result.addProperty("status", "success");
        } catch (Exception e) {
            result.addProperty("error", "获取MSPT指标失败: " + e.getMessage());
        }

        return result;
    }

    public JsonObject getCPUUsage() {
        JsonObject result = new JsonObject();

        if (!sparkAvailable || sparkApi == null) {
            result.addProperty("error", SPARK_UNAVAILABLE_MESSAGE);
            return result;
        }

        try {
            DoubleStatistic<StatisticWindow.CpuUsage> cpuProcess = sparkApi.cpuProcess();
            DoubleStatistic<StatisticWindow.CpuUsage> cpuSystem = sparkApi.cpuSystem();

            if (cpuProcess != null) {
                result.addProperty("cpu_process_10s", cpuProcess.poll(StatisticWindow.CpuUsage.SECONDS_10));
                result.addProperty("cpu_process_1m", cpuProcess.poll(StatisticWindow.CpuUsage.MINUTES_1));
                result.addProperty("cpu_process_15m", cpuProcess.poll(StatisticWindow.CpuUsage.MINUTES_15));
            }

            if (cpuSystem != null) {
                result.addProperty("cpu_system_10s", cpuSystem.poll(StatisticWindow.CpuUsage.SECONDS_10));
                result.addProperty("cpu_system_1m", cpuSystem.poll(StatisticWindow.CpuUsage.MINUTES_1));
                result.addProperty("cpu_system_15m", cpuSystem.poll(StatisticWindow.CpuUsage.MINUTES_15));
            }

            result.addProperty("status", "success");
        } catch (Exception e) {
            result.addProperty("error", "获取CPU使用率失败: " + e.getMessage());
        }

        return result;
    }

    public JsonObject getGCStats() {
        JsonObject result = new JsonObject();

        if (!sparkAvailable || sparkApi == null) {
            result.addProperty("error", SPARK_UNAVAILABLE_MESSAGE);
            return result;
        }

        try {
            Map<String, GarbageCollector> gc = sparkApi.gc();
            if (gc != null && !gc.isEmpty()) {
                JsonArray gcArray = new JsonArray();
                for (GarbageCollector collector : gc.values()) {
                    JsonObject gcObj = new JsonObject();
                    gcObj.addProperty("name", collector.name());
                    gcObj.addProperty("avgFrequency", collector.avgFrequency());
                    gcObj.addProperty("avgTime", collector.avgTime());
                    gcObj.addProperty("totalCollections", collector.totalCollections());
                    gcObj.addProperty("totalTime", collector.totalTime());
                    gcArray.add(gcObj);
                }
                result.add("garbageCollectors", gcArray);
            }
            result.addProperty("status", "success");
        } catch (Exception e) {
            result.addProperty("error", "获取GC统计信息失败: " + e.getMessage());
        }

        return result;
    }

    public JsonObject getHealthReport() {
        JsonObject result = new JsonObject();

        if (!sparkAvailable || sparkApi == null) {
            result.addProperty("error", SPARK_UNAVAILABLE_MESSAGE);
            return result;
        }

        try {
            result.add("tps", getTPSMetrics());
            result.add("mspt", getMSPTMetrics());
            result.add("cpu", getCPUUsage());
            result.add("gc", getGCStats());
            result.addProperty("status", "generated");
        } catch (Exception e) {
            result.addProperty("error", "生成健康报告失败: " + e.getMessage());
        }

        return result;
    }

    public JsonObject startProfiler(String profilerType, Integer duration) {
        JsonObject result = new JsonObject();

        if (!sparkAvailable || sparkApi == null) {
            result.addProperty("error", SPARK_UNAVAILABLE_MESSAGE);
            return result;
        }

        String profilerId = UUID.randomUUID().toString();
        long durationSec = duration != null ? duration : 30;

        ProfilerSession session = new ProfilerSession(profilerId, profilerType, System.currentTimeMillis());
        profilerSessions.put(profilerId, session);

        StringBuilder command = new StringBuilder("spark profiler start");
        if (!"default".equals(profilerType) && !"cpu".equals(profilerType)) {
            if ("alloc".equals(profilerType) || "memory".equals(profilerType) || "allocs".equals(profilerType)) {
                command.append(" --alloc");
            } else if ("sampler".equals(profilerType)) {
                command.append(" --force-java-sampler");
            }
        }

        final String finalCommand = command.toString();
        server.execute(() -> {
            try {
                ServerCommandSource source = MCCompat.withLevel(server.getCommandSource(), 4)
                        .withSilent();
                MCCompat.executeCommand(server, source, finalCommand);
                LOGGER.info("Started Spark profiler: {} (id: {})", finalCommand, profilerId);
            } catch (Exception e) {
                LOGGER.error("Failed to start Spark profiler: {}", e.getMessage());
                session.running = false;
                session.endTime = System.currentTimeMillis();
            }
        });

        result.addProperty("profilerId", profilerId);
        result.addProperty("type", profilerType);
        result.addProperty("command", finalCommand);
        result.addProperty("status", "started");
        result.addProperty("note", "分析器已启动。调用 spark_stop_profiler 停止并保存结果。");

        return result;
    }

    public JsonObject stopProfiler(String profilerId) {
        JsonObject result = new JsonObject();

        if (!sparkAvailable || sparkApi == null) {
            result.addProperty("error", SPARK_UNAVAILABLE_MESSAGE);
            return result;
        }

        ProfilerSession session = profilerSessions.get(profilerId);
        if (session == null) {
            result.addProperty("error", "分析器不存在");
            return result;
        }

        if (!session.running) {
            result.addProperty("error", "分析器未在运行");
            return result;
        }

        session.running = false;
        session.endTime = System.currentTimeMillis();

        server.execute(() -> {
            try {
                String stopCommand = "spark profiler stop --save-to-file";
                ServerCommandSource source = MCCompat.withLevel(server.getCommandSource(), 4)
                        .withSilent();
                MCCompat.executeCommand(server, source, stopCommand);
                LOGGER.info("Stopped Spark profiler, saving to file");

                for (int i = 0; i < 60; i++) {
                    Thread.sleep(1000);
                    String outputFile = findLatestProfilerFile(session.startTime);
                    if (outputFile != null) {
                        session.outputFilePath = outputFile;
                        LOGGER.info("Profiler result saved to: {}", outputFile);
                        return;
                    }
                }
                LOGGER.warn("Profiler file not found after waiting 60 seconds");
            } catch (Exception e) {
                LOGGER.error("Failed to stop Spark profiler: {}", e.getMessage());
            }
        });

        result.addProperty("profilerId", profilerId);
        result.addProperty("status", "stopped");
        result.addProperty("note", "分析器已停止。使用 spark_get_profiler_result 获取二进制数据。");

        return result;
    }

    public JsonObject getProfilerStatus(String profilerId) {
        JsonObject result = new JsonObject();

        ProfilerSession session = profilerSessions.get(profilerId);
        if (session == null) {
            result.addProperty("error", "分析器不存在");
            return result;
        }

        result.addProperty("profilerId", profilerId);
        result.addProperty("type", session.type);
        result.addProperty("startTime", session.startTime);
        result.addProperty("endTime", session.endTime);
        result.addProperty("running", session.running);
        result.addProperty("duration", session.endTime > 0
                ? (session.endTime - session.startTime) / 1000
                : (System.currentTimeMillis() - session.startTime) / 1000);

        if (session.outputFilePath != null) {
            result.addProperty("outputFile", session.outputFilePath);
            result.addProperty("fileReady", true);
        } else {
            result.addProperty("fileReady", false);
        }

        return result;
    }

    public JsonObject getProfilerResult(String profilerId) {
        JsonObject result = new JsonObject();

        ProfilerSession session = profilerSessions.get(profilerId);
        if (session == null) {
            result.addProperty("error", "分析器不存在");
            return result;
        }

        if (session.outputFilePath == null) {
            result.addProperty("error", "分析器结果文件尚未就绪");
            result.addProperty("running", session.running);
            result.addProperty("note", "停止后等待几秒再重试");
            return result;
        }

        File file = new File(session.outputFilePath);
        if (!file.exists()) {
            result.addProperty("error", "分析器结果文件不存在");
            return result;
        }

        result.addProperty("profilerId", profilerId);
        result.addProperty("type", session.type);
        result.addProperty("outputFile", session.outputFilePath);
        result.addProperty("fileName", file.getName());
        result.addProperty("fileSize", file.length());
        result.addProperty("startTime", session.startTime);
        result.addProperty("endTime", session.endTime);
        result.addProperty("durationMs", session.endTime - session.startTime);

        try {
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            String base64Data = java.util.Base64.getEncoder().encodeToString(fileBytes);
            result.addProperty("encoding", "base64");
            result.addProperty("dataSize", fileBytes.length);
            result.addProperty("data", base64Data);
            result.addProperty("note", "数据为Base64编码的二进制。解码后可上传到 https://spark.lucko.me 查看，或在已知格式的情况下直接解析。");
        } catch (IOException e) {
            result.addProperty("error", "读取文件失败: " + e.getMessage());
        }

        return result;
    }

    private String findLatestProfilerFile(long afterTimestamp) {
        try {
            Path configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("config").resolve("spark");
            if (!Files.exists(configDir)) {
                configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("spark");
            }
            if (!Files.exists(configDir)) {
                return null;
            }

            return Files.list(configDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".sparkprofile") || name.endsWith(".spark");
                    })
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() >= afterTimestamp;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0;
                        }
                    }))
                    .map(Path::toString)
                    .orElse(null);
        } catch (IOException e) {
            LOGGER.error("Failed to find profiler file: {}", e.getMessage());
            return null;
        }
    }

    public JsonObject createHeapDump() {
        JsonObject result = new JsonObject();

        if (!sparkAvailable || sparkApi == null) {
            result.addProperty("error", SPARK_UNAVAILABLE_MESSAGE);
            return result;
        }

        server.execute(() -> {
            try {
                ServerCommandSource source = MCCompat.withLevel(server.getCommandSource(), 4)
                        .withSilent();
                MCCompat.executeCommand(server, source, "spark heap --save-to-file");
                LOGGER.info("Heap dump initiated via Spark command");
            } catch (Exception e) {
                LOGGER.error("Failed to create heap dump: {}", e.getMessage());
            }
        });

        result.addProperty("status", "initiated");
        result.addProperty("message", "堆转储创建已启动，将保存到spark配置目录");

        return result;
    }
}
