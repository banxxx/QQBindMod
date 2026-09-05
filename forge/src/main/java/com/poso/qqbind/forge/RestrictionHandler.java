package com.poso.qqbind.forge;

import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.PlayerStateManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerContainerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限制未绑定玩家的所有操作 (Forge 版)
 * 移动检测 + 操作拦截 + 持续提示（Title / Subtitle / ActionBar 每5秒刷新）
 */
public class RestrictionHandler {
    private final Map<UUID, BlockPos> lastPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> tickCounts = new ConcurrentHashMap<>();
    private static final int REMINDER_INTERVAL = 100; // 每 100 tick（5秒）刷新一次标题

    /**
     * 发送完整的提示信息（Title + Subtitle + ActionBar），使用配置模板
     */
    private void sendFullReminder(ServerPlayer player) {
        String title = QQBindConfig.formatMessage(QQBindConfig.TITLE_TEMPLATE);
        String subtitle = QQBindConfig.formatMessage(QQBindConfig.SUBTITLE_TEMPLATE);
        String actionBar = QQBindConfig.formatMessage(QQBindConfig.ACTION_BAR_TEMPLATE);

        // Title + Subtitle
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        // 动画参数：淡入10tick，停留200tick，淡出20tick
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 200, 20));

        // ActionBar
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(actionBar)));
    }

    /**
     * 发送 ActionBar 提示（用于操作拦截时的即时反馈）
     */
    private void sendActionBarReminder(ServerPlayer player) {
        String actionBar = QQBindConfig.formatMessage(QQBindConfig.ACTION_BAR_TEMPLATE);
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(actionBar)));
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.side != LogicalSide.SERVER) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        // 如果玩家已解除限制，清理记录并返回
        if (!PlayerStateManager.isRestricted(player)) {
            lastPositions.remove(player.getUUID());
            tickCounts.remove(player.getUUID());
            return;
        }

        // ---- 移动检测 ----
        BlockPos currentPos = player.blockPosition();
        BlockPos lastPos = lastPositions.get(player.getUUID());
        if (lastPos != null && !currentPos.equals(lastPos)) {
            // 拉回原位
            player.connection.teleport(
                    lastPos.getX() + 0.5,
                    lastPos.getY(),
                    lastPos.getZ() + 0.5,
                    player.getYRot(),
                    player.getXRot()
            );
            sendActionBarReminder(player);
        } else if (lastPos == null) {
            lastPositions.put(player.getUUID(), currentPos);
        }

        // ---- 定时刷新完整提示（Title + Subtitle + ActionBar） ----
        int count = tickCounts.getOrDefault(player.getUUID(), 0);
        if (count >= REMINDER_INTERVAL) {
            sendFullReminder(player);
            tickCounts.put(player.getUUID(), 0);
        } else {
            tickCounts.put(player.getUUID(), count + 1);
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        ServerPlayer player = (ServerPlayer) event.getPlayer();
        if (player != null && PlayerStateManager.isRestricted(player)) {
            event.setCanceled(true);
            sendActionBarReminder(player);
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        // 此事件不可取消，但不会影响限制效果
    }

    @SubscribeEvent
    public void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        // 此事件不可取消，但不会影响限制效果
    }

    /**
     * 拦截容器打开事件（包括物品栏、箱子等）
     */
    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (PlayerStateManager.isRestricted(player)) {
            event.setCanceled(true);
            // 使用聊天模板发送提示
            String chatMsg = QQBindConfig.formatMessage(QQBindConfig.CHAT_TEMPLATE);
            player.connection.send(new ClientboundSystemChatPacket(
                    Component.literal(chatMsg), false));
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            lastPositions.remove(player.getUUID());
            tickCounts.remove(player.getUUID());
        }
    }
}