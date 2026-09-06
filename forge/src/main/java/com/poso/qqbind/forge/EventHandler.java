package com.poso.qqbind.forge;

import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.core.PlayerStateManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import static com.mojang.text2speech.Narrator.LOGGER;

/**
 * 事件监听类，用于处理玩家登录事件以实施绑定验证.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class EventHandler {
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!QQBindConfig.ENABLE_WHITELIST_CHECK) return;

        String gameId = player.getScoreboardName();
        BindingManager bindingManager = QQBindMod.getBindingManager();
        if (bindingManager == null) return;

        if (player.hasPermissions(4)) return;

        if (!bindingManager.isBound(gameId)) {
            // 1. 标记为受限状态（旁观者模式）
            PlayerStateManager.setRestricted(player, true);

            // 2. 发送提示消息（自动生成令牌）
            PlayerStateManager.sendRestrictionMessage(player);

            LOGGER.info("玩家 {} 未绑定，已应用限制并发送提示", player.getScoreboardName());
        } else {
            // 已绑定玩家，确保不受限制（若之前因故被限制）
            if (PlayerStateManager.isRestricted(player)) {
                PlayerStateManager.setRestricted(player, false);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerStateManager.removePlayer(player);
        }
    }
}