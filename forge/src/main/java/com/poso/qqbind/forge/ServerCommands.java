package com.poso.qqbind.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.forge.QQBindMod;
import com.poso.qqbind.core.BindingManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 游戏内命令注册类，为服务器管理员提供管理命令.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class ServerCommands {
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
                Commands.literal("qqbind")
                        .then(Commands.literal("reload")
                                .requires(source -> source.hasPermission(4))
                                .executes(this::reload)
                        )
                        .then(Commands.literal("unbind")
                                .requires(source -> source.hasPermission(4))
                                .then(Commands.argument("gameId", StringArgumentType.word())
                                        .executes(this::unbindByGameId)
                                )
                        )
                        .then(Commands.literal("list")
                                .requires(source -> source.hasPermission(4))
                                .executes(this::listBindings)
                        )
        );
    }

    private int reload(CommandContext<CommandSourceStack> context) {
        // 重载配置
        QQBindConfig.reload();
        // 重新加载绑定数据
        BindingManager manager = QQBindMod.getBindingManager();
        if (manager != null) {
            manager.reload();
        }
        context.getSource().sendSuccess(
                () -> Component.literal("§aQQBindMod 已重新加载配置和数据！"),
                true
        );
        return 1;
    }

    private int unbindByGameId(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String gameId = StringArgumentType.getString(context, "gameId");
        BindingManager manager = QQBindMod.getBindingManager();

        if (manager == null) {
            context.getSource().sendFailure(Component.literal("§c绑定管理器未初始化"));
            return 0;
        }

        boolean result = manager.unbindByGameId(gameId);
        if (result) {
            context.getSource().sendSuccess(
                    () -> Component.literal("§a已解绑游戏ID: " + gameId),
                    true
            );
        } else {
            context.getSource().sendFailure(Component.literal("§c未找到游戏ID: " + gameId));
        }
        return result ? 1 : 0;
    }

    private int listBindings(CommandContext<CommandSourceStack> context) {
        BindingManager manager = QQBindMod.getBindingManager();

        if (manager == null) {
            context.getSource().sendFailure(Component.literal("§c绑定管理器未初始化"));
            return 0;
        }

        var bindings = manager.getAllBindings();
        if (bindings.isEmpty()) {
            context.getSource().sendSuccess(
                    () -> Component.literal("§e当前没有绑定记录"),
                    false
            );
            return 1;
        }

        context.getSource().sendSuccess(
                () -> Component.literal("§6=== 绑定列表 (" + bindings.size() + " 条) ==="),
                false
        );
        for (var entry : bindings.entrySet()) {
            context.getSource().sendSuccess(
                    () -> Component.literal("§e" + entry.getKey() + " §7→ §b" + entry.getValue()),
                    false
            );
        }
        return 1;
    }
}