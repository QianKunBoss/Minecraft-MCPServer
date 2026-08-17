package org.du.mcpserver.http;

import com.google.gson.JsonObject;
import org.du.mcpserver.mcp.MCPProtocolHandler;
import org.du.mcpserver.monitor.ConnectionManager;
import org.du.mcpserver.monitor.LogMonitor;
import org.du.mcpserver.monitor.PlayerInfoManager;
import org.du.mcpserver.monitor.ServerMetrics;
import org.du.mcpserver.spark.SparkIntegration;
import org.du.mcpserver.util.ConfigManager;
import org.du.mcpserver.util.FileEditor;
import org.du.mcpserver.util.JsonUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.Executors;

public class MCPHttpServer {

    private static final Logger LOGGER = LogManager.getLogger("MCPServer");

    private HttpServer httpServer;
    private java.util.concurrent.ExecutorService executor;
    private final int port;
    private volatile String apiKey;
    private final MCPProtocolHandler mcpProtocolHandler;
    private final ServerMetrics serverMetrics;
    private final LogMonitor logMonitor;
    private final PlayerInfoManager playerInfoManager;
    private final SparkIntegration sparkIntegration;
    private final ConnectionManager connectionManager;
    private final ConfigManager configManager;
    private final FileEditor fileEditor;

    public MCPHttpServer(int port, String apiKey, LogMonitor logMonitor,
                         ServerMetrics serverMetrics, PlayerInfoManager playerInfoManager,
                         SparkIntegration sparkIntegration, ConnectionManager connectionManager,
                         ConfigManager configManager, FileEditor fileEditor) {
        this.port = port;
        this.apiKey = apiKey;
        this.logMonitor = logMonitor;
        this.serverMetrics = serverMetrics;
        this.playerInfoManager = playerInfoManager;
        this.sparkIntegration = sparkIntegration;
        this.connectionManager = connectionManager;
        this.configManager = configManager;
        this.fileEditor = fileEditor;
        this.mcpProtocolHandler = new MCPProtocolHandler(logMonitor, serverMetrics, playerInfoManager,
                sparkIntegration, apiKey, configManager, fileEditor);
    }

    public void updateApiKey(String newApiKey) {
        this.apiKey = newApiKey;
        mcpProtocolHandler.updateApiKey(newApiKey);
    }

    public void start() {
        try {
            boolean sslEnabled = configManager.isSslEnabled();

            if (sslEnabled) {
                startHttpsServer();
            } else {
                startHttpServer();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to start MCP HTTP Server: {}", e.getMessage());
        }
    }

    private void startHttpServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newFixedThreadPool(16);
        httpServer.setExecutor(executor);
        registerContexts(httpServer);
        httpServer.start();
        LOGGER.info("MCP HTTP Server started on port {} (HTTP mode)", port);
    }

    private void startHttpsServer() throws Exception {
        java.nio.file.Path keystorePath = configManager.getKeystorePath();
        String keystorePassword = configManager.getKeystorePassword();
        String keystoreType = configManager.getKeystoreType();
        java.nio.file.Path certPath = configManager.getCertPath();
        java.nio.file.Path keyPath = configManager.getKeyPath();

        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
        executor = Executors.newFixedThreadPool(16);
        httpsServer.setExecutor(executor);

        SSLContext sslContext;

        if (java.nio.file.Files.exists(certPath) && java.nio.file.Files.exists(keyPath)) {
            LOGGER.info("Loading SSL certificate from PEM files: cert={}, key={}", certPath, keyPath);
            sslContext = createSslContextFromPem(certPath, keyPath);
        } else if (java.nio.file.Files.exists(keystorePath)) {
            LOGGER.info("Loading SSL certificate from keystore: {}", keystorePath);
            sslContext = createSslContextFromKeystore(keystorePath, keystorePassword, keystoreType);
        } else {
            LOGGER.error("SSL enabled but no certificate found. Neither PEM files nor keystore exists.");
            LOGGER.error("Please place your SSL certificate at the specified path or update the config");
            // 先释放已创建的 HTTPS 线程池，避免泄漏，再回退到 HTTP
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            startHttpServer();
            return;
        }

        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));

        registerContexts(httpsServer);
        httpServer = httpsServer;
        httpServer.start();
        LOGGER.info("MCP HTTPS Server started on port {} (SSL/TLS mode)", port);
    }

    private SSLContext createSslContextFromKeystore(java.nio.file.Path keystorePath, String password, String type) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(type);
        try (java.io.FileInputStream fis = new java.io.FileInputStream(keystorePath.toFile())) {
            keyStore.load(fis, password.toCharArray());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, password.toCharArray());

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    private SSLContext createSslContextFromPem(java.nio.file.Path certPath, java.nio.file.Path keyPath) throws Exception {
        byte[] certBytes = java.nio.file.Files.readAllBytes(certPath);
        byte[] keyBytes = java.nio.file.Files.readAllBytes(keyPath);

        java.security.cert.CertificateFactory certFactory = java.security.cert.CertificateFactory.getInstance("X.509");
        java.util.List<java.security.cert.X509Certificate> certChain = new java.util.ArrayList<>();

        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(certBytes)) {
            while (bais.available() > 0) {
                java.security.cert.Certificate cert = certFactory.generateCertificate(bais);
                if (cert instanceof java.security.cert.X509Certificate) {
                    certChain.add((java.security.cert.X509Certificate) cert);
                }
            }
        }

        LOGGER.info("Loaded {} certificates from fullchain.pem", certChain.size());
        for (int i = 0; i < certChain.size(); i++) {
            LOGGER.info("  Certificate {}: Subject={}, Issuer={}", 
                i, 
                certChain.get(i).getSubjectDN().getName(), 
                certChain.get(i).getIssuerDN().getName());
        }

        java.security.PrivateKey privateKey = parsePrivateKey(keyBytes);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);

        java.security.cert.Certificate[] chainArray = certChain.toArray(new java.security.cert.Certificate[0]);
        keyStore.setKeyEntry("server", privateKey, new char[0], chainArray);
        kmf.init(keyStore, new char[0]);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    private java.security.PrivateKey parsePrivateKey(byte[] keyBytes) throws Exception {
        String keyStr = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
        
        if (keyStr.contains("BEGIN RSA PRIVATE KEY")) {
            String pem = keyStr.replace("-----BEGIN RSA PRIVATE KEY-----", "")
                               .replace("-----END RSA PRIVATE KEY-----", "")
                               .replaceAll("\\s", "");
            byte[] decoded = java.util.Base64.getDecoder().decode(pem);
            java.security.spec.RSAPrivateKeySpec spec = new java.security.spec.RSAPrivateKeySpec(
                new java.math.BigInteger(1, decoded),
                new java.math.BigInteger("65537")
            );
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);
        } else if (keyStr.contains("BEGIN PRIVATE KEY")) {
            String pem = keyStr.replace("-----BEGIN PRIVATE KEY-----", "")
                               .replace("-----END PRIVATE KEY-----", "")
                               .replaceAll("\\s", "");
            byte[] decoded = java.util.Base64.getDecoder().decode(pem);
            java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(decoded);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            return kf.generatePrivate(spec);
        } else {
            throw new IllegalArgumentException("Unsupported private key format");
        }
    }

    private void registerContexts(HttpServer server) {
        server.createContext("/mcp", new MCPHandler());
        server.createContext("/api/status", new StatusHandler());
        server.createContext("/api/tps", new TpsHandler());
        server.createContext("/api/memory", new MemoryHandler());
        server.createContext("/api/entities", new EntitiesHandler());
        server.createContext("/api/logs", new LogsHandler());
        server.createContext("/api/spark/availability", new SparkAvailabilityHandler());
        server.createContext("/api/spark/tps", new SparkTpsHandler());
        server.createContext("/api/spark/health", new SparkHealthHandler());
        server.createContext("/api/spark/profiler/start", new SparkProfilerStartHandler());
        server.createContext("/api/spark/profiler/stop", new SparkProfilerStopHandler());
        server.createContext("/api/spark/profiler/status", new SparkProfilerStatusHandler());
        server.createContext("/api/spark/heapdump", new SparkHeapDumpHandler());
        server.createContext("/health", new HealthHandler());
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(0);
            LOGGER.info("MCP HTTP Server stopped");
        }
        // HttpServer.stop() 不会关闭自定义的 executor；必须显式关闭，否则线程池里的
        // 非守护线程会一直存活，导致 JVM 无法退出、关服后进程卡死。
        if (executor != null) {
            try {
                executor.shutdownNow();
            } catch (Exception ignored) {
                // 忽略关闭异常
            }
            executor = null;
        }
    }

    public MCPProtocolHandler getProtocolHandler() {
        return mcpProtocolHandler;
    }

    private boolean validateApiKey(HttpExchange exchange) {
        String requestApiKey = exchange.getRequestHeaders().getFirst("X-API-Key");
        return apiKey.equals(requestApiKey);
    }

    private void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "X-API-Key, Content-Type, Accept");
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String responseBody) throws IOException {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        setCorsHeaders(exchange);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int charsRead;
            while ((charsRead = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, charsRead);
            }
            return sb.toString();
        }
    }

    private class MCPHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 200, "");
                return;
            }

            if (!"POST".equals(exchange.getRequestMethod())) {
                sendJsonResponse(exchange, 405, JsonUtils.toJson(JsonUtils.createErrorResponse(-32600, "Method not allowed: only POST is supported")));
                return;
            }

            handleStreamableHttp(exchange);
        }

        private void handleStreamableHttp(HttpExchange exchange) throws IOException {
            InetSocketAddress remoteAddr = exchange.getRemoteAddress();
            String connectionId = connectionManager.createConnection(remoteAddr);

            if (!validateApiKey(exchange)) {
                connectionManager.updateTokenStatus(connectionId, ConnectionManager.TokenStatus.FAILED);
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                connectionManager.disconnect(connectionId, false);
                return;
            }

            connectionManager.updateTokenStatus(connectionId, ConnectionManager.TokenStatus.VERIFIED);

            try {
                String body = readRequestBody(exchange);
                connectionManager.recordReceived(connectionId, body.length());

                JsonObject response = mcpProtocolHandler.handleRequest(body);

                if (response == null) {
                    sendJsonResponse(exchange, 202, "{}");
                } else {
                    String jsonResponse = JsonUtils.toJson(response);
                    connectionManager.recordSent(connectionId, jsonResponse.length());
                    sendJsonResponse(exchange, 200, jsonResponse);
                }
            } catch (Exception e) {
                LOGGER.error("MCP Protocol Error: {}", e.getMessage());
                sendJsonResponse(exchange, 500, JsonUtils.toJson(JsonUtils.createErrorResponse(-32603, "Internal error")));
            } finally {
                connectionManager.disconnect(connectionId, false);
            }
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }
            sendJsonResponse(exchange, 200, JsonUtils.toJson(serverMetrics.getServerStatus()));
        }
    }

    private class TpsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }
            sendJsonResponse(exchange, 200, JsonUtils.toJson(serverMetrics.getTPSMetrics()));
        }
    }

    private class MemoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }
            sendJsonResponse(exchange, 200, JsonUtils.toJson(serverMetrics.getMemoryMetrics()));
        }
    }

    private class EntitiesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }
            sendJsonResponse(exchange, 200, JsonUtils.toJson(serverMetrics.getEntityMetrics()));
        }
    }

    private class LogsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String type = null;
            int limit = 50;

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] parts = param.split("=");
                    if (parts.length == 2) {
                        if ("type".equals(parts[0])) {
                            type = parts[1];
                        } else if ("limit".equals(parts[0])) {
                            try {
                                limit = Integer.parseInt(parts[1]);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }

            JsonObject result = logMonitor.getRecentLogs(limit, type);
            sendJsonResponse(exchange, 200, JsonUtils.toJson(result));
        }
    }

    private class SparkAvailabilityHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }
            JsonObject result = sparkIntegration.checkAvailability();
            sendJsonResponse(exchange, result.has("error") ? 400 : 200, JsonUtils.toJson(result));
        }
    }

    private class SparkTpsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }
            JsonObject result = sparkIntegration.getTPSMetrics();
            sendJsonResponse(exchange, result.has("error") ? 400 : 200, JsonUtils.toJson(result));
        }
    }

    private class SparkHealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }
            JsonObject result = sparkIntegration.getHealthReport();
            sendJsonResponse(exchange, result.has("error") ? 400 : 200, JsonUtils.toJson(result));
        }
    }

    private class SparkProfilerStartHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String type = "default";
            Integer duration = null;

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] parts = param.split("=");
                    if (parts.length == 2) {
                        if ("type".equals(parts[0])) {
                            type = parts[1];
                        } else if ("duration".equals(parts[0])) {
                            try {
                                duration = Integer.parseInt(parts[1]);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }

            JsonObject result = sparkIntegration.startProfiler(type, duration);
            sendJsonResponse(exchange, result.has("error") ? 400 : 200, JsonUtils.toJson(result));
        }
    }

    private class SparkProfilerStopHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String profilerId = null;

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] parts = param.split("=");
                    if (parts.length == 2 && "profilerId".equals(parts[0])) {
                        profilerId = parts[1];
                        break;
                    }
                }
            }

            JsonObject result = sparkIntegration.stopProfiler(profilerId);
            sendJsonResponse(exchange, result.has("error") ? 400 : 200, JsonUtils.toJson(result));
        }
    }

    private class SparkProfilerStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String profilerId = null;

            if (query != null) {
                for (String param : query.split("&")) {
                    String[] parts = param.split("=");
                    if (parts.length == 2 && "profilerId".equals(parts[0])) {
                        profilerId = parts[1];
                        break;
                    }
                }
            }

            JsonObject result = sparkIntegration.getProfilerStatus(profilerId);
            sendJsonResponse(exchange, result.has("error") ? 400 : 200, JsonUtils.toJson(result));
        }
    }

    private class SparkHeapDumpHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!validateApiKey(exchange)) {
                sendJsonResponse(exchange, 401, JsonUtils.toJson(JsonUtils.createErrorResponse(401, "Unauthorized")));
                return;
            }
            JsonObject result = sparkIntegration.createHeapDump();
            sendJsonResponse(exchange, result.has("error") ? 400 : 200, JsonUtils.toJson(result));
        }
    }

    private class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            JsonObject result = new JsonObject();
            result.addProperty("status", "healthy");
            result.addProperty("timestamp", System.currentTimeMillis());
            sendJsonResponse(exchange, 200, JsonUtils.toJson(result));
        }
    }
}