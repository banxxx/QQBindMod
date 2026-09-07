package com.poso.qqbind.fabric;

import com.poso.qqbind.core.PlayerStateManager;
import net.fabricmc.fabric.api.event.player.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric 版限制未绑定玩家操作的处理器。
 * 使用 Fabric API 提供的各种回调拦截玩家的交互、移动、聊天等行为。
 */
public class FabricRestrictionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("qqbind-fabric-restrict");

    private static final Map<UUID, BlockPos> lastPositions = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> tickCounts = new ConcurrentHashMap<>();
    private static final int REMINDER_INTERVAL = 100; // 5 秒

    /**
     * 注册所有事件回调，应在模组初始化时调用一次。
     */
    public static void register() {
        // ===== 1. 移动检测与定时提醒 =====
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!PlayerStateManager.isRestricted(player)) {
                    lastPositions.remove(player.getUUID());
                    tickCounts.remove(player.getUUID());
                    continue;
                }

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
                    PlayerStateManager.sendActionBarReminder(player);
                } else if (lastPos == null) {
                    lastPositions.put(player.getUUID(), currentPos);
                }

                int count = tickCounts.getOrDefault(player.getUUID(), 0);
                if (count >= REMINDER_INTERVAL) {
                    PlayerStateManager.sendRestrictionMessage(player);
                    tickCounts.put(player.getUUID(), 0);
                } else {
                    tickCounts.put(player.getUUID(), count + 1);
                }
            }
        });

        // ===== 2. 交互拦截 =====

        // 2.1 使用方块（右键点击方块）—— 拦截容器打开、放置方块等
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer sp = (ServerPlayer) player;
                if (PlayerStateManager.isRestricted(sp)) {
                    PlayerStateManager.sendActionBarReminder(sp);
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // 2.2 使用物品（右键使用物品）—— 拦截使用物品（如吃东西、投掷等）
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer sp = (ServerPlayer) player;
                if (PlayerStateManager.isRestricted(sp)) {
                    PlayerStateManager.sendActionBarReminder(sp);
                    return InteractionResultHolder.fail(player.getItemInHand(hand));
                }
            }
            return InteractionResultHolder.pass(ItemStack.EMPTY);
        });

        // 2.3 攻击方块（左键）—— 拦截破坏方块
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer sp = (ServerPlayer) player;
                if (PlayerStateManager.isRestricted(sp)) {
                    PlayerStateManager.sendActionBarReminder(sp);
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // 2.4 攻击实体（左键）—— 拦截攻击生物/玩家
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer sp = (ServerPlayer) player;
                if (PlayerStateManager.isRestricted(sp)) {
                    PlayerStateManager.sendActionBarReminder(sp);
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // 2.5 右键点击实体（如村民交易、骑乘等）—— 拦截与实体的交互
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer) {
                ServerPlayer sp = (ServerPlayer) player;
                if (PlayerStateManager.isRestricted(sp)) {
                    PlayerStateManager.sendActionBarReminder(sp);
                    return InteractionResult.FAIL;
                }
            }
            return InteractionResult.PASS;
        });

        // ===== 3. 聊天拦截（使用 ALLOW_CHAT_MESSAGE 阻止消息） =====
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (sender != null && PlayerStateManager.isRestricted(sender)) {
                PlayerStateManager.sendActionBarReminder(sender);
                return false; // 阻止消息发送
            }
            return true;
        });

        // ===== 4. 玩家登出清理缓存 =====
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.getPlayer();
            lastPositions.remove(player.getUUID());
            tickCounts.remove(player.getUUID());
        });

        LOGGER.info("FabricRestrictionHandler registered all event callbacks.");
    }
}