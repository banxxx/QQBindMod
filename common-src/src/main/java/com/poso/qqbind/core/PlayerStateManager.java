package com.poso.qqbind.core;

import com.poso.qqbind.QQBindConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家状态管理类，用于跟踪玩家是否处于“受限”状态（未绑定）。
 * 纯服务端实现，通过游戏模式（旁观者）和标记共同限制操作。
 */
public class PlayerStateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerStateManager.class);

    // 存储受限玩家的 UUID
    private static final Map<UUID, Boolean> restrictedPlayers = new ConcurrentHashMap<>();

    /**
     * 设置玩家的受限状态。
     * @param player   目标玩家
     * @param restricted true 表示限制，false 表示解除限制
     */
    public static void setRestricted(ServerPlayer player, boolean restricted) {
        UUID uuid = player.getUUID();
        if (restricted) {
            restrictedPlayers.put(uuid, true);
            player.setGameMode(GameType.SPECTATOR);
            LOGGER.info("玩家 {} 已被限制（未绑定），切换为旁观者模式", player.getScoreboardName());
        } else {
            restrictedPlayers.remove(uuid);
            player.setGameMode(GameType.SURVIVAL);
            LOGGER.info("玩家 {} 解除限制，恢复生存模式", player.getScoreboardName());
        }
    }

    /**
     * 检查玩家是否处于受限状态。
     */
    public static boolean isRestricted(ServerPlayer player) {
        return restrictedPlayers.containsKey(player.getUUID());
    }

    /**
     * 玩家退出时清理状态。
     */
    public static void removePlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (restrictedPlayers.remove(uuid) != null) {
            LOGGER.info("玩家 {} 退出，已清理受限状态", player.getScoreboardName());
        }
        // 同时清理令牌
        TokenManager.removeTokenForPlayer(player);
    }

    /**
     * 向受限玩家发送完整提示消息（Title + Subtitle + ActionBar + Chat），
     * 包含令牌信息。此方法自动获取或刷新令牌。
     * @param player 目标玩家
     */
    public static void sendRestrictionMessage(ServerPlayer player) {
        // 获取或刷新令牌
        String token = TokenManager.getOrRefreshToken(player);

        // 构建消息
        String title = QQBindConfig.formatMessage(QQBindConfig.TITLE_TEMPLATE, token);
        String subtitle = QQBindConfig.formatMessage(QQBindConfig.SUBTITLE_TEMPLATE, token);
        String actionBar = QQBindConfig.formatMessage(QQBindConfig.ACTION_BAR_TEMPLATE, token);

        // 发送 Title
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 100, 20));

        // ActionBar
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(actionBar)));

    }

    /**
     * 发送简短的 ActionBar 提醒（用于操作拦截时快速反馈）
     */
    public static void sendActionBarReminder(ServerPlayer player) {
        String token = TokenManager.getOrRefreshToken(player);
        String actionBar = QQBindConfig.formatMessage(QQBindConfig.ACTION_BAR_TEMPLATE, token);
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(actionBar)));
    }
}