package com.poso.qqbind.core;

import com.poso.qqbind.server.ServerProviderHolder;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 主线程调度工具：将游戏状态变更（命令执行、游戏模式切换、踢出等）切回服务端主线程执行。
 * HTTP 处理器线程与数据库健康检查线程直接操作游戏对象会导致并发崩溃或死锁，
 * 所有此类操作必须通过 {@link #post(Runnable)} 排队到 MinecraftServer 主线程。
 * 需要返回值的读取（玩家列表快照、统计读取等）使用 {@link #call(long, Function)}。
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

    /**
     * 在主线程执行带返回值的任务并阻塞等待结果（用于 HTTP 线程做游戏状态快照）。
     * 已在主线程时直接执行；否则排队并限时等待。
     *
     * @throws IllegalStateException 服务器不可用、等待超时或任务本身抛出异常
     */
    public static <T> T call(long timeoutMs, Function<MinecraftServer, T> task) {
        MinecraftServer server;
        try {
            server = ServerProviderHolder.get().getCurrentServer();
        } catch (Throwable t) {
            throw new IllegalStateException("Server provider unavailable: " + t.getMessage());
        }
        if (server == null) {
            throw new IllegalStateException("Server not available");
        }
        if (server.isSameThread()) {
            return task.apply(server);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.apply(server));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Main-thread call timed out after " + timeoutMs + "ms");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtimeEx) {
                throw runtimeEx;
            }
            throw new IllegalStateException(cause);
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
