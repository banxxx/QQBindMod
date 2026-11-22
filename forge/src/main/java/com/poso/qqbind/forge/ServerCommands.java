package com.poso.qqbind.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.core.BindingBootstrap;
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
                        .then(Commands.literal("operation")
                                .requires(source -> source.hasPermission(4))
                                .then(Commands.argument("operationId",StringArgumentType.word())
                                        .executes(ctx -> officialCommand(ctx,"operation",StringArgumentType.getString(ctx,"operationId")))
                                        .then(Commands.literal("retry").executes(ctx -> officialCommand(ctx,"retry",StringArgumentType.getString(ctx,"operationId"))))
                                        .then(Commands.literal("cancel")
                                                .executes(ctx -> officialCommand(ctx,"cancel",StringArgumentType.getString(ctx,"operationId"))))))
                        .then(Commands.literal("reconcile")
                                .requires(source -> source.hasPermission(4))
                                .executes(context -> {
                                    boolean queued=BindingBootstrap.requestOfficialReconcile();
                                    if (queued) context.getSource().sendSuccess(() -> Component.literal("官方镜像与预占对账已排队；使用 /qqbind status 查看状态。未决绑定不会自动提交。"),false);
                                    else context.getSource().sendFailure(Component.literal("官方绑定运行时尚未就绪。"));
                                    return queued ? 1 : 0;
                                }))
                        .then(Commands.literal("status")
                                .requires(source -> source.hasPermission(4))
                                .executes(context -> {
                                    context.getSource().sendSuccess(() -> Component.literal(BindingBootstrap.statusSummary()),false);
                                    return 1;
                                }))
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
        if (QQBindConfig.getLoadError() != null) {
            context.getSource().sendFailure(Component.literal(QQBindConfig.getLoadError()));
            return 0;
        }
        // 重新加载绑定数据
        BindingManager manager = QQBindMod.getBindingManager();
        if (manager != null) {
            manager.reload();
        }
        context.getSource().sendSuccess(
                () -> Component.literal("§aQQBindMod 配置已重载，绑定数据正在后台重新加载（含数据库校验）..."),
                true
        );
        return 1;
    }

    private int unbindByGameId(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String gameId = StringArgumentType.getString(context, "gameId");
        if (QQBindConfig.isOfficialBindingSelected()) return officialCommand(context,"unbind",gameId);
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
        if (QQBindConfig.isOfficialBindingSelected()) return officialCommand(context,"list","");
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
    private static int officialCommand(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context, String action, String argument) {
        var source=context.getSource();
        var entity=source.getEntity();
        var player=entity instanceof net.minecraft.server.level.ServerPlayer p ? p : null;
        String actor=player == null ? "console" : player.getUUID().toString();
        java.util.function.BooleanSupplier permission=() -> player == null ? source.hasPermission(4)
            : source.getServer().getPlayerList().getPlayer(player.getUUID()) == player && player.createCommandSourceStack().hasPermission(4);
        boolean queued=BindingBootstrap.officialAdmin(action,argument,actor,permission,
            message -> source.sendSuccess(() -> Component.literal(message),false));
        if (queued) source.sendSuccess(() -> Component.literal("官方操作已排队；确定结果将在后台处理后返回。"),false);
        else source.sendFailure(Component.literal("官方操作未排队：服务未就绪、权限不足或队列已满。"));
        return queued ? 1 : 0;
    }
}
