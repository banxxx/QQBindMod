package com.poso.qqbind.core;

import com.poso.qqbind.server.ServerProviderHolder;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 主线程调度工具：将游戏状态变更（命令执行、游戏模式切换、踢出等）切回服务端主线程执行。
 * HTTP 处理器线程与数据库健康检查线程直接操作游戏对象会导致并发崩溃或死锁，
 * 所有此类操作必须通过 {@link #post(Runnable)} 排队到 MinecraftServer 主线程。
 */
public final class MainThread {
    private static final Logger LOGGER = LoggerFactory.getLogger(MainThread.class);

    private MainThread() {
    }

    /**
     * 在服务端主线程执行任务。已在主线程时立即同步执行，否则排队到下一 tick。
     * 服务器不可用时丢弃任务并记录警告。
     */
    public static void post(Runnable task) {
        MinecraftServer server;
        try {
            server = ServerProviderHolder.get().getCurrentServer();
        } catch (Throwable t) {
            LOGGER.warn("Server provider unavailable, dropping main-thread task: {}", t.getMessage());
            return;
        }
        if (server == null) {
            LOGGER.warn("Server not started yet, dropping main-thread task");
            return;
        }
        if (server.isSameThread()) {
            runSafely(task);
        } else {
            server.execute(() -> runSafely(task));
        }
    }

    private static void runSafely(Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            LOGGER.error("Task failed on main thread", e);
        }
    }
}
