package com.poso.qqbind.fabric;

import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.core.PlayerStateManager;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 玩家登录/登出事件处理（Fabric 版）。
 * 在玩家加入服务器时检查绑定状态，未绑定则限制其操作并发送绑定提示。
 */
public class FabricEventHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("qqbind-fabric-event");

    /**
     * 玩家登录时调用
     */
    public static void onPlayerLogin(ServerPlayer player) {
        if (!QQBindConfig.ENABLE_WHITELIST_CHECK) return;

        String gameId = player.getScoreboardName();
        BindingManager manager = FabricModInitializer.getBindingManager();
        if (manager == null) return;

        // 跳过 OP 玩家（权限等级 >= 4）
        if (player.hasPermissions(4)) return;

        if (!manager.isBound(gameId)) {
            // 未绑定 → 限制
            PlayerStateManager.setRestricted(player, true);
            PlayerStateManager.sendRestrictionMessage(player);
            LOGGER.info("玩家 {} 未绑定，已应用限制并发送提示", gameId);
        } else {
            // 已绑定，但若之前因某种原因仍处于受限状态，解除限制
            if (PlayerStateManager.isRestricted(player)) {
                PlayerStateManager.setRestricted(player, false);
                LOGGER.info("玩家 {} 已绑定，解除限制", gameId);
            }
        }
    }

    /**
     * 玩家登出时清理资源
     */
    public static void onPlayerLogout(ServerPlayer player) {
        PlayerStateManager.removePlayer(player);
    }
}