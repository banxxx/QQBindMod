package com.poso.qqbind.forge;

import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.forge.QQBindMod;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.core.CommandExecutor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

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
            // 使用配置中的自定义消息，支持颜色代码和换行
            CommandExecutor.disconnectPlayer(player, QQBindConfig.KICK_MESSAGE);
        }
    }
}