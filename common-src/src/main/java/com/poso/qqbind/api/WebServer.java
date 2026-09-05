package com.poso.qqbind.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.server.ServerProviderHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP API 服务类，基于 Java 内置 {@link com.sun.net.httpserver.HttpServer} 实现.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class WebServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebServer.class);
    private static final String JSON_CONTENT_TYPE = "application/json; charset=UTF-8";
    private final Gson gson = new Gson();
    private final BindingManager bindingManager;
    private HttpServer server;

    // 反射缓存字段
    private static Field latencyField;
    private static Field tickTimesField;

    static {
        // 初始化 latency 字段反射
        try {
            latencyField = ServerPlayer.class.getDeclaredField("latency");
            latencyField.setAccessible(true);
            LOGGER.info("Successfully found latency field in ServerPlayer");
        } catch (NoSuchFieldException e) {
            LOGGER.warn("latency field not found in ServerPlayer, latency will be 0", e);
        }

        // 初始化 tickTimes 字段反射
        try {
            tickTimesField = MinecraftServer.class.getDeclaredField("tickTimes");
            tickTimesField.setAccessible(true);
            LOGGER.info("Successfully found tickTimes field in MinecraftServer");
        } catch (NoSuchFieldException e) {
            LOGGER.warn("tickTimes field not found in MinecraftServer, TPS will always return 20.0", e);
        }
    }

    public WebServer(BindingManager bindingManager) {
        this.bindingManager = bindingManager;
    }

    public void start() {
        try {
            int port = QQBindConfig.HTTP_PORT;
            server = HttpServer.create(new InetSocketAddress(port), 0);

            // 注册路由
            server.createContext("/api/bind", new BindHandler());
            server.createContext("/api/unbind", new UnbindHandler());
            server.createContext("/api/check", new CheckHandler());
            server.createContext("/api/status", new StatusHandler());
            server.createContext("/api/stats", new StatsHandler());
            server.createContext("/api/tps", new TpsHandler());
            server.createContext("/api/broadcast", new BroadcastHandler());

            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            LOGGER.info("HTTP Server started on port {}", port);
        } catch (IOException e) {
            LOGGER.error("Failed to start HTTP server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            LOGGER.info("HTTP Server stopped");
        }
    }

    /**
     * 验证请求方法是否匹配预期
     */
    private boolean validateRequestMethod(HttpExchange exchange, String expectedMethod) {
        return !expectedMethod.equalsIgnoreCase(exchange.getRequestMethod());
    }

    /**
     * 检查请求是否未授权（返回 true 表示未授权）
     */
    private boolean isUnauthorized(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        return auth == null || !auth.equals("Bearer " + QQBindConfig.API_TOKEN);
    }

    /**
     * 统一发送 JSON 响应
     */
    private void sendJson(HttpExchange exchange, int statusCode, JsonObject json) {
        try {
            String jsonStr = gson.toJson(json);
            byte[] bytes = jsonStr.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to send JSON response", e);
        }
    }

    /**
     * 发送简单消息响应（内部构建 JSON）
     */
    private void sendResponse(HttpExchange exchange, int statusCode, String message, boolean success) {
        JsonObject response = new JsonObject();
        response.addProperty("success", success);
        response.addProperty("message", message);
        sendJson(exchange, statusCode, response);
    }

    /**
     * 从查询字符串中获取指定参数的值（工具方法）
     */
    private String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        String prefix = key + "=";
        int start = query.indexOf(prefix);
        if (start == -1) return null;
        start += prefix.length();
        int end = query.indexOf("&", start);
        return end == -1 ? query.substring(start) : query.substring(start, end);
    }

    // ========== 处理器 ==========

    /**
     * 绑定处理器 - POST /api/bind
     * 请求体: {"qq": "123456789", "gameId": "player"}
     */
    private class BindHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (validateRequestMethod(exchange, "POST")) {
                sendResponse(exchange, 405, "Method Not Allowed", false);
                return;
            }
            if (isUnauthorized(exchange)) {
                sendResponse(exchange, 401, "Unauthorized", false);
                return;
            }

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            try {
                JsonObject json = gson.fromJson(body, JsonObject.class);
                String qq = json.get("qq").getAsString();
                String gameId = json.get("gameId").getAsString();

                if (qq == null || qq.isEmpty() || gameId == null || gameId.isEmpty()) {
                    sendResponse(exchange, 400, "Missing qq or gameId", false);
                    return;
                }

                BindingManager.BindResult result = bindingManager.bind(qq, gameId);
                if (result.success) {
                    sendResponse(exchange, 200, result.message, true);
                } else {
                    sendResponse(exchange, 400, result.message, false);
                }
            } catch (Exception e) {
                LOGGER.error("Error processing bind request", e);
                sendResponse(exchange, 500, "Internal Server Error: " + e.getMessage(), false);
            }
        }
    }

    /**
     * 解绑处理器 - POST /api/unbind
     * 请求体: {"gameId": "player"} 或 {"qq": "123456789"}
     */
    private class UnbindHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (validateRequestMethod(exchange, "POST")) {
                sendResponse(exchange, 405, "Method Not Allowed", false);
                return;
            }
            if (isUnauthorized(exchange)) {
                sendResponse(exchange, 401, "Unauthorized", false);
                return;
            }

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            try {
                JsonObject json = gson.fromJson(body, JsonObject.class);
                String gameId = json.has("gameId") ? json.get("gameId").getAsString() : null;
                String qq = json.has("qq") ? json.get("qq").getAsString() : null;

                boolean result;
                String message;

                if (gameId != null && !gameId.isEmpty()) {
                    result = bindingManager.unbindByGameId(gameId);
                    message = result ? "Unbound successfully" : "Game ID not found";
                } else if (qq != null && !qq.isEmpty()) {
                    result = bindingManager.unbindByQQ(qq);
                    message = result ? "Unbound successfully" : "QQ not found";
                } else {
                    sendResponse(exchange, 400, "Missing gameId or qq", false);
                    return;
                }

                sendResponse(exchange, result ? 200 : 404, message, result);
            } catch (Exception e) {
                LOGGER.error("Error processing unbind request", e);
                sendResponse(exchange, 500, "Internal Server Error", false);
            }
        }
    }

    /**
     * 检查处理器 - GET /api/check
     * 支持参数: ?gameId=player 或 ?qq=123456789
     */
    private class CheckHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (validateRequestMethod(exchange, "GET")) {
                sendResponse(exchange, 405, "Method Not Allowed", false);
                return;
            }
            if (isUnauthorized(exchange)) {
                sendResponse(exchange, 401, "Unauthorized", false);
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String gameId = getQueryParam(query, "gameId");
            String qq = getQueryParam(query, "qq");

            if ((gameId == null || gameId.isEmpty()) && (qq == null || qq.isEmpty())) {
                sendResponse(exchange, 400, "Missing gameId or qq parameter", false);
                return;
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);

            if (gameId != null && !gameId.isEmpty()) {
                boolean exists = bindingManager.isBound(gameId);
                response.addProperty("gameId", gameId);
                response.addProperty("bound", exists);
                response.addProperty("qq", exists ? bindingManager.getQQ(gameId) : "");
            } else {
                String boundGameId = bindingManager.getGameIdByQQ(qq);
                boolean exists = (boundGameId != null);
                response.addProperty("qq", qq);
                response.addProperty("bound", exists);
                response.addProperty("gameId", exists ? boundGameId : "");
            }

            sendJson(exchange, 200, response);
        }
    }

    /**
     * 状态处理器 - GET /api/status
     * 返回本服在线玩家列表、人数、TPS、版本、延迟等
     */
    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (validateRequestMethod(exchange, "GET")) {
                sendResponse(exchange, 405, "Method Not Allowed", false);
                return;
            }
            if (isUnauthorized(exchange)) {
                sendResponse(exchange, 401, "Unauthorized", false);
                return;
            }

            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server == null) {
                sendResponse(exchange, 500, "Server not available", false);
                return;
            }

            JsonObject response = new JsonObject();
            response.addProperty("success", true);

            JsonObject serverInfo = new JsonObject();
            serverInfo.addProperty("name", "本服"); // 可改为配置
            serverInfo.addProperty("online_players", server.getPlayerCount());
            serverInfo.addProperty("max_players", server.getMaxPlayers());
            serverInfo.addProperty("version", server.getServerVersion());
            serverInfo.addProperty("motd", server.getMotd());

            // TPS
            double tps = getTPS(server);
            serverInfo.addProperty("tps", tps);

            // 延迟（玩家平均延迟）—— 使用反射安全获取
            Collection<ServerPlayer> players = server.getPlayerList().getPlayers();
            double avgLatency = players.stream().mapToDouble(p -> {
                if (latencyField != null) {
                    try {
                        return latencyField.getInt(p);
                    } catch (IllegalAccessException e) {
                        return 0.0;
                    }
                }
                return 0.0;
            }).average().orElse(0);
            serverInfo.addProperty("latency", avgLatency);

            // 玩家列表
            JsonArray playersArray = new JsonArray();
            for (ServerPlayer player : players) {
                JsonObject p = new JsonObject();
                p.addProperty("name", player.getName().getString());
                p.addProperty("uuid", player.getUUID().toString());
                p.addProperty("is_premium", true);
                playersArray.add(p);
            }
            serverInfo.add("players", playersArray);

            response.add("server", serverInfo);
            sendJson(exchange, 200, response);
        }
    }

    /**
     * 玩家统计处理器 - GET /api/stats/{player}
     */
    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (validateRequestMethod(exchange, "GET")) {
                sendResponse(exchange, 405, "Method Not Allowed", false);
                return;
            }
            if (isUnauthorized(exchange)) {
                sendResponse(exchange, 401, "Unauthorized", false);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            String[] segments = path.split("/");
            if (segments.length < 4) {
                sendResponse(exchange, 400, "Missing player name", false);
                return;
            }
            String playerName = segments[3]; // /api/stats/POSOO

            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server == null) {
                sendResponse(exchange, 500, "Server not available", false);
                return;
            }

            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
            if (player == null) {
                sendResponse(exchange, 404, "Player not found online", false);
                return;
            }

            Map<String, Object> statsMap = com.poso.qqbind.utils.StatUtils.getPlayerStats(player);
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            for (Map.Entry<String, Object> entry : statsMap.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof Number) {
                    response.addProperty(entry.getKey(), (Number) val);
                } else if (val instanceof Boolean) {
                    response.addProperty(entry.getKey(), (Boolean) val);
                } else {
                    response.addProperty(entry.getKey(), val.toString());
                }
            }
            sendJson(exchange, 200, response);
        }
    }

    /**
     * TPS 处理器 - GET /api/tps
     */
    private class TpsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (validateRequestMethod(exchange, "GET")) {
                sendResponse(exchange, 405, "Method Not Allowed", false);
                return;
            }
            if (isUnauthorized(exchange)) {
                sendResponse(exchange, 401, "Unauthorized", false);
                return;
            }

            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server == null) {
                sendResponse(exchange, 500, "Server not available", false);
                return;
            }

            double tps = getTPS(server);
            JsonObject response = new JsonObject();
            response.addProperty("success", true);
            response.addProperty("tps", tps);
            sendJson(exchange, 200, response);
        }
    }

    /**
     * 广播处理器 - POST /api/broadcast
     * 请求体: {"message": "Hello"}
     */
    private class BroadcastHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (validateRequestMethod(exchange, "POST")) {
                sendResponse(exchange, 405, "Method Not Allowed", false);
                return;
            }
            if (isUnauthorized(exchange)) {
                sendResponse(exchange, 401, "Unauthorized", false);
                return;
            }

            String body;
            try (InputStream is = exchange.getRequestBody()) {
                body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            try {
                JsonObject json = gson.fromJson(body, JsonObject.class);
                String message = json.get("message").getAsString();
                if (message == null || message.isEmpty()) {
                    sendResponse(exchange, 400, "Missing message", false);
                    return;
                }

                MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
                if (server == null) {
                    sendResponse(exchange, 500, "Server not available", false);
                    return;
                }

                server.getPlayerList().broadcastSystemMessage(
                        Component.literal(message),
                        false
                );

                sendResponse(exchange, 200, "Broadcast sent", true);
            } catch (Exception e) {
                LOGGER.error("Error processing broadcast request", e);
                sendResponse(exchange, 500, "Internal Server Error", false);
            }
        }
    }

    // ========== TPS 计算（使用反射） ==========

    /**
     * 计算当前服务器的 TPS（基于 tickTimes 数组）
     * 兼容 Forge 和 NeoForge（通过反射）
     */
    private double getTPS(MinecraftServer server) {
        if (tickTimesField == null) {
            return 20.0; // 无法获取，返回满 TPS
        }

        try {
            long[] tickTimes = (long[]) tickTimesField.get(server);
            if (tickTimes == null || tickTimes.length == 0) {
                return 20.0;
            }
            // 取最近 20 个有效值（排除 0）
            int count = 0;
            long sum = 0;
            for (int i = 0; i < tickTimes.length && count < 20; i++) {
                long time = tickTimes[i];
                if (time > 0) {
                    sum += time;
                    count++;
                }
            }
            if (count == 0) {
                return 20.0;
            }
            double avgNanos = (double) sum / count;
            double avgMs = avgNanos / 1_000_000.0;
            return Math.min(20.0, 1000.0 / avgMs);
        } catch (IllegalAccessException e) {
            LOGGER.warn("Failed to access tickTimes via reflection", e);
            return 20.0;
        }
    }
}