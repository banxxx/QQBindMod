package com.poso.qqbind.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.core.BindingBootstrap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Fabric 版服务器命令注册，提供 /qqbind reload, unbind, list 等管理命令。
 */
public class FabricServerCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("qqbind")
                        .then(Commands.literal("operation")
                                .requires(source -> source.hasPermission(4))
                                .then(Commands.argument("operationId",StringArgumentType.word())
                                        .executes(ctx -> officialCommand(ctx,"operation",StringArgumentType.getString(ctx,"operationId")))
                                        .then(Commands.literal("retry").executes(ctx -> officialCommand(ctx,"retry",StringArgumentType.getString(ctx,"operationId"))))
                                        .then(Commands.literal("cancel")
                                                .executes(ctx -> officialCommand(ctx,"cancel",StringArgumentType.getString(ctx,"operationId"))))))
                .then(Commands.literal("reconcile")
                        .requires(source -> source.hasPermission(4))
                        .executes(ctx -> {
                            boolean queued=BindingBootstrap.requestOfficialReconcile();
                            if (queued) ctx.getSource().sendSuccess(() -> Component.literal("官方镜像与预占对账已排队；使用 /qqbind status 查看状态。未决绑定不会自动提交。"),false);
                            else ctx.getSource().sendFailure(Component.literal("官方绑定运行时尚未就绪。"));
                            return queued ? 1 : 0;
                        }))
                .then(Commands.literal("status")
                        .requires(source -> source.hasPermission(4))
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(BindingBootstrap.statusSummary()), false);
                            return 1;
                        }))
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(4)) // OP 权限
                        .executes(ctx -> {
                            QQBindConfig.reload();
                            if (QQBindConfig.getLoadError() != null) {
                                ctx.getSource().sendFailure(Component.literal(QQBindConfig.getLoadError()));
                                return 0;
                            }
                            BindingManager manager = FabricModInitializer.getBindingManager();
                            if (manager != null) {
                                manager.reload();
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§aQQBindMod 配置已重载，绑定数据正在后台重新加载（含数据库校验）..."), true);
                            return 1;
                        })
                )
                .then(Commands.literal("unbind")
                        .requires(source -> source.hasPermission(4))
                        .then(Commands.argument("gameId", StringArgumentType.word())
                                .executes(ctx -> {
                                    String gameId = StringArgumentType.getString(ctx, "gameId");
                                    if (QQBindConfig.isOfficialBindingSelected()) return officialCommand(ctx,"unbind",gameId);
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
                            if (QQBindConfig.isOfficialBindingSelected()) return officialCommand(ctx,"list","");
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
