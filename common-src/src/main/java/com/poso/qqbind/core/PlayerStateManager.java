package com.poso.qqbind.core;

import com.poso.qqbind.QQBindConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
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

    // 存储受限玩家的 UUID，使用 ConcurrentHashMap 保证线程安全
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
            // 将玩家游戏模式改为旁观者，无法交互
            player.setGameMode(GameType.SPECTATOR);
            LOGGER.info("玩家 {} 已被限制（未绑定），切换为旁观者模式", player.getScoreboardName());
        } else {
            restrictedPlayers.remove(uuid);
            // 恢复为生存模式
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
    }

    /**
     * 应用限制状态并发送提示信息（使用配置模板）。
     * 用于玩家登录时或解绑后重新限制。
     * @param player 目标玩家
     */
    public static void applyRestriction(ServerPlayer player) {
        // 设置为受限状态（旁观者模式）
        setRestricted(player, true);

        // ---- 使用配置模板发送完整提示信息 ----
        String title = QQBindConfig.formatMessage(QQBindConfig.TITLE_TEMPLATE);
        String subtitle = QQBindConfig.formatMessage(QQBindConfig.SUBTITLE_TEMPLATE);
        String actionBar = QQBindConfig.formatMessage(QQBindConfig.ACTION_BAR_TEMPLATE);
        String chatMsg = QQBindConfig.formatMessage(QQBindConfig.CHAT_TEMPLATE);

        // Title（大标题）
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        // 显示时长：淡入 10 tick，停留 100 tick，淡出 20 tick
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 100, 20));

        // ActionBar（底部提示）
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(actionBar)));

        // Chat 消息
        player.connection.send(new ClientboundSystemChatPacket(Component.literal(chatMsg), false));

        LOGGER.info("已向玩家 {} 发送受限提示", player.getScoreboardName());
    }
}