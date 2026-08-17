package org.du.mcpserver.util;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 系统级命令执行器（Windows cmd / Linux bash）
 *
 * ⚠ 高危功能，必须通过 config.json 中 shellEnabled: true 显式启用。
 * 用法示例：
 *   - Windows: execute("dir /b")
 *   - Linux:   execute("ls -la")
 */
public class ShellExecutor {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    // Windows 默认 cmd 代码页 936 (GBK)，但如果终端已经 chcp 65001 则为 UTF-8
    // 为同时兼容两种场景：先读原始字节，再按 UTF-8 → GBK 回退策略解码
    private static final Charset CS_UTF8 = StandardCharsets.UTF_8;
    private static final Charset CS_GBK  = Charset.forName("GBK");

    private final MinecraftServer server;
    private final ConfigManager configManager;

    public ShellExecutor(MinecraftServer server, ConfigManager configManager) {
        this.server = server;
        this.configManager = configManager;
    }

    public boolean isEnabled() {
        return configManager.isShellEnabled();
    }

    /** 以服务器 run 目录为工作目录执行命令 */
    public JsonObject execute(String command) {
        return execute(command, null, configManager.getShellTimeoutMs());
    }

    public JsonObject execute(String command, String workingDir, Integer timeoutMs) {
        JsonObject result = new JsonObject();

        if (!isEnabled()) {
            result.addProperty("status", "error");
            result.addProperty("error", "Shell executor is disabled. Set 'shellEnabled: true' in config.json");
            return result;
        }

        if (command == null || command.isBlank()) {
            result.addProperty("status", "error");
            result.addProperty("error", "Command is empty");
            return result;
        }

        int timeout = timeoutMs != null ? timeoutMs : configManager.getShellTimeoutMs();
        if (timeout < 1000) timeout = 1000;
        if (timeout > 3_600_000) timeout = 3_600_000;

        try {
            // 自动选择 shell
            String[] shellCmd = buildShellCommand(command);
            ProcessBuilder pb = new ProcessBuilder(shellCmd);
            pb.redirectErrorStream(false);

            // 工作目录
            Path workDir;
            if (workingDir != null && !workingDir.isBlank()) {
                workDir = Path.of(workingDir);
            } else if (server != null) {
                workDir = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
            } else {
                workDir = Path.of(".").toAbsolutePath();
            }
            pb.directory(workDir.toAbsolutePath().toFile());

            LOGGER.warn("[Shell] Executing command: {} (cwd={})", command, workDir);
            long startedAt = System.currentTimeMillis();

            Process process = pb.start();

            // 读取原始字节（避免编码锁定死），并行不阻塞
            ByteArrayOutputStream stdoutBytes = new ByteArrayOutputStream();
            ByteArrayOutputStream stderrBytes = new ByteArrayOutputStream();
            Thread stdoutThread = startByteReader(process.getInputStream(), stdoutBytes, "out");
            Thread stderrThread = startByteReader(process.getErrorStream(), stderrBytes, "err");

            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - startedAt;

            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                if (stdoutThread != null) stdoutThread.join(500);
                if (stderrThread != null) stderrThread.join(500);

                result.addProperty("status", "error");
                result.addProperty("error", "Command timed out after " + timeout + "ms");
                result.addProperty("timedOut", true);
                result.addProperty("stdout", decodeBytes(stdoutBytes.toByteArray()));
                result.addProperty("stderr", decodeBytes(stderrBytes.toByteArray()));
                result.addProperty("elapsedMs", elapsed);
                LOGGER.warn("[Shell] Command timed out ({}ms): {}", elapsed, command);
                return result;
            }

            if (stdoutThread != null) stdoutThread.join(1000);
            if (stderrThread != null) stderrThread.join(1000);

            int exitCode = process.exitValue();
            result.addProperty("status", "success");
            result.addProperty("exitCode", exitCode);
            result.addProperty("success", exitCode == 0);
            result.addProperty("stdout", decodeBytes(stdoutBytes.toByteArray()));
            result.addProperty("stderr", decodeBytes(stderrBytes.toByteArray()));
            result.addProperty("elapsedMs", elapsed);
            result.addProperty("command", command);
            result.addProperty("workingDir", workDir.toAbsolutePath().toString());

            LOGGER.info("[Shell] Command finished (exit={}, {}ms): {}", exitCode, elapsed, command);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.addProperty("status", "error");
            result.addProperty("error", "Command interrupted: " + e.getMessage());
            return result;
        } catch (Exception e) {
            LOGGER.error("[Shell] Command failed: {} - {}", command, e.getMessage());
            result.addProperty("status", "error");
            result.addProperty("error", e.getMessage() != null ? e.getMessage() : e.getClass().getName());
            return result;
        }
    }

    /** 跨平台选择 shell：Windows -> cmd.exe /c；Unix/Linux -> bash -c (或 sh -c) */
    private static String[] buildShellCommand(String command) {
        if (IS_WINDOWS) {
            return new String[]{"cmd.exe", "/c", command};
        } else {
            try {
                if (java.nio.file.Files.exists(java.nio.file.Path.of("/bin/bash"))) {
                    return new String[]{"/bin/bash", "-c", command};
                }
            } catch (Exception ignored) {}
            return new String[]{"/bin/sh", "-c", command};
        }
    }

    /** 异步读取完整字节流，避免管道阻塞。完整读取后再解码。 */
    private static Thread startByteReader(InputStream in, ByteArrayOutputStream out, String tag) {
        Thread t = new Thread(() -> {
            try (InputStream is = in) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            } catch (Exception ignored) {}
        }, "ShellReader-" + tag);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /**
     * 智能解码：Windows 优先 UTF-8，若有大量 U+FFFD 替换字符则回退 GBK；
     * 非 Windows 直接 UTF-8。
     * 这样既兼容 chcp 65001 的现代 Windows 终端，也兼容默认 cmd (chcp 936)。
     */
    private static String decodeBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        if (!IS_WINDOWS) {
            return new String(bytes, CS_UTF8);
        }
        // Windows：先 UTF-8，检测是否有乱码（替换字符），有则 GBK
        String utf8 = new String(bytes, CS_UTF8);
        if (countReplacementChars(utf8) == 0) return utf8;
        String gbk = new String(bytes, CS_GBK);
        // 如果 GBK 乱码更少，用 GBK，否则仍用 UTF-8（部分字符的 GBK 也可能有替换字符）
        return countReplacementChars(gbk) < countReplacementChars(utf8) ? gbk : utf8;
    }

    private static int countReplacementChars(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') count++;
        }
        return count;
    }
}
