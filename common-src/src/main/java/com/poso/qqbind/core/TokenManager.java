package com.poso.qqbind.core;

import com.poso.qqbind.QQBindConfig;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 令牌管理类，负责生成、存储、验证和刷新一次性绑定令牌。
 * 每个令牌与玩家、服务器、有效期绑定，过期后自动失效。
 * 玩家登录时自动生成令牌，令牌过期后无需重新登录即可刷新。
 */
public class TokenManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TokenManager.class);
    private static final long TOKEN_VALIDITY_MS = 5 * 60 * 1000; // 5 分钟
    private static final int TOKEN_LENGTH = 6; // 6 位数字

    // 存储所有有效令牌（token -> TokenInfo）
    private static final Map<String, TokenInfo> tokenMap = new ConcurrentHashMap<>();
    // 快速查找玩家当前令牌（playerUUID -> token）
    private static final Map<UUID, String> playerTokenCache = new ConcurrentHashMap<>();

    /**
     * 令牌信息内部类
     */
    public static class TokenInfo {
        final String token;
        final String gameId;
        final String serverId;
        final long createdAt;
        final long expiresAt;
        boolean used;

        public String getGameId() { return gameId; }
        public String getServerId() { return serverId; }

        TokenInfo(String token, String gameId, String serverId) {
            this.token = token;
            this.gameId = gameId;
            this.serverId = serverId;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = this.createdAt + TOKEN_VALIDITY_MS;
            this.used = false;
        }

        boolean isValid(String serverId) {
            return !used && expiresAt > System.currentTimeMillis() && this.serverId.equals(serverId);
        }

        void markUsed() {
            this.used = true;
        }
    }

    /**
     * 获取或刷新玩家的有效令牌。
     * 若玩家已有有效令牌（未过期且未使用），则直接返回；
     * 否则生成新令牌并返回。
     * @param player 目标玩家
     * @return 令牌字符串
     */
    public static String getOrRefreshToken(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String existingToken = playerTokenCache.get(uuid);
        // 检查现有令牌是否仍有效
        if (existingToken != null) {
            TokenInfo info = tokenMap.get(existingToken);
            if (info != null && info.isValid(QQBindConfig.SERVER_ID)) {
                return existingToken;
            } else {
                // 失效，移除旧记录
                invalidateToken(player);
            }
        }
        // 生成新令牌
        return generateNewToken(player);
    }

    /**
     * 生成新的令牌并存储。
     * @param player 目标玩家
     * @return 新令牌
     */
    private static String generateNewToken(ServerPlayer player) {
        // 使旧令牌失效
        invalidateToken(player);

        String token;
        do {
            token = generateRandomToken();
        } while (tokenMap.containsKey(token)); // 极小概率碰撞，循环直到唯一

        TokenInfo info = new TokenInfo(token, player.getScoreboardName(), QQBindConfig.SERVER_ID);
        tokenMap.put(token, info);
        playerTokenCache.put(player.getUUID(), token);
        LOGGER.info("Generated new token {} for player {}", token, player.getScoreboardName());
        return token;
    }

    /**
     * 生成 6 位随机数字令牌
     */
    private static String generateRandomToken() {
        // 使用 UUID 生成随机数字，取绝对值后取模 10^6
        int rand = Math.abs(UUID.randomUUID().hashCode()) % 1_000_000;
        return String.format("%06d", rand);
    }

    /**
     * 使玩家的旧令牌失效（从 map 和 cache 中移除）
     */
    private static void invalidateToken(ServerPlayer player) {
        UUID uuid = player.getUUID();
        String oldToken = playerTokenCache.remove(uuid);
        if (oldToken != null) {
            TokenInfo info = tokenMap.remove(oldToken);
            if (info != null) {
                info.markUsed();
                LOGGER.info("Invalidated old token {} for player {}", oldToken, player.getScoreboardName());
            }
        }
    }

    /**
     * 验证令牌是否有效。
     * 若有效，返回对应的游戏 ID；否则返回 null。
     * 同时会标记令牌为已使用（但不会立即移除，以便后续日志追踪）。
     * @param token 令牌
     * @param qq 用于绑定的 QQ 号（仅用于日志）
     * @return 若有效返回 gameId，否则返回 null
     */
    public static String validateAndUseToken(String token, String qq) {
        TokenInfo info = tokenMap.get(token);
        if (info == null) {
            LOGGER.warn("Token {} not found", token);
            return null;
        }
        if (!info.isValid(QQBindConfig.SERVER_ID)) {
            LOGGER.warn("Token {} is invalid (expired, used, or wrong server)", token);
            return null;
        }
        // 标记为已使用并移除
        info.markUsed();
        tokenMap.remove(token);
        // 清除玩家缓存（如果正好是该玩家的令牌）
        for (Map.Entry<UUID, String> entry : playerTokenCache.entrySet()) {
            if (entry.getValue().equals(token)) {
                playerTokenCache.remove(entry.getKey());
                break;
            }
        }
        LOGGER.info("Token {} validated and used for binding QQ {} to gameId {}", token, qq, info.gameId);
        return info.gameId;
    }

    /**
     * 清理所有过期令牌（定期调用）
     */
    public static void cleanupExpiredTokens() {
        long now = System.currentTimeMillis();
        tokenMap.entrySet().removeIf(entry -> {
            TokenInfo info = entry.getValue();
            if (info.expiresAt < now) {
                // 同时清理玩家缓存
                playerTokenCache.entrySet().removeIf(cacheEntry -> cacheEntry.getValue().equals(info.token));
                return true;
            }
            return false;
        });
    }

    /**
     * 获取玩家当前有效的令牌（可能返回 null）
     */
    public static String getCurrentToken(ServerPlayer player) {
        return playerTokenCache.get(player.getUUID());
    }

    /**
     * 强制移除玩家的令牌（用于玩家退出时清理）
     */
    public static void removeTokenForPlayer(ServerPlayer player) {
        invalidateToken(player);
    }

    /**
     * 仅验证令牌是否有效，不消耗令牌。
     * 用于机器人端定位令牌所属的服务器。
     * @param token 令牌
     * @return 若有效则返回 TokenInfo（包含 gameId 和 serverId），否则返回 null
     */
    public static TokenInfo validateTokenOnly(String token) {
        TokenInfo info = tokenMap.get(token);
        if (info == null) {
            return null;
        }
        if (!info.isValid(QQBindConfig.SERVER_ID)) {
            return null;
        }
        return info;
    }
}