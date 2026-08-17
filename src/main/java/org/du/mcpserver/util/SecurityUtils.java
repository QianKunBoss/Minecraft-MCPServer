package org.du.mcpserver.util;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SecurityUtils {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, SessionInfo> SESSIONS = new ConcurrentHashMap<>();

    public static class SessionInfo {
        public final String sessionId;
        public final String apiKey;
        public final long createdAt;
        public volatile long lastUsedAt;

        public SessionInfo(String sessionId, String apiKey) {
            this.sessionId = sessionId;
            this.apiKey = apiKey;
            this.createdAt = System.currentTimeMillis();
            this.lastUsedAt = System.currentTimeMillis();
        }

        public void updateLastUsed() {
            this.lastUsedAt = System.currentTimeMillis();
        }
    }

    public static String generateApiKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static String generateSessionId() {
        return UUID.randomUUID().toString();
    }

    public static String createSession(String apiKey) {
        String sessionId = generateSessionId();
        SESSIONS.put(sessionId, new SessionInfo(sessionId, apiKey));
        return sessionId;
    }

    public static boolean isValidSession(String sessionId) {
        SessionInfo info = SESSIONS.get(sessionId);
        if (info == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - info.lastUsedAt > 3600000) {
            SESSIONS.remove(sessionId);
            return false;
        }
        info.updateLastUsed();
        return true;
    }

    public static void invalidateSession(String sessionId) {
        SESSIONS.remove(sessionId);
    }

    public static void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> now - entry.getValue().lastUsedAt > 3600000);
    }
}
