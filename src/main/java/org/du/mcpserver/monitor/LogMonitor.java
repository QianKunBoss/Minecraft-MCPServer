package org.du.mcpserver.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogMonitor {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");
    private static final int MAX_LOG_ENTRIES = 2000;

    private final Deque<LogEntry> logBuffer = new ConcurrentLinkedDeque<>();
    private final Deque<LogEntry> playerEvents = new ConcurrentLinkedDeque<>();
    private final Deque<LogEntry> chatMessages = new ConcurrentLinkedDeque<>();
    private final Deque<LogEntry> commandEvents = new ConcurrentLinkedDeque<>();

    private CustomLogAppender logAppender;
    private MinecraftServer server;
    private static LogMonitor instance;

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})");
    private static final Pattern THREAD_LEVEL_PATTERN = Pattern.compile("\\[(.*?)/(.*?)\\]");
    private static final Pattern CHAT_PATTERN = Pattern.compile("<([^>]+)> (.*)");
    private static final Pattern COMMAND_PATTERN = Pattern.compile("(\\w+) issued server command: /(.*)");
    private static final Pattern PLAYER_JOIN_PATTERN = Pattern.compile("(\\w+) joined the game");
    private static final Pattern PLAYER_LEAVE_PATTERN = Pattern.compile("(\\w+) left the game");

    public static class LogEntry {
        public final long timestamp;
        public final String timestampStr;
        public final String level;
        public final String thread;
        public final String loggerName;
        public final String message;
        public final String type;
        public final String playerName;
        public final String detail;
        public final String rawMessage;

        public LogEntry(long timestamp, String level, String thread, String loggerName,
                        String message, String type, String playerName, String detail, String rawMessage) {
            this.timestamp = timestamp;
            this.timestampStr = formatTimestamp(timestamp);
            this.level = level;
            this.thread = thread;
            this.loggerName = loggerName;
            this.message = message;
            this.type = type;
            this.playerName = playerName;
            this.detail = detail;
            this.rawMessage = rawMessage;
        }

        private String formatTimestamp(long timestamp) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }

    public void initialize(MinecraftServer minecraftServer) {
        this.server = minecraftServer;
        instance = this;

        // 清理可能残留的旧 appender（热重启/多次 initialize 场景），避免重复挂载
        if (logAppender != null) {
            try {
                org.apache.logging.log4j.core.Logger oldRoot =
                        (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
                oldRoot.removeAppender(logAppender);
            } catch (Exception ignored) {
                // 忽略移除失败
            }
        }

        logAppender = new CustomLogAppender("MCPServerAppender", null,
                PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY);
        logAppender.start();

        org.apache.logging.log4j.core.Logger rootLogger =
                (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
        rootLogger.addAppender(logAppender);

        ServerPlayConnectionEvents.JOIN.register(LogMonitor::onJoinEvent);
        ServerPlayConnectionEvents.DISCONNECT.register(LogMonitor::onDisconnectEvent);

        LOGGER.info("LogMonitor initialized with buffer size: {}", MAX_LOG_ENTRIES);
    }

    private static void onJoinEvent(net.minecraft.server.network.ServerPlayNetworkHandler handler,
                                    net.fabricmc.fabric.api.networking.v1.PacketSender sender,
                                    net.minecraft.server.MinecraftServer server) {
        if (instance != null) {
            String playerName = handler.getPlayer().getName().getString();
            instance.recordPlayerEvent("PLAYER_JOIN", playerName, null);
        }
    }

    private static void onDisconnectEvent(net.minecraft.server.network.ServerPlayNetworkHandler handler,
                                           net.minecraft.server.MinecraftServer server) {
        if (instance != null) {
            String playerName = handler.getPlayer().getName().getString();
            instance.recordPlayerEvent("PLAYER_LEAVE", playerName, null);
        }
    }

    private void recordPlayerEvent(String type, String playerName, String detail) {
        long timestamp = System.currentTimeMillis();
        String message = type.equals("PLAYER_JOIN") ? playerName + " joined the game" : playerName + " left the game";
        LogEntry entry = new LogEntry(timestamp, "INFO", "Server thread", "MCPServer",
                message, type, playerName, detail, message);

        logBuffer.addLast(entry);
        playerEvents.addLast(entry);
        trimBuffers();
    }

    private void addLogEntry(LogEntry entry) {
        logBuffer.addLast(entry);

        switch (entry.type) {
            case "CHAT" -> chatMessages.addLast(entry);
            case "COMMAND" -> commandEvents.addLast(entry);
            case "PLAYER_JOIN", "PLAYER_LEAVE" -> playerEvents.addLast(entry);
        }

        trimBuffers();
    }

    private void trimBuffers() {
        while (logBuffer.size() > MAX_LOG_ENTRIES) {
            logBuffer.pollFirst();
        }
        while (playerEvents.size() > 500) {
            playerEvents.pollFirst();
        }
        while (chatMessages.size() > 500) {
            chatMessages.pollFirst();
        }
        while (commandEvents.size() > 500) {
            commandEvents.pollFirst();
        }
    }

    private class CustomLogAppender extends AbstractAppender {
        protected CustomLogAppender(String name, org.apache.logging.log4j.core.Filter filter,
                                    org.apache.logging.log4j.core.Layout<?> layout, boolean ignoreExceptions,
                                    Property[] properties) {
            super(name, filter, layout, ignoreExceptions, properties);
        }

        @Override
        public void append(LogEvent event) {
            // 防御：未启动时直接丢弃，避免 "Attempted to append to non-started appender"
            if (!isStarted()) {
                return;
            }
            String rawMessage = event.getMessage().getFormattedMessage();
            String level = event.getLevel().name();
            long timestamp = event.getTimeMillis();
            String thread = event.getThreadName();
            String loggerName = event.getLoggerName();

            String type = classifyLog(level);
            String playerName = null;
            String detail = null;
            String message = rawMessage;

            Matcher chatMatcher = CHAT_PATTERN.matcher(rawMessage);
            Matcher commandMatcher = COMMAND_PATTERN.matcher(rawMessage);
            Matcher joinMatcher = PLAYER_JOIN_PATTERN.matcher(rawMessage);
            Matcher leaveMatcher = PLAYER_LEAVE_PATTERN.matcher(rawMessage);

            if (chatMatcher.find()) {
                type = "CHAT";
                playerName = chatMatcher.group(1);
                detail = chatMatcher.group(2);
            } else if (commandMatcher.find()) {
                type = "COMMAND";
                playerName = commandMatcher.group(1);
                detail = "/" + commandMatcher.group(2);
            } else if (joinMatcher.find()) {
                type = "PLAYER_JOIN";
                playerName = joinMatcher.group(1);
            } else if (leaveMatcher.find()) {
                type = "PLAYER_LEAVE";
                playerName = leaveMatcher.group(1);
            }

            LogEntry entry = new LogEntry(timestamp, level, thread, loggerName,
                    message, type, playerName, detail, rawMessage);

            addLogEntry(entry);
        }

        private String classifyLog(String level) {
            return switch (level) {
                case "ERROR", "FATAL" -> "ERROR";
                case "WARN" -> "WARN";
                case "DEBUG", "TRACE" -> "DEBUG";
                default -> "GENERAL";
            };
        }
    }

    public JsonObject getRecentLogs(int limit, String filterType) {
        JsonObject result = new JsonObject();
        JsonArray logs = new JsonArray();

        int count = 0;
        Iterator<LogEntry> iterator = logBuffer.descendingIterator();

        while (iterator.hasNext() && count < limit) {
            LogEntry entry = iterator.next();

            if (filterType != null && !filterType.isEmpty()) {
                boolean matches = false;
                matches = matches || entry.type.equalsIgnoreCase(filterType);
                matches = matches || entry.level.equalsIgnoreCase(filterType);
                if (!matches) continue;
            }

            logs.add(logEntryToJson(entry));
            count++;
        }

        result.add("logs", logs);
        result.addProperty("total", logs.size());
        result.addProperty("bufferSize", logBuffer.size());
        return result;
    }

    public JsonObject getPlayerEvents(int limit) {
        JsonObject result = new JsonObject();
        JsonArray events = new JsonArray();

        int count = 0;
        Iterator<LogEntry> iterator = playerEvents.descendingIterator();

        while (iterator.hasNext() && count < limit) {
            events.add(logEntryToJson(iterator.next()));
            count++;
        }

        result.add("events", events);
        result.addProperty("total", events.size());
        return result;
    }

    public JsonObject getChatMessages(int limit) {
        JsonObject result = new JsonObject();
        JsonArray messages = new JsonArray();

        int count = 0;
        Iterator<LogEntry> iterator = chatMessages.descendingIterator();

        while (iterator.hasNext() && count < limit) {
            messages.add(logEntryToJson(iterator.next()));
            count++;
        }

        result.add("messages", messages);
        result.addProperty("total", messages.size());
        return result;
    }

    public JsonObject getCommandHistory(int limit) {
        JsonObject result = new JsonObject();
        JsonArray commands = new JsonArray();

        int count = 0;
        Iterator<LogEntry> iterator = commandEvents.descendingIterator();

        while (iterator.hasNext() && count < limit) {
            commands.add(logEntryToJson(iterator.next()));
            count++;
        }

        result.add("commands", commands);
        result.addProperty("total", commands.size());
        return result;
    }

    private JsonObject logEntryToJson(LogEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("timestamp", entry.timestamp);
        obj.addProperty("timestampStr", entry.timestampStr);
        obj.addProperty("level", entry.level);
        obj.addProperty("thread", entry.thread);
        obj.addProperty("logger", entry.loggerName);
        obj.addProperty("message", entry.message);
        obj.addProperty("type", entry.type);
        if (entry.playerName != null) {
            obj.addProperty("playerName", entry.playerName);
        }
        if (entry.detail != null) {
            obj.addProperty("detail", entry.detail);
        }
        obj.addProperty("raw", entry.rawMessage);
        return obj;
    }

    public void shutdown() {
        if (logAppender != null) {
            try {
                // 先从 root logger 移除，避免关闭后仍有日志路由到已停止的 appender
                org.apache.logging.log4j.core.Logger rootLogger =
                        (org.apache.logging.log4j.core.Logger) LogManager.getRootLogger();
                rootLogger.removeAppender(logAppender);
            } catch (Exception ignored) {
                // 忽略移除失败
            }
            try {
                logAppender.stop();
            } catch (Exception ignored) {
                // 忽略停止失败
            }
            logAppender = null;
        }
        instance = null;
        LOGGER.info("LogMonitor shutdown");
    }
}