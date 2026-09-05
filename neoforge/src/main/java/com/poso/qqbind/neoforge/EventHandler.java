package com.poso.qqbind.neoforge;

import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.neoforge.QQBindMod;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.core.CommandExecutor;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 事件监听类，用于处理玩家登录事件以实施绑定验证.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  13:23
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
            CommandExecutor.disconnectPlayer(player, QQBindConfig.KICK_MESSAGE);
        }
    }
}