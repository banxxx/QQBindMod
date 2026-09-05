package com.poso.qqbind.neoforge;

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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限制未绑定玩家的所有操作 (NeoForge 版)
 * 移动检测 + 操作拦截 + 持续提示（Title / Subtitle / ActionBar 每5秒刷新）
 */
public class RestrictionHandler {
    private final Map<UUID, BlockPos> lastPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> tickCounts = new ConcurrentHashMap<>();
    private static final int REMINDER_INTERVAL = 100; // 每 100 tick（5秒）刷新一次标题

    private void sendFullReminder(ServerPlayer player) {
        String title = QQBindConfig.formatMessage(QQBindConfig.TITLE_TEMPLATE);
        String subtitle = QQBindConfig.formatMessage(QQBindConfig.SUBTITLE_TEMPLATE);
        String actionBar = QQBindConfig.formatMessage(QQBindConfig.ACTION_BAR_TEMPLATE);

        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 200, 20));
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(actionBar)));
    }

    private void sendActionBarReminder(ServerPlayer player) {
        String actionBar = QQBindConfig.formatMessage(QQBindConfig.ACTION_BAR_TEMPLATE);
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(actionBar)));
    }

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        if (!PlayerStateManager.isRestricted(player)) {
            lastPositions.remove(player.getUUID());
            tickCounts.remove(player.getUUID());
            return;
        }

        // 移动检测
        BlockPos currentPos = player.blockPosition();
        BlockPos lastPos = lastPositions.get(player.getUUID());
        if (lastPos != null && !currentPos.equals(lastPos)) {
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

        // 定时刷新完整提示
        int count = tickCounts.getOrDefault(player.getUUID(), 0);
        if (count >= REMINDER_INTERVAL) {
            sendFullReminder(player);
            tickCounts.put(player.getUUID(), 0);
        } else {
            tickCounts.put(player.getUUID(), count + 1);
        }
    }

    /**
     * 拦截方块破坏
     */
    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        ServerPlayer player = (ServerPlayer) event.getPlayer();
        if (PlayerStateManager.isRestricted(player)) {
            event.setCanceled(true);
            sendActionBarReminder(player);
        }
    }

    /**
     * 拦截方块放置
     */
    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                sendActionBarReminder(player);
            }
        }
    }

    /**
     * 拦截右键点击方块（包括箱子、工作台等容器方块）
     * 这是阻止容器打开的主要手段
     */
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                sendActionBarReminder(player);
            }
        }
    }

    /**
     * 拦截右键点击物品
     */
    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                sendActionBarReminder(player);
            }
        }
    }

    /**
     * 拦截右键点击实体（如打开马的背包、村民交易等）
     */
    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                sendActionBarReminder(player);
            }
        }
    }

    /**
     * 拦截左键点击方块
     */
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
        // 不可取消，不影响限制
    }

    @SubscribeEvent
    public void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        // 不可取消，不影响限制
    }

    /**
     * 容器打开事件（后置处理，作为兜底）
     * PlayerContainerEvent.Open 没有 setCanceled 方法，
     * 只能用 closeContainer() 强制关闭，用于处理按 E 键打开物品栏等情况
     */
    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                // 强制关闭容器（包括物品栏）
                player.closeContainer();
                sendActionBarReminder(player);
            }
        }
    }

    /**
     * 拦截聊天消息
     */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                String chatMsg = QQBindConfig.formatMessage(QQBindConfig.CHAT_TEMPLATE);
                player.connection.send(new ClientboundSystemChatPacket(
                        Component.literal(chatMsg), false));
            }
        }
    }

    /**
     * 玩家退出时清理位置记录
     */
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            lastPositions.remove(player.getUUID());
            tickCounts.remove(player.getUUID());
        }
    }
}