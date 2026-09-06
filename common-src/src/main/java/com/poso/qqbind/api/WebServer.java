package com.poso.qqbind.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.api.exception.BusinessException;
import com.poso.qqbind.api.exception.InvalidParameterException;
import com.poso.qqbind.api.exception.ResourceNotFoundException;
import com.poso.qqbind.api.handler.BaseHandler;
import com.poso.qqbind.api.response.ErrorCode;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.core.TokenManager;
import com.poso.qqbind.server.ServerProviderHolder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
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
    private final BindingManager bindingManager;
    private HttpServer server;

    // 反射缓存字段
    private static Field latencyField;
    private static Field tickTimesField;

    static {
        try {
            latencyField = ServerPlayer.class.getDeclaredField("latency");
            latencyField.setAccessible(true);
            LOGGER.info("Successfully found latency field in ServerPlayer");
        } catch (NoSuchFieldException e) {
            LOGGER.warn("latency field not found in ServerPlayer, latency will be 0", e);
        }
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
            server.createContext("/api/validate_token", new ValidateTokenHandler());

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

    // ========== 处理器实现（继承 BaseHandler） ==========

    /**
     * 绑定处理器 - POST /api/bind
     * 支持两种模式：
     * 1) 传统模式：{"qq": "123456789", "gameId": "player"}
     * 2) 令牌模式：{"token": "778899", "qq": "123456789"}
     */
    private class BindHandler extends BaseHandler {
        @Override
        protected void doHandle(HttpExchange exchange) throws Exception {
            if (!validateMethod(exchange, "POST")) return;
            if (!validateAuth(exchange)) return;

            String body = readRequestBody(exchange);
            JsonObject json = gson.fromJson(body, JsonObject.class);
            String qq = json.has("qq") ? json.get("qq").getAsString() : null;
            String gameId = json.has("gameId") ? json.get("gameId").getAsString() : null;
            String token = json.has("token") ? json.get("token").getAsString() : null;

            if (qq == null || qq.isEmpty()) {
                throw new InvalidParameterException(ErrorCode.MISSING_QQ);
            }

            // 模式1：传统模式（提供 gameId）
            if (gameId != null && !gameId.isEmpty()) {
                BindingManager.BindResult result = bindingManager.bind(qq, gameId);
                if (result.success) {
                    JsonObject data = new JsonObject();
                    data.addProperty("gameId", gameId);
                    data.addProperty("qq", qq);
                    sendSuccess(exchange, data);
                } else {
                    // 业务失败，使用适当的错误码
                    ErrorCode errorCode = result.message.contains("已被绑定") ? ErrorCode.GAME_ID_ALREADY_BOUND : ErrorCode.BIND_FAILED;
                    sendError(exchange, errorCode);
                }
                return;
            }

            // 模式2：令牌模式（提供 token）
            if (token != null && !token.isEmpty()) {
                String gameIdFromToken = TokenManager.validateAndUseToken(token, qq);
                if (gameIdFromToken == null) {
                    throw new InvalidParameterException(ErrorCode.INVALID_TOKEN);
                }
                BindingManager.BindResult result = bindingManager.bind(qq, gameIdFromToken);
                if (result.success) {
                    JsonObject data = new JsonObject();
                    data.addProperty("gameId", gameIdFromToken);
                    data.addProperty("qq", qq);
                    sendSuccess(exchange, data);
                } else {
                    sendError(exchange, ErrorCode.BIND_FAILED);
                }
                return;
            }

            // 既无 gameId 也无 token
            throw new InvalidParameterException(ErrorCode.MISSING_GAME_ID_OR_TOKEN);
        }
    }

    /**
     * 解绑处理器 - POST /api/unbind
     * 请求体: {"gameId": "player"} 或 {"qq": "123456789"}
     */
    private class UnbindHandler extends BaseHandler {
        @Override
        protected void doHandle(HttpExchange exchange) throws Exception {
            if (!validateMethod(exchange, "POST")) return;
            if (!validateAuth(exchange)) return;

            String body = readRequestBody(exchange);
            JsonObject json = gson.fromJson(body, JsonObject.class);
            String gameId = json.has("gameId") ? json.get("gameId").getAsString() : null;
            String qq = json.has("qq") ? json.get("qq").getAsString() : null;

            boolean result;
            String message;

            if (gameId != null && !gameId.isEmpty()) {
                result = bindingManager.unbindByGameId(gameId);
                message = result ? "Unbound successfully" : "Game ID not found";
                if (!result) throw new ResourceNotFoundException(ErrorCode.GAME_ID_NOT_FOUND);
            } else if (qq != null && !qq.isEmpty()) {
                result = bindingManager.unbindByQQ(qq);
                message = result ? "Unbound successfully" : "QQ not found";
                if (!result) throw new ResourceNotFoundException(ErrorCode.QQ_NOT_FOUND);
            } else {
                throw new InvalidParameterException(ErrorCode.MISSING_PARAMETER);
            }

            JsonObject data = new JsonObject();
            data.addProperty("message", message);
            sendSuccess(exchange, data);
        }
    }

    /**
     * 检查处理器 - GET /api/check
     * 支持参数: ?gameId=player 或 ?qq=123456789
     */
    private class CheckHandler extends BaseHandler {
        @Override
        protected void doHandle(HttpExchange exchange) throws Exception {
            if (!validateMethod(exchange, "GET")) return;
            if (!validateAuth(exchange)) return;

            String query = exchange.getRequestURI().getQuery();
            String gameId = getQueryParam(query, "gameId");
            String qq = getQueryParam(query, "qq");

            if ((gameId == null || gameId.isEmpty()) && (qq == null || qq.isEmpty())) {
                throw new InvalidParameterException(ErrorCode.MISSING_PARAMETER);
            }

            JsonObject responseData = new JsonObject();
            if (gameId != null && !gameId.isEmpty()) {
                boolean exists = bindingManager.isBound(gameId);
                responseData.addProperty("gameId", gameId);
                responseData.addProperty("bound", exists);
                if (exists) {
                    responseData.addProperty("qq", bindingManager.getQQ(gameId));
                }
            } else {
                String boundGameId = bindingManager.getGameIdByQQ(qq);
                boolean exists = (boundGameId != null);
                responseData.addProperty("qq", qq);
                responseData.addProperty("bound", exists);
                if (exists) {
                    responseData.addProperty("gameId", boundGameId);
                }
            }
            sendSuccess(exchange, responseData);
        }
    }

    /**
     * 状态处理器 - GET /api/status
     * 返回本服在线玩家列表、人数、TPS、版本、延迟等
     */
    private class StatusHandler extends BaseHandler {
        @Override
        protected void doHandle(HttpExchange exchange) throws Exception {
            if (!validateMethod(exchange, "GET")) return;
            if (!validateAuth(exchange)) return;

            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server == null) {
                throw new BusinessException(ErrorCode.SERVER_NOT_AVAILABLE);
            }

            JsonObject serverInfo = new JsonObject();
            serverInfo.addProperty("name", "本服");
            serverInfo.addProperty("online_players", server.getPlayerCount());
            serverInfo.addProperty("max_players", server.getMaxPlayers());
            serverInfo.addProperty("version", server.getServerVersion());
            serverInfo.addProperty("motd", server.getMotd());

            double tps = getTPS(server);
            serverInfo.addProperty("tps", tps);

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

            JsonArray playersArray = new JsonArray();
            for (ServerPlayer player : players) {
                JsonObject p = new JsonObject();
                p.addProperty("name", player.getName().getString());
                p.addProperty("uuid", player.getUUID().toString());
                p.addProperty("is_premium", true);
                playersArray.add(p);
            }
            serverInfo.add("players", playersArray);

            sendSuccess(exchange, serverInfo);
        }
    }

    /**
     * 玩家统计处理器 - GET /api/stats/{player}
     * 注意：路径参数解析在 handler 中处理
     */
    private class StatsHandler extends BaseHandler {
        @Override
        protected void doHandle(HttpExchange exchange) throws Exception {
            if (!validateMethod(exchange, "GET")) return;
            if (!validateAuth(exchange)) return;

            String path = exchange.getRequestURI().getPath();
            String[] segments = path.split("/");
            if (segments.length < 4) {
                throw new InvalidParameterException(ErrorCode.MISSING_PLAYER_NAME);
            }
            String playerName = segments[3];

            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server == null) {
                throw new BusinessException(ErrorCode.SERVER_NOT_AVAILABLE);
            }

            ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
            if (player == null) {
                throw new ResourceNotFoundException(ErrorCode.PLAYER_NOT_FOUND);
            }

            Map<String, Object> statsMap = com.poso.qqbind.utils.StatUtils.getPlayerStats(player);
            JsonObject response = new JsonObject();
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
            sendSuccess(exchange, response);
        }
    }

    /**
     * TPS 处理器 - GET /api/tps
     */
    private class TpsHandler extends BaseHandler {
        @Override
        protected void doHandle(HttpExchange exchange) throws Exception {
            if (!validateMethod(exchange, "GET")) return;
            if (!validateAuth(exchange)) return;

            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server == null) {
                throw new BusinessException(ErrorCode.SERVER_NOT_AVAILABLE);
            }

            double tps = getTPS(server);
            JsonObject data = new JsonObject();
            data.addProperty("tps", tps);
            sendSuccess(exchange, data);
        }
    }

    /**
     * 广播处理器 - POST /api/broadcast
     * 请求体: {"message": "Hello"}
     * 在游戏屏幕中央显示消息（Title）
     */
    private class BroadcastHandler extends BaseHandler {
        @Override
        protected void doHandle(HttpExchange exchange) throws Exception {

            if (!validateMethod(exchange, "POST")) return;
            if (!validateAuth(exchange)) return;

            String body = readRequestBody(exchange);
            JsonObject json = gson.fromJson(body, JsonObject.class);
            String message = json.has("message") ? json.get("message").getAsString() : null;
            if (message == null || message.isEmpty()) {
                throw new InvalidParameterException(ErrorCode.MISSING_PARAMETER);
            }

            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server == null) {
                throw new BusinessException(ErrorCode.SERVER_NOT_AVAILABLE);
            }

            // 构建屏幕中央显示的消息
            Component titleComponent = Component.literal(message);

            // 获取所有在线玩家
            Collection<ServerPlayer> players = server.getPlayerList().getPlayers();
            for (ServerPlayer player : players) {
                // 发送大标题
                player.connection.send(new ClientboundSetTitleTextPacket(titleComponent));
                // 设置动画：淡入10 tick，停留100 tick（5秒），淡出10 tick
                player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 200, 10));
            }

            LOGGER.info("Broadcast title sent to {} players: {}", players.size(), message);

            // 返回成功响应
            JsonObject data = new JsonObject();
            data.addProperty("message", "广播已发送");
            data.addProperty("player_count", players.size());
            sendSuccess(exchange, data);
        }
    }

    /**
     * 令牌验证处理器 - GET /api/validate_token
     * 用于机器人端查询令牌的有效性，并返回对应的 gameId 和 serverId。
     * 请求参数: ?token=123456
     */
    private class ValidateTokenHandler extends BaseHandler {
        @Override
        protected void doHandle(HttpExchange exchange) throws Exception {
            if (!validateMethod(exchange, "GET")) return;
            if (!validateAuth(exchange)) return;

            String query = exchange.getRequestURI().getQuery();
            String token = getQueryParam(query, "token");
            if (token == null || token.isEmpty()) {
                throw new InvalidParameterException(ErrorCode.MISSING_PARAMETER);
            }

            TokenManager.TokenInfo info = TokenManager.validateTokenOnly(token);
            if (info == null) {
                throw new InvalidParameterException(ErrorCode.INVALID_TOKEN);
            }

            JsonObject data = new JsonObject();
            data.addProperty("valid", true);
            data.addProperty("gameId", info.getGameId());
            data.addProperty("serverId", info.getServerId());
            sendSuccess(exchange, data);
        }
    }

    // ========== 工具方法 ==========

    /**
     * 计算当前服务器的 TPS（基于 tickTimes 数组）
     * 兼容 Forge 和 NeoForge（通过反射）
     */
    private double getTPS(MinecraftServer server) {
        if (tickTimesField == null) {
            return 20.0;
        }
        try {
            long[] tickTimes = (long[]) tickTimesField.get(server);
            if (tickTimes == null || tickTimes.length == 0) {
                return 20.0;
            }
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