package com.poso.qqbind.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.BindingManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Fabric 版服务器命令注册，提供 /qqbind reload, unbind, list 等管理命令。
 */
public class FabricServerCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("qqbind")
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(4)) // OP 权限
                        .executes(ctx -> {
                            QQBindConfig.reload();
                            BindingManager manager = FabricModInitializer.getBindingManager();
                            if (manager != null) {
                                manager.reload();
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§aQQBindMod 已重新加载配置和数据！"), true);
                            return 1;
                        })
                )
                .then(Commands.literal("unbind")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.argument("gameId", StringArgumentType.word())
                                .executes(ctx -> {
                                    String gameId = StringArgumentType.getString(ctx, "gameId");
                                    BindingManager manager = FabricModInitializer.getBindingManager();
                                    if (manager == null) {
                                        ctx.getSource().sendFailure(Component.literal("§c绑定管理器未初始化"));
                                        return 0;
                                    }
                                    boolean result = manager.unbindByGameId(gameId);
                                    if (result) {
                                        ctx.getSource().sendSuccess(() -> Component.literal("§a已解绑游戏ID: " + gameId), true);
                                    } else {
                                        ctx.getSource().sendFailure(Component.literal("§c未找到游戏ID: " + gameId));
                                    }
                                    return result ? 1 : 0;
                                })
                        )
                )
                .then(Commands.literal("list")
                        .requires(source -> source.hasPermission(4))
                        .executes(ctx -> {
                            BindingManager manager = FabricModInitializer.getBindingManager();
                            if (manager == null) {
                                ctx.getSource().sendFailure(Component.literal("§c绑定管理器未初始化"));
                                return 0;
                            }
                            var bindings = manager.getAllBindings();
                            if (bindings.isEmpty()) {
                                ctx.getSource().sendSuccess(() -> Component.literal("§e当前没有绑定记录"), false);
                                return 1;
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§6=== 绑定列表 (" + bindings.size() + " 条) ==="), false);
                            bindings.forEach((gameId, qq) ->
                                    ctx.getSource().sendSuccess(() -> Component.literal("§e" + gameId + " §7→ §b" + qq), false)
                            );
                            return 1;
                        })
                )
        );
    }
}