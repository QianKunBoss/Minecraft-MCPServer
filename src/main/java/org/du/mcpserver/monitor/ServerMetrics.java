package org.du.mcpserver.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.EntityType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public class ServerMetrics {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");
    private static final int TPS_HISTORY_SIZE = 60;
    private static final int METRICS_HISTORY_SIZE = 30;

    private MinecraftServer server;
    private final Deque<Double> tpsHistory = new ConcurrentLinkedDeque<>();
    private final Deque<MemoryMetrics> memoryHistory = new ConcurrentLinkedDeque<>();
    private final Deque<EntityMetrics> entityHistory = new ConcurrentLinkedDeque<>();

    private long lastTickTime = System.currentTimeMillis();
    private int tickCount = 0;
    private double currentTPS = 20.0;

    public static class MemoryMetrics {
        public final long timestamp;
        public final long usedHeap;
        public final long maxHeap;
        public final long usedNonHeap;
        public final long maxNonHeap;
        public final double heapUsagePercent;

        public MemoryMetrics(long timestamp, long usedHeap, long maxHeap, long usedNonHeap, long maxNonHeap) {
            this.timestamp = timestamp;
            this.usedHeap = usedHeap;
            this.maxHeap = maxHeap;
            this.usedNonHeap = usedNonHeap;
            this.maxNonHeap = maxNonHeap;
            this.heapUsagePercent = maxHeap > 0 ? (double) usedHeap / maxHeap * 100 : 0;
        }
    }

    public static class EntityMetrics {
        public final long timestamp;
        public final int totalEntities;
        public final int totalPlayers;
        public final Map<String, Integer> entityCounts;

        public EntityMetrics(long timestamp, int totalEntities, int totalPlayers, Map<String, Integer> entityCounts) {
            this.timestamp = timestamp;
            this.totalEntities = totalEntities;
            this.totalPlayers = totalPlayers;
            this.entityCounts = entityCounts;
        }
    }

    public void initialize(MinecraftServer minecraftServer) {
        this.server = minecraftServer;
        LOGGER.info("ServerMetrics initialized");
    }

    public MinecraftServer getServer() {
        return server;
    }

    public void onTick() {
        tickCount++;
        long currentTime = System.currentTimeMillis();
        long deltaTime = currentTime - lastTickTime;

        if (deltaTime >= 1000) {
            currentTPS = (tickCount * 1000.0) / deltaTime;
            if (currentTPS > 20) {
                currentTPS = 20.0;
            }

            tpsHistory.addLast(currentTPS);
            while (tpsHistory.size() > TPS_HISTORY_SIZE) {
                tpsHistory.pollFirst();
            }

            collectMemoryMetrics();
            collectEntityMetrics();

            tickCount = 0;
            lastTickTime = currentTime;
        }
    }

    private void collectMemoryMetrics() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long usedHeap = memoryBean.getHeapMemoryUsage().getUsed();
        long maxHeap = memoryBean.getHeapMemoryUsage().getMax();
        long usedNonHeap = memoryBean.getNonHeapMemoryUsage().getUsed();
        long maxNonHeap = memoryBean.getNonHeapMemoryUsage().getMax();

        memoryHistory.addLast(new MemoryMetrics(System.currentTimeMillis(), usedHeap, maxHeap, usedNonHeap, maxNonHeap));
        while (memoryHistory.size() > METRICS_HISTORY_SIZE) {
            memoryHistory.pollFirst();
        }
    }

    private void collectEntityMetrics() {
        if (server == null || !server.isRunning()) {
            return;
        }

        int totalEntities = 0;
        int totalPlayers = server.getCurrentPlayerCount();
        Map<String, Integer> entityCounts = new ConcurrentHashMap<>();

        for (ServerWorld world : server.getWorlds()) {
            for (net.minecraft.entity.Entity entity : world.iterateEntities()) {
                totalEntities++;
                String entityTypeName = entity.getType().getName().getString();
                entityCounts.merge(entityTypeName, 1, Integer::sum);
            }
        }

        entityHistory.addLast(new EntityMetrics(System.currentTimeMillis(), totalEntities, totalPlayers, entityCounts));
        while (entityHistory.size() > METRICS_HISTORY_SIZE) {
            entityHistory.pollFirst();
        }
    }

    public JsonObject getTPSMetrics() {
        JsonObject result = new JsonObject();

        double avgTPS = tpsHistory.stream().mapToDouble(Double::doubleValue).average().orElse(20.0);
        double minTPS = tpsHistory.stream().mapToDouble(Double::doubleValue).min().orElse(20.0);
        double maxTPS = tpsHistory.stream().mapToDouble(Double::doubleValue).max().orElse(20.0);

        result.addProperty("current", currentTPS);
        result.addProperty("average", avgTPS);
        result.addProperty("min", minTPS);
        result.addProperty("max", maxTPS);

        JsonArray history = new JsonArray();
        for (Double tps : tpsHistory) {
            history.add(tps);
        }
        result.add("history", history);

        return result;
    }

    public JsonObject getMemoryMetrics() {
        JsonObject result = new JsonObject();

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        JsonObject heap = new JsonObject();
        heap.addProperty("used", memoryBean.getHeapMemoryUsage().getUsed());
        heap.addProperty("max", memoryBean.getHeapMemoryUsage().getMax());
        heap.addProperty("committed", memoryBean.getHeapMemoryUsage().getCommitted());
        heap.addProperty("init", memoryBean.getHeapMemoryUsage().getInit());
        heap.addProperty("usagePercent", memoryBean.getHeapMemoryUsage().getMax() > 0
                ? (double) memoryBean.getHeapMemoryUsage().getUsed() / memoryBean.getHeapMemoryUsage().getMax() * 100
                : 0);

        JsonObject nonHeap = new JsonObject();
        nonHeap.addProperty("used", memoryBean.getNonHeapMemoryUsage().getUsed());
        nonHeap.addProperty("max", memoryBean.getNonHeapMemoryUsage().getMax());
        nonHeap.addProperty("committed", memoryBean.getNonHeapMemoryUsage().getCommitted());
        nonHeap.addProperty("init", memoryBean.getNonHeapMemoryUsage().getInit());

        JsonObject system = new JsonObject();
        system.addProperty("availableProcessors", osBean.getAvailableProcessors());
        system.addProperty("systemLoadAverage", osBean.getSystemLoadAverage());

        result.add("heap", heap);
        result.add("nonHeap", nonHeap);
        result.add("system", system);

        JsonArray history = new JsonArray();
        for (MemoryMetrics metrics : memoryHistory) {
            JsonObject entry = new JsonObject();
            entry.addProperty("timestamp", metrics.timestamp);
            entry.addProperty("usedHeap", metrics.usedHeap);
            entry.addProperty("heapUsagePercent", metrics.heapUsagePercent);
            history.add(entry);
        }
        result.add("history", history);

        return result;
    }

    public JsonObject getEntityMetrics() {
        JsonObject result = new JsonObject();

        if (entityHistory.isEmpty()) {
            collectEntityMetrics();
        }

        EntityMetrics latest = entityHistory.peekLast();
        if (latest != null) {
            result.addProperty("totalEntities", latest.totalEntities);
            result.addProperty("totalPlayers", latest.totalPlayers);

            JsonObject entityTypes = new JsonObject();
            latest.entityCounts.forEach((key, value) -> entityTypes.addProperty(key, value));
            result.add("entityTypes", entityTypes);

            JsonArray history = new JsonArray();
            for (EntityMetrics metrics : entityHistory) {
                JsonObject entry = new JsonObject();
                entry.addProperty("timestamp", metrics.timestamp);
                entry.addProperty("totalEntities", metrics.totalEntities);
                entry.addProperty("totalPlayers", metrics.totalPlayers);
                history.add(entry);
            }
            result.add("history", history);
        }

        return result;
    }

    public JsonObject getServerStatus() {
        JsonObject result = new JsonObject();

        if (server == null) {
            result.addProperty("status", "not_initialized");
            return result;
        }

        result.addProperty("status", server.isRunning() ? "running" : "stopped");
        result.addProperty("minecraftVersion", server.getVersion());
        result.addProperty("maxPlayers", server.getMaxPlayerCount());
        result.addProperty("onlinePlayers", server.getCurrentPlayerCount());

        JsonArray playerNames = new JsonArray();
        server.getPlayerManager().getPlayerList().forEach(player ->
                playerNames.add(player.getName().getString())
        );
        result.add("players", playerNames);

        JsonArray worlds = new JsonArray();
        server.getWorlds().forEach(world -> {
            JsonObject worldObj = new JsonObject();
            worldObj.addProperty("name", world.getRegistryKey().getValue().toString());
            worldObj.addProperty("dimension", world.getDimension().toString());
            worldObj.addProperty("time", world.getTime());
            worldObj.addProperty("dayTime", world.getTimeOfDay());
            worlds.add(worldObj);
        });
        result.add("worlds", worlds);

        result.add("tps", getTPSMetrics());
        result.add("memory", getMemoryMetrics());
        result.add("entities", getEntityMetrics());

        return result;
    }

    public JsonObject getThreadMetrics() {
        JsonObject result = new JsonObject();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        result.addProperty("threadCount", threadBean.getThreadCount());
        result.addProperty("peakThreadCount", threadBean.getPeakThreadCount());
        result.addProperty("daemonThreadCount", threadBean.getDaemonThreadCount());
        result.addProperty("totalStartedThreadCount", threadBean.getTotalStartedThreadCount());

        return result;
    }
}
