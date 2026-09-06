package com.poso.qqbind.forge;

import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.PlayerStateManager;
import com.poso.qqbind.core.TokenManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
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
 * 提示信息中包含动态令牌，令牌过期自动刷新。
 */
public class RestrictionHandler {
    private final Map<UUID, BlockPos> lastPositions = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> tickCounts = new ConcurrentHashMap<>();
    private static final int REMINDER_INTERVAL = 100; // 每 100 tick（5秒）刷新一次

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.side != LogicalSide.SERVER) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        if (!PlayerStateManager.isRestricted(player)) {
            lastPositions.remove(player.getUUID());
            tickCounts.remove(player.getUUID());
            return;
        }

        // ---- 移动检测 ----
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
            // 操作拦截时发送 ActionBar
            PlayerStateManager.sendActionBarReminder(player);
        } else if (lastPos == null) {
            lastPositions.put(player.getUUID(), currentPos);
        }

        // ---- 定时刷新完整提示（使用 sendRestrictionMessage，内部刷新令牌） ----
        int count = tickCounts.getOrDefault(player.getUUID(), 0);
        if (count >= REMINDER_INTERVAL) {
            PlayerStateManager.sendRestrictionMessage(player);
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
            PlayerStateManager.sendActionBarReminder(player);
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                PlayerStateManager.sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                PlayerStateManager.sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                PlayerStateManager.sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                PlayerStateManager.sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                event.setCanceled(true);
                PlayerStateManager.sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        // 不可取消
    }

    @SubscribeEvent
    public void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        // 不可取消
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            if (PlayerStateManager.isRestricted(player)) {
                player.closeContainer();
                PlayerStateManager.sendActionBarReminder(player);
            }
        }
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        if (PlayerStateManager.isRestricted(player)) {
            // 取消聊天消息，阻止发送
            event.setCanceled(true);
            // 仅发送 ActionBar 提示（不占用聊天栏）
            PlayerStateManager.sendActionBarReminder(player);
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