package org.du.mcpserver.command;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.du.mcpserver.Mcpserver;
import org.du.mcpserver.monitor.behavior.PlayerBehaviorRecorder;
import org.du.mcpserver.monitor.behavior.PlayerBehaviorRecord;
import org.du.mcpserver.util.VersionCompat;

import java.util.List;
import java.util.Set;

public class BehaviorRecorderCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("behavior")
                        .requires(source -> VersionCompat.hasPermission(source, 2) || !source.isExecutedByPlayer())
                        .then(
                                CommandManager.literal("status")
                                        .executes(BehaviorRecorderCommand::executeStatus)
                        )
                        .then(
                                CommandManager.literal("track")
                                        .then(
                                                CommandManager.argument("player", StringArgumentType.greedyString())
                                                        .executes(BehaviorRecorderCommand::executeTrack)
                                        )
                        )
                        .then(
                                CommandManager.literal("untrack")
                                        .then(
                                                CommandManager.argument("player", StringArgumentType.greedyString())
                                                        .executes(BehaviorRecorderCommand::executeUntrack)
                                        )
                        )
                        .then(
                                CommandManager.literal("clear")
                                        .executes(ctx -> executeClear(ctx, null))
                                        .then(
                                                CommandManager.argument("player", StringArgumentType.word())
                                                        .executes(ctx -> executeClear(ctx, StringArgumentType.getString(ctx, "player")))
                                        )
                        )
                        .then(
                                CommandManager.literal("latest")
                                        .then(
                                                CommandManager.argument("player", StringArgumentType.word())
                                                        .executes(BehaviorRecorderCommand::executeLatest)
                                        )
                        )
                        .then(
                                CommandManager.literal("query")
                                        .then(
                                                CommandManager.argument("player", StringArgumentType.word())
                                                        .then(
                                                                CommandManager.argument("limit", IntegerArgumentType.integer(1, 1000))
                                                                        .executes(BehaviorRecorderCommand::executeQuery)
                                                        )
                                                        .executes(ctx -> executeQueryLimit(ctx, 50))
                                        )
                        )
        );
    }

    private static PlayerBehaviorRecorder getRecorder() {
        Mcpserver inst = Mcpserver.getInstance();
        return inst != null ? inst.getBehaviorRecorder() : null;
    }

    private static int executeStatus(CommandContext<ServerCommandSource> ctx) {
        PlayerBehaviorRecorder br = getRecorder();
        if (br == null) {
            VersionCompat.sendError(ctx.getSource(), VersionCompat.literal("Behavior Recorder 未初始化"));
            return 0;
        }
        JsonObject status = br.getStatus();
        Set<String> tracked = br.getTrackedPlayers();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 行为记录器状态 ===\n");
        sb.append("运行中: ").append(status.get("running").getAsBoolean()).append('\n');
        sb.append("全体监测: ").append(status.get("trackAllPlayers").getAsBoolean()).append('\n');
        sb.append("监测玩家: [");
        int i = 0;
        for (String n : tracked) {
            if (i > 0) sb.append(", ");
            sb.append(n);
            i++;
        }
        sb.append("]\n");
        JsonObject stats = status.getAsJsonObject("stats");
        sb.append("总记录数: ").append(stats.get("totalRecords").getAsLong()).append('\n');
        sb.append("丢弃记录: ").append(stats.get("droppedRecords").getAsInt()).append('\n');
        VersionCompat.sendFeedback(ctx.getSource(), VersionCompat.literal(sb.toString()));
        return 1;
    }

    private static int executeTrack(CommandContext<ServerCommandSource> ctx) {
        PlayerBehaviorRecorder br = getRecorder();
        if (br == null) {
            VersionCompat.sendError(ctx.getSource(), VersionCompat.literal("Behavior Recorder 未初始化"));
            return 0;
        }
        String player = StringArgumentType.getString(ctx, "player");
        if ("*".equals(player)) {
            br.trackAllPlayers(true);
            VersionCompat.sendFeedback(ctx.getSource(), VersionCompat.literal("已开启全体在线玩家监测模式"));
        } else if ("!all".equals(player)) {
            br.clearAllTracking();
            VersionCompat.sendFeedback(ctx.getSource(), VersionCompat.literal("已清空所有监测规则"));
        } else {
            br.addTrackingPlayer(player);
            VersionCompat.sendFeedback(ctx.getSource(), VersionCompat.literal("已添加监测：" + player));
        }
        return 1;
    }

    private static int executeUntrack(CommandContext<ServerCommandSource> ctx) {
        PlayerBehaviorRecorder br = getRecorder();
        if (br == null) {
            VersionCompat.sendError(ctx.getSource(), VersionCompat.literal("Behavior Recorder 未初始化"));
            return 0;
        }
        String player = StringArgumentType.getString(ctx, "player");
        if ("!all".equals(player)) {
            br.clearAllTracking();
            VersionCompat.sendFeedback(ctx.getSource(), VersionCompat.literal("已清空所有监测规则"));
        } else {
            br.removeTrackingPlayer(player);
            VersionCompat.sendFeedback(ctx.getSource(), VersionCompat.literal("已停止监测：" + player));
        }
        return 1;
    }

    private static int executeClear(CommandContext<ServerCommandSource> ctx, String player) {
        PlayerBehaviorRecorder br = getRecorder();
        if (br == null) {
            VersionCompat.sendError(ctx.getSource(), VersionCompat.literal("Behavior Recorder 未初始化"));
            return 0;
        }
        br.clearHistory(player);
        VersionCompat.sendFeedback(ctx.getSource(),
                VersionCompat.literal(player == null ? "已清空全部历史" : "已清空历史：" + player));
        return 1;
    }

    private static int executeLatest(CommandContext<ServerCommandSource> ctx) {
        PlayerBehaviorRecorder br = getRecorder();
        if (br == null) {
            VersionCompat.sendError(ctx.getSource(), VersionCompat.literal("Behavior Recorder 未初始化"));
            return 0;
        }
        String player = StringArgumentType.getString(ctx, "player");
        PlayerBehaviorRecord latest = br.getLatestRecord(player);
        if (latest == null) {
            VersionCompat.sendError(ctx.getSource(), VersionCompat.literal("该玩家暂无记录"));
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(player).append(" 最新记录 (ts=").append(latest.timestamp).append(") ===\n");
        sb.append("HP: ").append(String.format("%.1f/%.1f", latest.health, latest.maxHealth));
        sb.append("  饱食: ").append(latest.foodLevel).append("  饱和度: ").append(String.format("%.1f", latest.saturationLevel)).append('\n');
        sb.append(String.format("位置: (%.2f, %.2f, %.2f) in %s\n", latest.posX, latest.posY, latest.posZ, latest.dimension));
        sb.append(String.format("朝向: yaw=%.1f pitch=%.1f\n", latest.yaw, latest.pitch));
        sb.append(String.format("速度: %.4f blocks/s 移动:%s 疾跑:%s 潜行:%s 飞行:%s\n",
                latest.movementSpeed, latest.isMoving, latest.isSprinting, latest.isSneaking, latest.isFlying));
        sb.append("主手: ").append(latest.mainHandItemId).append(" x").append(latest.mainHandItemCount);
        sb.append("  副手: ").append(latest.offHandItemId).append(" x").append(latest.offHandItemCount).append('\n');
        sb.append("脚下方块: ").append(latest.blockBelowId).append('\n');
        if (latest.inventorySnapshot != null) {
            sb.append("背包物品数: ").append(latest.inventorySnapshot.size());
        } else {
            sb.append("背包快照: 本轮未采样");
        }
        VersionCompat.sendFeedback(ctx.getSource(), VersionCompat.literal(sb.toString()));
        return 1;
    }

    private static int executeQuery(CommandContext<ServerCommandSource> ctx) {
        int limit = IntegerArgumentType.getInteger(ctx, "limit");
        return executeQueryLimit(ctx, limit);
    }

    private static int executeQueryLimit(CommandContext<ServerCommandSource> ctx, int limit) {
        PlayerBehaviorRecorder br = getRecorder();
        if (br == null) {
            VersionCompat.sendError(ctx.getSource(), VersionCompat.literal("Behavior Recorder 未初始化"));
            return 0;
        }
        String player = StringArgumentType.getString(ctx, "player");
        List<PlayerBehaviorRecord> records = br.queryHistory(player, null, null, limit);
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(player).append(" 历史记录 (").append(records.size()).append(" 条) ===\n");
        for (PlayerBehaviorRecord r : records) {
            sb.append(String.format("[ts=%d] HP=%.1f/%.1f Food=%d Pos=(%.1f,%.1f,%.1f) Speed=%.3f%s %s\n",
                    r.timestamp, r.health, r.maxHealth, r.foodLevel,
                    r.posX, r.posY, r.posZ,
                    r.movementSpeed,
                    r.isMoving ? " [MOVE]" : "",
                    r.mainHandItemId
            ));
        }
        VersionCompat.sendFeedback(ctx.getSource(), VersionCompat.literal(sb.toString()));
        return 1;
    }
}
