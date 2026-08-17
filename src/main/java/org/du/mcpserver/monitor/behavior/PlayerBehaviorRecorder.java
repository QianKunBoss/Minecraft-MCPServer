package org.du.mcpserver.monitor.behavior;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.du.mcpserver.util.MCCompat;

import java.io.BufferedWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerBehaviorRecorder {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");

    // 默认采样间隔（毫秒）
    public static final long DEFAULT_HIGH_FREQ_INTERVAL = 500;   // 高频：生命值、位置、移动等
    public static final long DEFAULT_LOW_FREQ_INTERVAL = 5000;    // 低频：背包内容
    public static final int DEFAULT_MAX_HISTORY_PER_PLAYER = 7200; // 1小时 @ 500ms/条 = 7200条

    private final MinecraftServer server;
    private volatile boolean running = false;
    private Thread recorderThread;

    // 被监测的玩家集合 (playerName lowercase -> config)
    private final ConcurrentHashMap<String, TrackingConfig> trackedPlayers = new ConcurrentHashMap<>();
    private final Set<String> trackedPlayerNames = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean trackAllPlayers = false;

    // 数据存储：每个玩家独立的时间有序队列
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<PlayerBehaviorRecord>> history = new ConcurrentHashMap<>();
    private final int maxHistoryPerPlayer;
    private final long highFreqIntervalMs;
    private final long lowFreqIntervalMs;

    // 异步写入队列（降低主线程压力）
    private final ConcurrentLinkedDeque<PlayerBehaviorRecord> writeQueue = new ConcurrentLinkedDeque<>();
    private Thread writerThread;

    // 统计
    private final AtomicLong totalRecords = new AtomicLong(0);
    private final AtomicInteger droppedRecords = new AtomicInteger(0);

    // behave.log 文件写入（JSONL 格式：每行一条记录）
    private final Path logFilePath;
    private volatile BufferedWriter logWriter;
    private static final long LOG_FLUSH_INTERVAL_MS = 10_000;  // 定时刷盘间隔：10秒
    private final AtomicLong lastLogFlushMs = new AtomicLong(0);

    // 前一tick位置缓存（用于计算移动状态和速度）
    private final ConcurrentHashMap<String, Vec3d> lastPositions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastPositionTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastInventoryTimes = new ConcurrentHashMap<>();

    public PlayerBehaviorRecorder(MinecraftServer server) {
        this(server, DEFAULT_HIGH_FREQ_INTERVAL, DEFAULT_LOW_FREQ_INTERVAL, DEFAULT_MAX_HISTORY_PER_PLAYER);
    }

    public PlayerBehaviorRecorder(MinecraftServer server, long highFreqIntervalMs, long lowFreqIntervalMs, int maxHistoryPerPlayer) {
        this.server = server;
        this.highFreqIntervalMs = highFreqIntervalMs;
        this.lowFreqIntervalMs = lowFreqIntervalMs;
        this.maxHistoryPerPlayer = maxHistoryPerPlayer;
        this.logFilePath = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("config").resolve("MCPServer").resolve("behave.log");
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        // 打开 behave.log（追加模式，服务器重启后继续追加）
        try {
            Files.createDirectories(logFilePath.getParent());
            logWriter = Files.newBufferedWriter(logFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            lastLogFlushMs.set(System.currentTimeMillis());
            LOGGER.info("========================================");
            LOGGER.info("[BehaviorRecorder] 日志文件路径: {}", logFilePath.toAbsolutePath());
            LOGGER.info("[BehaviorRecorder] 每 10 秒自动刷盘，服务器停止时确保落盘");
            LOGGER.info("========================================");
        } catch (Exception e) {
            LOGGER.warn("Failed to open behavior log file '{}': {}", logFilePath.toAbsolutePath(), e.getMessage());
            logWriter = null;
        }

        recorderThread = new Thread(this::recorderLoop, "MCPServer-BehaviorRecorder");
        recorderThread.setDaemon(true);
        recorderThread.start();

        writerThread = new Thread(this::writerLoop, "MCPServer-BehaviorWriter");
        writerThread.setDaemon(true);
        writerThread.start();

        LOGGER.info("PlayerBehaviorRecorder started (highFreq={}ms, lowFreq={}ms, maxHistory={})",
                highFreqIntervalMs, lowFreqIntervalMs, maxHistoryPerPlayer);
    }

    public synchronized void stop() {
        running = false;
        if (recorderThread != null) recorderThread.interrupt();
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        LOGGER.info("PlayerBehaviorRecorder stopped. Total records: {}", totalRecords.get());
    }

    // ==================== 玩家管理 ====================

    public void trackAllPlayers(boolean enable) {
        this.trackAllPlayers = enable;
        LOGGER.info("Track all players: {}", enable);
    }

    public boolean isTrackingAllPlayers() {
        return trackAllPlayers;
    }

    public boolean addTrackingPlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        String key = playerName.toLowerCase(Locale.ROOT);
        TrackingConfig cfg = trackedPlayers.computeIfAbsent(key, k -> new TrackingConfig(playerName));
        trackedPlayerNames.add(playerName);
        if (!history.containsKey(key)) {
            history.put(key, new ConcurrentLinkedDeque<>());
        }
        LOGGER.info("Added tracking for player: {}", playerName);
        return true;
    }

    public boolean removeTrackingPlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        String key = playerName.toLowerCase(Locale.ROOT);
        trackedPlayers.remove(key);
        trackedPlayerNames.remove(playerName);
        lastPositions.remove(key);
        lastPositionTimes.remove(key);
        lastInventoryTimes.remove(key);
        LOGGER.info("Removed tracking for player: {}", playerName);
        return true;
    }

    public Set<String> getTrackedPlayers() {
        Set<String> result = new HashSet<>(trackedPlayerNames);
        if (trackAllPlayers && server != null) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                result.add(p.getName().getString());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public void clearAllTracking() {
        trackedPlayers.clear();
        trackedPlayerNames.clear();
        trackAllPlayers = false;
        lastPositions.clear();
        lastPositionTimes.clear();
        lastInventoryTimes.clear();
        LOGGER.info("Cleared all player tracking");
    }

    public void clearHistory(String playerName) {
        if (playerName == null) {
            history.clear();
            totalRecords.set(0);
            LOGGER.info("Cleared all history");
        } else {
            String key = playerName.toLowerCase(Locale.ROOT);
            ConcurrentLinkedDeque<PlayerBehaviorRecord> q = history.get(key);
            if (q != null) {
                totalRecords.addAndGet(-q.size());
                q.clear();
            }
            LOGGER.info("Cleared history for player: {}", playerName);
        }
    }

    // ==================== 采样循环 ====================

    private void recorderLoop() {
        long nextHighFreq = System.currentTimeMillis();
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                long now = System.currentTimeMillis();
                if (now >= nextHighFreq) {
                    collectAllPlayers(now);
                    nextHighFreq = now + highFreqIntervalMs;
                }
                // 短睡眠，避免CPU空转
                long sleepMs = Math.max(1, nextHighFreq - System.currentTimeMillis());
                if (sleepMs > 0) Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.warn("Behavior recorder loop error: {}", e.getMessage());
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void collectAllPlayers(long now) {
        if (server == null) return;
        List<ServerPlayerEntity> playersToCheck = new ArrayList<>();

        if (trackAllPlayers) {
            playersToCheck.addAll(server.getPlayerManager().getPlayerList());
        } else {
            for (String name : trackedPlayerNames) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
                if (p != null) playersToCheck.add(p);
            }
        }

        for (ServerPlayerEntity player : playersToCheck) {
            try {
                collectPlayer(player, now);
            } catch (Exception e) {
                LOGGER.warn("Failed to collect data for player {}: {}",
                        player.getName().getString(), e.getMessage());
            }
        }
    }

    private void collectPlayer(ServerPlayerEntity player, long now) {
        String playerName = player.getName().getString();
        String key = playerName.toLowerCase(Locale.ROOT);

        // 初始化历史队列
        history.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        // 基础状态信息
        float health = player.getHealth();
        float maxHealth = player.getMaxHealth();
        int foodLevel = player.getHungerManager().getFoodLevel();
        float saturation = player.getHungerManager().getSaturationLevel();

        // 位置数据
        Vec3d pos = MCCompat.getEntityPos(player);
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        String dimension = MCCompat.getEntityWorld(player).getRegistryKey().getValue().toString();

        // 移动属性：计算速度和是否在移动
        Vec3d lastPos = lastPositions.get(key);
        Long lastTime = lastPositionTimes.get(key);
        double movementSpeed = 0;
        boolean isMoving = false;
        if (lastPos != null && lastTime != null) {
            long deltaMs = now - lastTime;
            if (deltaMs > 0) {
                double dx = pos.x - lastPos.x;
                double dy = pos.y - lastPos.y;
                double dz = pos.z - lastPos.z;
                double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                // blocks per second
                movementSpeed = dist * 1000.0 / deltaMs;
                isMoving = dist > 0.01; // 微小阈值避免抖动
            }
        }
        lastPositions.put(key, pos);
        lastPositionTimes.put(key, now);

        boolean onGround = player.isOnGround();
        boolean sprinting = player.isSprinting();
        boolean sneaking = player.isSneaking();
        boolean flying = player.getAbilities().flying;

        // 手持物品
        ItemStack mainHand = player.getEquippedStack(EquipmentSlot.MAINHAND);
        ItemStack offHand = player.getEquippedStack(EquipmentSlot.OFFHAND);
        String mainId = getItemId(mainHand);
        String offId = getItemId(offHand);
        int mainCount = mainHand.isEmpty() ? 0 : mainHand.getCount();
        int offCount = offHand.isEmpty() ? 0 : offHand.getCount();

        // 脚下方块
        BlockPos feetPos = player.getBlockPos().down();
        String blockBelowId = "minecraft:air";
        try {
            net.minecraft.block.BlockState state = MCCompat.getEntityWorld(player).getBlockState(feetPos);
            net.minecraft.block.Block block = state.getBlock();
            try {
                Object entry = block.getRegistryEntry();
                Method getId = entry.getClass().getMethod("getId");
                Identifier bid = (Identifier) getId.invoke(entry);
                if (bid != null) blockBelowId = bid.toString();
            } catch (Exception ignored) {
                blockBelowId = block.getTranslationKey();
            }
        } catch (Exception ignored) {}

        // 背包内容（低频采样）
        Long lastInv = lastInventoryTimes.get(key);
        boolean sampleInventory = lastInv == null || (now - lastInv) >= lowFreqIntervalMs;
        List<ItemEntry> inventorySnapshot = null;
        if (sampleInventory) {
            inventorySnapshot = collectInventory(player);
            lastInventoryTimes.put(key, now);
        }

        PlayerBehaviorRecord record = new PlayerBehaviorRecord(
                now, playerName, player.getUuidAsString(),
                health, maxHealth, foodLevel, saturation,
                pos.x, pos.y, pos.z, yaw, pitch, dimension,
                movementSpeed, isMoving, onGround, sprinting, sneaking, flying,
                mainId, mainCount, offId, offCount,
                blockBelowId,
                inventorySnapshot
        );

        // 提交到异步写入队列
        writeQueue.offer(record);
    }

    private List<ItemEntry> collectInventory(ServerPlayerEntity player) {
        List<ItemEntry> result = new ArrayList<>();
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();

        // 主背包（含快捷栏）
        List<ItemStack> mainInv = MCCompat.getInvMain(inv);
        for (int i = 0; i < mainInv.size(); i++) {
            ItemStack stack = mainInv.get(i);
            if (!stack.isEmpty()) {
                result.add(new ItemEntry(getItemId(stack), i, stack.getCount(),
                        stack.getDamage(), stack.getMaxDamage()));
            }
        }
        // 装备栏: 36=脚 37=腿 38=胸 39=头
        List<ItemStack> armorInv = MCCompat.getInvArmor(inv);
        for (int i = 0; i < armorInv.size(); i++) {
            ItemStack stack = armorInv.get(i);
            if (!stack.isEmpty()) {
                result.add(new ItemEntry(getItemId(stack), 36 + i, stack.getCount(),
                        stack.getDamage(), stack.getMaxDamage()));
            }
        }
        // 副手：45
        ItemStack offhand = MCCompat.getInvOffHand(inv).get(0);
        if (!offhand.isEmpty()) {
            result.add(new ItemEntry(getItemId(offhand), 45, offhand.getCount(),
                    offhand.getDamage(), offhand.getMaxDamage()));
        }
        return result;
    }

    private String getItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "minecraft:air";
        try {
            Object item = stack.getItem();
            Method getReg = item.getClass().getMethod("getRegistryEntry");
            Object entry = getReg.invoke(item);
            Method getId = entry.getClass().getMethod("getId");
            Identifier id = (Identifier) getId.invoke(entry);
            if (id != null) return id.toString();
        } catch (Exception ignored) {}
        try {
            Class<?> reg = Class.forName("net.minecraft.registry.Registries");
            java.lang.reflect.Field f = reg.getDeclaredField("ITEM");
            Object itemRegistry = f.get(null);
            Method getId = itemRegistry.getClass().getMethod("getId", Object.class);
            Identifier id = (Identifier) getId.invoke(itemRegistry, stack.getItem());
            if (id != null) return id.toString();
        } catch (Exception ignored) {}
        try {
            String k = MCCompat.itemTranslationKey(stack);
            if (k != null) {
                String[] parts = k.split("\\.");
                if (parts.length >= 3) return parts[1] + ":" + parts[2];
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    // ==================== 异步写入循环 ====================

    private void writerLoop() {
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                try {
                    PlayerBehaviorRecord record = writeQueue.poll();
                    if (record == null) {
                        // 即使没有新记录，也检查一下是否该定时刷盘
                        maybePeriodicFlush();
                        Thread.sleep(10);
                        continue;
                    }
                    String key = record.playerName.toLowerCase(Locale.ROOT);
                    ConcurrentLinkedDeque<PlayerBehaviorRecord> q =
                            history.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

                    q.offerLast(record);
                    long count = totalRecords.incrementAndGet();

                    // 限制历史长度
                    while (q.size() > maxHistoryPerPlayer) {
                        q.pollFirst();
                        droppedRecords.incrementAndGet();
                    }

                    // 追加写入 behave.log（JSONL 格式，每行一条）
                    if (logWriter != null) {
                        try {
                            logWriter.write(recordToJson(record).toString());
                            logWriter.write('\n');
                            if (count % 100 == 0) {
                                logWriter.flush();
                                lastLogFlushMs.set(System.currentTimeMillis());
                            } else {
                                maybePeriodicFlush();
                            }
                        } catch (Exception ex) {
                            LOGGER.warn("Failed to write behavior log: {}", ex.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.warn("Writer loop error: {}", e.getMessage());
                }
            }
            // 停止后清空队列里还没写入的
            writeQueue.clear();
        } finally {
            // 关闭日志文件
            if (logWriter != null) {
                try {
                    logWriter.flush();
                    logWriter.close();
                } catch (Exception e) {
                    LOGGER.warn("Failed to close behavior log: {}", e.getMessage());
                }
                logWriter = null;
            }
        }
    }

    // ==================== 查询 API ====================

    public List<PlayerBehaviorRecord> queryHistory(String playerName, Long fromTimestamp, Long toTimestamp, Integer limit) {
        String key = playerName.toLowerCase(Locale.ROOT);
        ConcurrentLinkedDeque<PlayerBehaviorRecord> q = history.get(key);
        if (q == null) return Collections.emptyList();

        List<PlayerBehaviorRecord> result = new ArrayList<>();
        long from = fromTimestamp == null ? 0 : fromTimestamp;
        long to = toTimestamp == null ? Long.MAX_VALUE : toTimestamp;
        int maxCount = limit == null ? Integer.MAX_VALUE : limit;

        // 队列是按时间有序的，从头遍历
        for (PlayerBehaviorRecord r : q) {
            if (r.timestamp < from) continue;
            if (r.timestamp > to) break;
            result.add(r);
            if (result.size() >= maxCount) break;
        }
        return result;
    }

    public PlayerBehaviorRecord getLatestRecord(String playerName) {
        String key = playerName.toLowerCase(Locale.ROOT);
        ConcurrentLinkedDeque<PlayerBehaviorRecord> q = history.get(key);
        if (q == null) return null;
        return q.peekLast();
    }

    public JsonObject getStatus() {
        JsonObject result = new JsonObject();
        result.addProperty("running", running);
        result.addProperty("trackAllPlayers", trackAllPlayers);

        JsonArray tracked = new JsonArray();
        for (String name : trackedPlayerNames) tracked.add(name);
        result.add("trackedPlayers", tracked);

        JsonObject stats = new JsonObject();
        stats.addProperty("totalRecords", totalRecords.get());
        stats.addProperty("droppedRecords", droppedRecords.get());
        stats.addProperty("writeQueueSize", writeQueue.size());

        JsonObject perPlayer = new JsonObject();
        for (Map.Entry<String, ConcurrentLinkedDeque<PlayerBehaviorRecord>> e : history.entrySet()) {
            perPlayer.addProperty(e.getKey(), e.getValue().size());
        }
        stats.add("historySizes", perPlayer);
        result.add("stats", stats);

        JsonObject cfg = new JsonObject();
        cfg.addProperty("highFreqIntervalMs", highFreqIntervalMs);
        cfg.addProperty("lowFreqIntervalMs", lowFreqIntervalMs);
        cfg.addProperty("maxHistoryPerPlayer", maxHistoryPerPlayer);
        result.add("config", cfg);

        return result;
    }

    public JsonObject exportRecordsToJson(List<PlayerBehaviorRecord> records) {
        JsonObject result = new JsonObject();
        result.addProperty("count", records.size());
        JsonArray arr = new JsonArray();
        for (PlayerBehaviorRecord r : records) arr.add(recordToJson(r));
        result.add("records", arr);
        return result;
    }

    public String exportRecordsToCsv(List<PlayerBehaviorRecord> records, boolean includeInventory) {
        StringBuilder sb = new StringBuilder();
        // 表头
        sb.append("timestamp,playerName,playerUuid,health,maxHealth,foodLevel,saturation,");
        sb.append("posX,posY,posZ,yaw,pitch,dimension,");
        sb.append("movementSpeed,isMoving,isOnGround,isSprinting,isSneaking,isFlying,");
        sb.append("mainHandItemId,mainHandItemCount,offHandItemId,offHandItemCount,blockBelowId");
        if (includeInventory) sb.append(",inventorySnapshotCount");
        sb.append("\n");

        for (PlayerBehaviorRecord r : records) {
            sb.append(r.timestamp).append(',');
            sb.append(escapeCsv(r.playerName)).append(',');
            sb.append(r.playerUuid).append(',');
            sb.append(r.health).append(',').append(r.maxHealth).append(',');
            sb.append(r.foodLevel).append(',').append(r.saturationLevel).append(',');
            sb.append(r.posX).append(',').append(r.posY).append(',').append(r.posZ).append(',');
            sb.append(r.yaw).append(',').append(r.pitch).append(',');
            sb.append(escapeCsv(r.dimension)).append(',');
            sb.append(String.format("%.6f", r.movementSpeed)).append(',');
            sb.append(r.isMoving).append(',').append(r.isOnGround).append(',');
            sb.append(r.isSprinting).append(',').append(r.isSneaking).append(',').append(r.isFlying).append(',');
            sb.append(escapeCsv(r.mainHandItemId)).append(',').append(r.mainHandItemCount).append(',');
            sb.append(escapeCsv(r.offHandItemId)).append(',').append(r.offHandItemCount).append(',');
            sb.append(escapeCsv(r.blockBelowId));
            if (includeInventory) {
                sb.append(',').append(r.inventorySnapshot == null ? "" : r.inventorySnapshot.size());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static JsonObject recordToJson(PlayerBehaviorRecord r) {
        JsonObject j = new JsonObject();
        j.addProperty("timestamp", r.timestamp);
        j.addProperty("playerName", r.playerName);
        j.addProperty("playerUuid", r.playerUuid);

        JsonObject status = new JsonObject();
        status.addProperty("health", r.health);
        status.addProperty("maxHealth", r.maxHealth);
        status.addProperty("healthPercent", r.maxHealth > 0 ? (r.health / r.maxHealth * 100) : 0);
        status.addProperty("foodLevel", r.foodLevel);
        status.addProperty("saturationLevel", r.saturationLevel);
        j.add("status", status);

        JsonObject pos = new JsonObject();
        pos.addProperty("x", r.posX);
        pos.addProperty("y", r.posY);
        pos.addProperty("z", r.posZ);
        pos.addProperty("yaw", r.yaw);
        pos.addProperty("pitch", r.pitch);
        pos.addProperty("dimension", r.dimension);
        j.add("position", pos);

        JsonObject move = new JsonObject();
        move.addProperty("movementSpeed", r.movementSpeed);
        move.addProperty("isMoving", r.isMoving);
        move.addProperty("isOnGround", r.isOnGround);
        move.addProperty("isSprinting", r.isSprinting);
        move.addProperty("isSneaking", r.isSneaking);
        move.addProperty("isFlying", r.isFlying);
        j.add("movement", move);

        JsonObject interact = new JsonObject();
        JsonObject mh = new JsonObject();
        mh.addProperty("id", r.mainHandItemId);
        mh.addProperty("count", r.mainHandItemCount);
        interact.add("mainHand", mh);
        JsonObject oh = new JsonObject();
        oh.addProperty("id", r.offHandItemId);
        oh.addProperty("count", r.offHandItemCount);
        interact.add("offHand", oh);
        interact.addProperty("blockBelow", r.blockBelowId);
        j.add("interaction", interact);

        if (r.inventorySnapshot != null) {
            JsonArray inv = new JsonArray();
            for (ItemEntry ie : r.inventorySnapshot) {
                JsonObject ij = new JsonObject();
                ij.addProperty("id", ie.itemId);
                ij.addProperty("slot", ie.slot);
                ij.addProperty("count", ie.count);
                if (ie.maxDamage > 0) {
                    ij.addProperty("damage", ie.damage);
                    ij.addProperty("maxDamage", ie.maxDamage);
                    ij.addProperty("durabilityPercent", (ie.maxDamage - ie.damage) * 100.0 / ie.maxDamage);
                }
                inv.add(ij);
            }
            j.add("inventory", inv);
        }

        return j;
    }

    /** 每 10 秒至少刷盘一次，即使记录数没到 100 条。即使 0 条记录也会触发。 */
    private void maybePeriodicFlush() {
        if (logWriter == null) return;
        long now = System.currentTimeMillis();
        long last = lastLogFlushMs.get();
        if (now - last >= LOG_FLUSH_INTERVAL_MS) {
            if (lastLogFlushMs.compareAndSet(last, now)) {
                try {
                    logWriter.flush();
                } catch (Exception e) {
                    LOGGER.warn("Periodic log flush failed: {}", e.getMessage());
                }
            }
        }
    }

    private static class TrackingConfig {
        final String displayName;
        TrackingConfig(String displayName) { this.displayName = displayName; }
    }
}
