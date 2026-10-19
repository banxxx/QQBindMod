package com.poso.qqbind.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.poso.qqbind.server.ServerProviderHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;
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
        if (!isValidGameId(gameId)) {
            return;
        }
        executeCommand("whitelist add " + gameId);
    }

    public static void removeWhitelist(String gameId) {
        if (!isValidGameId(gameId)) {
            return;
        }
        executeCommand("whitelist remove " + gameId);
    }

    /**
     * 命令执行前的最后一道防线：gameId 会被拼接进控制台命令，
     * 只放行合法玩家名，拒绝选择器、引号、空白等一切特殊输入。
     */
    private static boolean isValidGameId(String gameId) {
        if (!com.poso.qqbind.utils.GameIdValidator.isValid(gameId)) {
            LOGGER.warn("拒绝执行命令：gameId 非法（不匹配 [A-Za-z0-9_]{{1,16}}）：{}", gameId);
            return false;
        }
        return true;
    }

    private static void executeCommand(String command) {
        // 命令必须排在服务端主线程执行（HTTP/健康检查线程直接跑 dispatcher 会崩溃或死锁）
        MainThread.post(() -> {
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
        });
    }

    /**
     * 以控制台身份执行命令并回收其输出（供 /api/console 使用）。
     *
     * 复用原版 RCON 的命令源：它把命令输出写进自己的缓冲区，因此不需要开 RCON 端口，
     * 命令权限也与原版控制台一致。原版实现是逐条 append 文本、不加换行，
     * 这里补一个换行以保证多条消息能分行回显。
     *
     * @throws IllegalStateException 服务器不可用或主线程等待超时（超时不代表命令没执行）
     */
    public static CommandOutput executeWithOutput(String command, long timeoutMs) {
        return MainThread.call(timeoutMs, server -> {
            RconConsoleSource sink = new RconConsoleSource(server) {
                @Override
                public void sendSystemMessage(Component message) {
                    super.sendSystemMessage(Component.literal(message.getString() + "\n"));
                }
            };
            sink.prepareForCommand();
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            CommandSourceStack source = sink.createCommandSourceStack();
            int result = 0;
            try {
                result = dispatcher.execute(dispatcher.parse(command, source));
            } catch (CommandSyntaxException e) {
                sink.sendSystemMessage(Component.literal("命令解析失败: " + e.getMessage()));
            }
            LOGGER.info("Console command executed: {}", command);
            return new CommandOutput(sink.getCommandResponse(), result);
        });
    }

    /**
     * 控制台命令的执行结果：原版缓冲区的原始输出 + Brigadier 的影响数。
     */
    public record CommandOutput(String response, int result) {
    }

    /**
     * 断开玩家连接并显示提示（使用 Component）
     */
    public static void disconnectPlayer(ServerPlayer player, String message) {
        MainThread.post(() -> {
            // 使用 Component.literal() 创建文本，并支持颜色代码
            Component kickMessage = Component.literal(message);
            player.connection.disconnect(kickMessage);
            LOGGER.info("Disconnected player {}: {}", player.getScoreboardName(), message);
        });
    }
}