package com.poso.qqbind.neoforge;

import com.mojang.logging.LogUtils;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.core.PlayerActivityManager;
import com.poso.qqbind.core.PlayerStateManager;
import com.poso.qqbind.core.TickTracker;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;


/**
 * 事件监听类，用于处理玩家登录事件以实施绑定验证.
 *
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  13:23
 * @since : 1.0
 */
public class EventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            PlayerActivityManager.onPlayerLogin(player.getUUID(), player.getScoreboardName());
        } catch (Exception e) {
            LOGGER.warn("记录玩家登录活动失败: {}", e.getMessage());
        }
        if (!QQBindConfig.ENABLE_WHITELIST_CHECK) return;

        String gameId = player.getScoreboardName();
        BindingManager bindingManager = QQBindMod.getBindingManager();
        if (bindingManager == null) return;

        if (player.hasPermissions(4)) return;

        // 非阻塞判定（只读缓存/本地镜像），绝不因 DB 查询阻塞主线程 tick。
        // 缓存未热时先按未绑定处理，再异步回源校正，避免误限已绑定玩家。
        if (!bindingManager.isBoundFast(gameId)) {
            // 1. 标记为受限状态（旁观者模式）
            PlayerStateManager.setRestricted(player, true);

            // 2. 发送提示消息（自动生成令牌）
            PlayerStateManager.sendRestrictionMessage(player);

            // 3. 异步回源：若实际已绑定（镜像滞后），解除限制
            bindingManager.verifyBindingAsync(gameId);

            LOGGER.info("玩家 {} 未绑定，已应用限制并发送提示", player.getScoreboardName());
        } else {
            PlayerStateManager.setRestricted(player, false);
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            try {
                PlayerActivityManager.onPlayerLogout(player.getUUID(), player.getScoreboardName());
            } catch (Exception e) {
                LOGGER.warn("记录玩家退出活动失败: {}", e.getMessage());
            }
            PlayerStateManager.removePlayer(player);
        }
    }

    // Pre/Post 之间的耗时就是 /forge tps 用的那个 mspt 样本
    @SubscribeEvent
    public void onServerTickPre(ServerTickEvent.Pre event) {
        TickTracker.onTickStart();
    }

    @SubscribeEvent
    public void onServerTickPost(ServerTickEvent.Post event) {
        TickTracker.onTickEnd();
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        TickTracker.reset();
    }
}