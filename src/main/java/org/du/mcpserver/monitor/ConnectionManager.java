package org.du.mcpserver.monitor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ConnectionManager {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");

    private final Map<String, ConnectionInfo> connections = new ConcurrentHashMap<>();
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    public ConnectionManager() {
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public enum ConnectionStatus {
        CONNECTED("已连接"),
        DISCONNECTED("断开连接"),
        ABNORMAL_DISCONNECT("异常断开");

        private final String display;

        ConnectionStatus(String display) {
            this.display = display;
        }

        public String getDisplay() {
            return display;
        }
    }

    public enum TokenStatus {
        VERIFIED("验证通过"),
        FAILED("验证失败"),
        PENDING("待验证");

        private final String display;

        TokenStatus(String display) {
            this.display = display;
        }

        public String getDisplay() {
            return display;
        }
    }

    public static class ConnectionInfo {
        public final String connectionId;
        public final long connectedAt;
        public final String clientAddress;
        public volatile ConnectionStatus status;
        public volatile TokenStatus tokenStatus;
        public final AtomicLong bytesReceived = new AtomicLong(0);
        public final AtomicLong bytesSent = new AtomicLong(0);
        public final AtomicLong packetsReceived = new AtomicLong(0);
        public final AtomicLong packetsSent = new AtomicLong(0);
        public final AtomicLong packetsLost = new AtomicLong(0);

        public ConnectionInfo(String connectionId, String clientAddress) {
            this.connectionId = connectionId;
            this.connectedAt = System.currentTimeMillis();
            this.clientAddress = clientAddress;
            this.status = ConnectionStatus.CONNECTED;
            this.tokenStatus = TokenStatus.PENDING;
        }

        public double getPacketLossRate() {
            long total = packetsSent.get() + packetsLost.get();
            if (total == 0) return 0.0;
            return (double) packetsLost.get() / total * 100.0;
        }
    }

    public String createConnection(InetSocketAddress address) {
        String connectionId = java.util.UUID.randomUUID().toString();
        String clientAddr = address.getAddress().getHostAddress() + ":" + address.getPort();
        ConnectionInfo info = new ConnectionInfo(connectionId, clientAddr);
        connections.put(connectionId, info);
        LOGGER.info("[连接事件] 新连接建立 | ID: {} | 客户端: {} | 时间: {}",
                connectionId, clientAddr, sdf.format(new Date(info.connectedAt)));
        return connectionId;
    }

    public ConnectionInfo getConnection(String connectionId) {
        return connections.get(connectionId);
    }

    public void updateTokenStatus(String connectionId, TokenStatus status) {
        ConnectionInfo info = connections.get(connectionId);
        if (info != null && info.tokenStatus != status) {
            info.tokenStatus = status;
            LOGGER.info("[Token事件] 验证状态变更 | ID: {} | 客户端: {} | 状态: {}",
                    connectionId, info.clientAddress, status.getDisplay());
        }
    }

    public void recordReceived(String connectionId, int bytes) {
        ConnectionInfo info = connections.get(connectionId);
        if (info != null) {
            info.bytesReceived.addAndGet(bytes);
            info.packetsReceived.incrementAndGet();
        }
    }

    public void recordSent(String connectionId, int bytes) {
        ConnectionInfo info = connections.get(connectionId);
        if (info != null) {
            info.bytesSent.addAndGet(bytes);
            info.packetsSent.incrementAndGet();
        }
    }

    public void recordLost(String connectionId, int count) {
        ConnectionInfo info = connections.get(connectionId);
        if (info != null) {
            info.packetsLost.addAndGet(count);
        }
    }

    public void disconnect(String connectionId, boolean abnormal) {
        ConnectionInfo info = connections.get(connectionId);
        if (info != null && info.status == ConnectionStatus.CONNECTED) {
            ConnectionStatus newStatus = abnormal ? ConnectionStatus.ABNORMAL_DISCONNECT : ConnectionStatus.DISCONNECTED;
            info.status = newStatus;
            long duration = System.currentTimeMillis() - info.connectedAt;
            LOGGER.info("[连接事件] {} | ID: {} | 客户端: {} | 持续: {}ms | 收: {}B/{}包 | 发: {}B/{}包 | 丢包率: {}%",
                    newStatus.getDisplay(),
                    connectionId,
                    info.clientAddress,
                    duration,
                    info.bytesReceived.get(),
                    info.packetsReceived.get(),
                    info.bytesSent.get(),
                    info.packetsSent.get(),
                    String.format("%.2f", info.getPacketLossRate()));
        }
    }

    public void removeConnection(String connectionId) {
        connections.remove(connectionId);
    }

    public Map<String, ConnectionInfo> getAllConnections() {
        return new ConcurrentHashMap<>(connections);
    }

    public int getActiveConnectionCount() {
        return (int) connections.values().stream()
                .filter(c -> c.status == ConnectionStatus.CONNECTED)
                .count();
    }

    public void cleanupDisconnected(long maxAgeMs) {
        long now = System.currentTimeMillis();
        connections.entrySet().removeIf(entry -> {
            ConnectionInfo info = entry.getValue();
            return info.status != ConnectionStatus.CONNECTED &&
                    (now - info.connectedAt) > maxAgeMs;
        });
    }
}
