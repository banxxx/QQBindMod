package com.poso.qqbind.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.poso.qqbind.server.ServerProviderHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务器命令执行工具类，提供执行 Minecraft 原生命令和踢出玩家的功能.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class CommandExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandExecutor.class);

    public static void addWhitelist(String gameId) {
        executeCommand("whitelist add " + gameId);
    }

    public static void removeWhitelist(String gameId) {
        executeCommand("whitelist remove " + gameId);
    }

    private static void executeCommand(String command) {
        try {
            MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
            if (server == null) {
                LOGGER.warn("Server not available, cannot execute command: {}", command);
                return;
            }

            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            CommandSourceStack source = server.createCommandSourceStack();

            ParseResults<CommandSourceStack> parseResults = dispatcher.parse(command, source);
            dispatcher.execute(parseResults);

            LOGGER.info("Executed command: {}", command);
        } catch (Exception e) {
            LOGGER.error("Failed to execute command: {}", command, e);
        }
    }

    /**
     * 断开玩家连接并显示提示（使用 Component）
     */
    public static void disconnectPlayer(ServerPlayer player, String message) {
        // 使用 Component.literal() 创建文本，并支持颜色代码
        Component kickMessage = Component.literal(message);
        player.connection.disconnect(kickMessage);
        LOGGER.info("Disconnected player {}: {}", player.getScoreboardName(), message);
    }
}