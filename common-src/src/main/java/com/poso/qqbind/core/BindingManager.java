package com.poso.qqbind.core;

import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.server.ServerProviderHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import com.poso.qqbind.storage.DataStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 绑定业务逻辑核心类，负责管理 QQ 号与游戏 ID 的绑定关系.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class BindingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BindingManager.class);
    private final DataStorage storage;

    public BindingManager(DataStorage storage) {
        this.storage = storage;
        storage.load();
    }

    /**
     * 绑定 QQ 号与游戏 ID
     */
    public BindResult bind(String qq, String gameId) {
        // 检查 gameId 是否已被绑定
        String existingQQ = storage.getQQ(gameId);
        if (existingQQ != null) {
            return new BindResult(false, "该游戏ID已被绑定 (QQ: " + existingQQ + ")");
        }

        // 检查 qq 是否已绑定其他 ID
        String existingGameId = storage.getGameId(qq);
        if (existingGameId != null) {
            return new BindResult(false, "该QQ已绑定游戏ID: " + existingGameId);
        }

        // 执行绑定
        storage.save(qq, gameId);

        // 执行 whitelist add 命令
        CommandExecutor.addWhitelist(gameId);

        // ========== 绑定成功后检查玩家是否在线，若在线则解除限制 ==========
        try {
            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server != null) {
                ServerPlayer player = server.getPlayerList().getPlayerByName(gameId);
                if (player != null) {
                    if (PlayerStateManager.isRestricted(player)) {
                        // 解除受限状态，恢复生存模式
                        PlayerStateManager.setRestricted(player, false);
                        LOGGER.info("玩家 {} 绑定成功，已解除限制", player.getScoreboardName());

                        // ---- 使用模板发送绑定成功通知 ----
                        String title = QQBindConfig.formatMessage(QQBindConfig.BIND_SUCCESS_TITLE);
                        String subtitle = QQBindConfig.formatMessage(QQBindConfig.BIND_SUCCESS_SUBTITLE);
                        String actionBar = QQBindConfig.formatMessage(QQBindConfig.BIND_SUCCESS_ACTION_BAR);
                        String chatMsg = QQBindConfig.formatMessage(QQBindConfig.BIND_SUCCESS_CHAT);

                        player.connection.send(new ClientboundSystemChatPacket(
                                Component.literal(chatMsg), false));

                        player.connection.send(new ClientboundSetTitleTextPacket(
                                Component.literal(title)));
                        player.connection.send(new ClientboundSetSubtitleTextPacket(
                                Component.literal(subtitle)));
                        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 10));

                        player.connection.send(new ClientboundSetActionBarTextPacket(
                                Component.literal(actionBar)));
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("绑定成功后解除玩家限制时发生异常（不影响绑定本身）: {}", e.getMessage());
        }

        LOGGER.info("Bound QQ {} to game ID {}", qq, gameId);
        return new BindResult(true, "绑定成功！您现在可以登录服务器了。");
    }

    /**
     * 通过游戏 ID 解绑
     */
    public boolean unbindByGameId(String gameId) {
        String qq = storage.getQQ(gameId);
        if (qq == null) {
            return false;
        }

        // 移除存储数据
        storage.remove(gameId);
        // 移除白名单
        CommandExecutor.removeWhitelist(gameId);

        // ---- 检查玩家是否在线，若在线则立即应用限制 ----
        try {
            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server != null) {
                ServerPlayer player = server.getPlayerList().getPlayerByName(gameId);
                if (player != null) {
                    // 应用限制（旁观者模式 + 提示信息）
                    PlayerStateManager.applyRestriction(player);
                    LOGGER.info("玩家 {} 已被解绑，已应用限制", player.getScoreboardName());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("解绑后限制玩家时发生异常: {}", e.getMessage());
        }

        LOGGER.info("Unbound game ID {} (QQ: {})", gameId, qq);
        return true;
    }

    /**
     * 通过 QQ 号解绑
     */
    public boolean unbindByQQ(String qq) {
        String gameId = storage.getGameId(qq);
        if (gameId == null) {
            return false;
        }

        // 移除存储数据
        storage.remove(gameId);
        // 移除白名单
        CommandExecutor.removeWhitelist(gameId);

        // ---- 检查玩家是否在线，若在线则立即应用限制 ----
        try {
            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server != null) {
                ServerPlayer player = server.getPlayerList().getPlayerByName(gameId);
                if (player != null) {
                    // 应用限制（旁观者模式 + 提示信息）
                    PlayerStateManager.applyRestriction(player);
                    LOGGER.info("玩家 {} 已被解绑，已应用限制", player.getScoreboardName());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("解绑后限制玩家时发生异常: {}", e.getMessage());
        }

        LOGGER.info("Unbound QQ {} (game ID: {})", qq, gameId);
        return true;
    }

    /**
     * 检查游戏 ID 是否已绑定
     */
    public boolean isBound(String gameId) {
        return storage.getQQ(gameId) != null;
    }

    /**
     * 获取游戏 ID 绑定的 QQ 号
     */
    public String getQQ(String gameId) {
        return storage.getQQ(gameId);
    }

    /**
     * 通过 QQ 号获取绑定的游戏 ID
     */
    public String getGameIdByQQ(String qq) {
        return storage.getGameId(qq);
    }

    /**
     * 获取所有绑定数据
     */
    public Map<String, String> getAllBindings() {
        return storage.getAll();
    }

    /**
     * 重新加载数据
     */
    public void reload() {
        storage.load();
        LOGGER.info("Binding data reloaded");
    }

    public static class BindResult {
        public final boolean success;
        public final String message;

        public BindResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}