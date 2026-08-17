package org.du.mcpserver.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.du.mcpserver.Mcpserver;
import org.du.mcpserver.util.ConfigManager;
import org.du.mcpserver.util.SecurityUtils;
import org.du.mcpserver.util.MCCompat;
import org.du.mcpserver.util.ShellExecutor;
import org.du.mcpserver.util.VersionCompat;

public class MCPServerCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                CommandManager.literal("mcpserver")
                        .requires(source -> VersionCompat.hasPermission(source, 4) || !source.isExecutedByPlayer())
                        .then(
                                CommandManager.literal("token")
                                        .executes(MCPServerCommand::executeShowToken)
                        )
                        .then(
                                CommandManager.literal("newtoken")
                                        .executes(MCPServerCommand::executeNewToken)
                        )
                        .then(
                                CommandManager.literal("tokenmode")
                                        .executes(MCPServerCommand::executeShowTokenMode)
                                        .then(
                                                CommandManager.argument("mode", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            builder.suggest("auto");
                                                            builder.suggest("persistent");
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(MCPServerCommand::executeSetTokenMode)
                                        )
                        )
                        .then(
                                CommandManager.literal("reload")
                                        .executes(MCPServerCommand::executeReload)
                        )
                        .then(
                                CommandManager.literal("shell")
                                        .then(
                                                CommandManager.literal("status")
                                                        .executes(MCPServerCommand::executeShellStatus)
                                        )
                                        .then(
                                                CommandManager.literal("enable")
                                                        .executes(ctx -> executeShellToggle(ctx, true))
                                        )
                                        .then(
                                                CommandManager.literal("disable")
                                                        .executes(ctx -> executeShellToggle(ctx, false))
                                        )
                                        .then(
                                                CommandManager.literal("timeout")
                                                        .then(
                                                                CommandManager.argument("ms", IntegerArgumentType.integer(1000, 3600000))
                                                                        .executes(MCPServerCommand::executeShellTimeout)
                                                        )
                                        )
                                        .then(
                                                CommandManager.argument("command", StringArgumentType.greedyString())
                                                        .executes(MCPServerCommand::executeShellRun)
                                        )
                        )
        );
    }

    /** 创建可点击复制的 token Text 组件 */
    private static Text clickableToken(String token) {
        return Text.literal(token)
                .styled(style -> {
                    ClickEvent ce = MCCompat.clickEvent(token);
                    if (ce != null) style = style.withClickEvent(ce);
                    HoverEvent he = MCCompat.hoverEvent(Text.literal("点击复制到剪贴板"));
                    if (he != null) style = style.withHoverEvent(he);
                    return style
                            .withColor(Formatting.AQUA)
                            .withUnderline(true);
                });
    }

    private static int executeShowToken(CommandContext<ServerCommandSource> context) {
        String currentToken = Mcpserver.getInstance().getApiKey();
        Text message = Text.literal("§6当前 Token §7(点击下方文本可复制)§r:\n")
                .append(clickableToken(currentToken));
        VersionCompat.sendFeedback(context.getSource(), message);
        return 1;
    }

    private static int executeNewToken(CommandContext<ServerCommandSource> context) {
        try {
            String newToken = SecurityUtils.generateApiKey();
            Mcpserver.getInstance().setApiKey(newToken);

            ConfigManager cm = Mcpserver.getInstance().getConfigManager();
            String mode = cm != null ? cm.getTokenMode() : "auto";
            String modeDesc = "persistent".equals(mode) ? "§a(已保存到 config.json)" : "§7(重启后会变化)";

            Text message = Text.literal("§a新 Token 已生成 §7[模式: " + mode + "] " + modeDesc + "§r\n")
                    .append(Text.literal("§7(点击下方文本可复制)§r:\n"))
                    .append(clickableToken(newToken));
            VersionCompat.sendFeedback(context.getSource(), message);

            return 1;
        } catch (Exception e) {
            VersionCompat.sendError(context.getSource(),
                    Text.literal("§c生成新 Token 失败: " + e.getMessage()));
            return 0;
        }
    }

    private static int executeShowTokenMode(CommandContext<ServerCommandSource> context) {
        ConfigManager cm = Mcpserver.getInstance().getConfigManager();
        String mode = cm != null ? cm.getTokenMode() : "auto";
        String desc = "persistent".equals(mode)
                ? "§a常驻模式 §7(重启后 token 保持不变)"
                : "§b自动变化模式 §7(每次重启生成新 token)";
        VersionCompat.sendFeedback(context.getSource(),
                Text.literal("§6当前 Token 模式: " + desc));
        VersionCompat.sendFeedback(context.getSource(),
                Text.literal("§7使用 §f/mcpserver tokenmode <auto|persistent> §7切换模式"));
        return 1;
    }

    private static int executeReload(CommandContext<ServerCommandSource> context) {
        Mcpserver inst = Mcpserver.getInstance();
        ConfigManager cm = inst.getConfigManager();
        if (cm == null) {
            VersionCompat.sendError(context.getSource(), Text.literal("§c配置管理器未初始化"));
            return 0;
        }

        // 记录重载前的值用于对比
        int oldPort = inst.getHttpPort();
        boolean oldSsl = cm.isSslEnabled();

        // 重新加载 config.json
        cm.reload();

        StringBuilder sb = new StringBuilder();
        sb.append("§a配置已从 config.json 重新加载§r\n");
        sb.append("§7──────────────────────────§r\n");
        sb.append("§6fileEditor: §f").append(cm.isFileEditorEnabled()).append('\n');
        sb.append("§6httpPort: §f").append(cm.getHttpPort());
        if (cm.getHttpPort() != oldPort) sb.append(" §c(已变更，需重启服务器生效)");
        sb.append('\n');
        sb.append("§6SSL: §f").append(cm.isSslEnabled());
        if (cm.isSslEnabled() != oldSsl) sb.append(" §c(已变更，需重启服务器生效)");
        sb.append('\n');
        sb.append("§6tokenMode: §f").append(cm.getTokenMode());

        VersionCompat.sendFeedback(context.getSource(), Text.literal(sb.toString()));
        return 1;
    }

    private static int executeSetTokenMode(CommandContext<ServerCommandSource> context) {
        String mode = StringArgumentType.getString(context, "mode");
        ConfigManager cm = Mcpserver.getInstance().getConfigManager();
        if (cm == null) {
            VersionCompat.sendError(context.getSource(),
                    Text.literal("§c配置管理器未初始化"));
            return 0;
        }
        try {
            cm.setTokenMode(mode);
            String desc = "persistent".equals(mode)
                    ? "§a常驻模式 §7(当前 token 已保存，重启后保持不变)"
                    : "§b自动变化模式 §7(下次重启将生成新 token)";
            VersionCompat.sendFeedback(context.getSource(),
                    Text.literal("§aToken 模式已切换为: " + desc));

            // 切换到 persistent 模式时，保存当前 token
            if ("persistent".equals(mode)) {
                cm.setPersistentToken(Mcpserver.getInstance().getApiKey());
                VersionCompat.sendFeedback(context.getSource(),
                        Text.literal("§a当前 Token 已保存到 config.json"));
            }
            return 1;
        } catch (IllegalArgumentException e) {
            VersionCompat.sendError(context.getSource(),
                    Text.literal("§c无效的模式: " + mode + "§7，请使用 §fauto §7或 §fpersistent"));
            return 0;
        }
    }

    private static int executeShellStatus(CommandContext<ServerCommandSource> context) {
        ConfigManager cm = Mcpserver.getInstance().getConfigManager();
        if (cm == null) {
            VersionCompat.sendError(context.getSource(), Text.literal("§c配置管理器未初始化"));
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== Shell 执行器状态 ===\n");
        sb.append("启用状态: ").append(cm.isShellEnabled() ? "§a已启用 ⚠ 高危§r" : "§7已禁用§r").append('\n');
        sb.append("超时时间: §f").append(cm.getShellTimeoutMs()).append("§7 ms (约 §f")
                .append(String.format("%.1f", cm.getShellTimeoutMs() / 1000.0)).append("§7 秒)§r\n");
        sb.append("\n§7子命令: /mcpserver shell <enable|disable|status|timeout <ms>|命令>\n");
        sb.append("§7示例: /mcpserver shell dir /b\n");
        VersionCompat.sendFeedback(context.getSource(), Text.literal(sb.toString()));
        return 1;
    }

    private static int executeShellToggle(CommandContext<ServerCommandSource> context, boolean enable) {
        ConfigManager cm = Mcpserver.getInstance().getConfigManager();
        if (cm == null) {
            VersionCompat.sendError(context.getSource(), Text.literal("§c配置管理器未初始化"));
            return 0;
        }
        cm.setShellEnabled(enable);
        if (enable) {
            VersionCompat.sendFeedback(context.getSource(),
                    Text.literal("§a⚠ Shell 执行器已启用！AI 将能执行系统级命令，请注意安全风险。§r\n"
                            + "§7可随时用 §f/mcpserver shell disable §7关闭。"));
        } else {
            VersionCompat.sendFeedback(context.getSource(),
                    Text.literal("§7Shell 执行器已关闭"));
        }
        return 1;
    }

    private static int executeShellTimeout(CommandContext<ServerCommandSource> context) {
        ConfigManager cm = Mcpserver.getInstance().getConfigManager();
        if (cm == null) {
            VersionCompat.sendError(context.getSource(), Text.literal("§c配置管理器未初始化"));
            return 0;
        }
        int ms = IntegerArgumentType.getInteger(context, "ms");
        try {
            cm.setShellTimeoutMs(ms);
            VersionCompat.sendFeedback(context.getSource(),
                    Text.literal("§aShell 超时已更新为: " + ms + " ms (约 " + String.format("%.1f", ms / 1000.0) + " 秒)"));
            return 1;
        } catch (IllegalArgumentException e) {
            VersionCompat.sendError(context.getSource(), Text.literal("§c" + e.getMessage()));
            return 0;
        }
    }

    private static int executeShellRun(CommandContext<ServerCommandSource> context) {
        Mcpserver inst = Mcpserver.getInstance();
        ConfigManager cm = inst.getConfigManager();
        if (cm == null) {
            VersionCompat.sendError(context.getSource(), Text.literal("§c配置管理器未初始化"));
            return 0;
        }
        if (!cm.isShellEnabled()) {
            VersionCompat.sendError(context.getSource(),
                    Text.literal("§cShell 执行器未启用，请先执行: §f/mcpserver shell enable"));
            return 0;
        }
        String command = StringArgumentType.getString(context, "command");
        ShellExecutor executor = new ShellExecutor(
                inst.getServerMetrics() != null ? inst.getServerMetrics().getServer() : null, cm);
        JsonObject res = executor.execute(command);
        String status = res.has("status") ? res.get("status").getAsString() : "error";
        StringBuilder sb = new StringBuilder();
        if ("success".equals(status)) {
            int exit = res.has("exitCode") ? res.get("exitCode").getAsInt() : -1;
            long elapsed = res.has("elapsedMs") ? res.get("elapsedMs").getAsLong() : 0;
            sb.append("§a✓ §7退出码: §f").append(exit).append(" §7(耗时: §f").append(elapsed).append("§7 ms)§r\n");
            if (res.has("stdout")) {
                String out = res.get("stdout").getAsString();
                if (out != null && !out.isEmpty()) {
                    String trimmed = out.length() > 4000 ? out.substring(0, 4000) + "\n...(截断，共 " + out.length() + " 字符)" : out;
                    sb.append("§7── stdout ──§r\n").append(trimmed);
                }
            }
            if (res.has("stderr")) {
                String err = res.get("stderr").getAsString();
                if (err != null && !err.isEmpty()) {
                    String trimmed = err.length() > 2000 ? err.substring(0, 2000) + "\n...(截断)" : err;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append("§c── stderr ──§r\n").append(trimmed);
                }
            }
        } else {
            sb.append("§c✗ 失败: ").append(res.has("error") ? res.get("error").getAsString() : "未知错误").append("§r");
            if (res.has("timedOut")) sb.append(" §c(超时)");
            if (res.has("stdout")) {
                String out = res.get("stdout").getAsString();
                if (out != null && !out.isEmpty()) sb.append("\n§7stdout:§r ").append(out.substring(0, Math.min(out.length(), 500)));
            }
            if (res.has("stderr")) {
                String err = res.get("stderr").getAsString();
                if (err != null && !err.isEmpty()) sb.append("\n§cstderr:§r ").append(err.substring(0, Math.min(err.length(), 500)));
            }
        }
        VersionCompat.sendFeedback(context.getSource(), Text.literal(sb.toString()));
        return "success".equals(status) ? 1 : 0;
    }
}
