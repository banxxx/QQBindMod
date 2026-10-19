package com.poso.qqbind.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 控制台命令接口的频率限制：按来源 IP 统计调用次数（不是失败次数），
 * 超出窗口阈值后封禁该 IP。控制台命令每次调用都是特权操作，因此计数包含成功调用。
 * 仅由 HTTP 线程调用，内部使用并发安全结构。
 */
public final class CommandRateLimiter {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRateLimiter.class);

    /** 单个 IP 在一个窗口内允许的命令调用次数 */
    private static final int IP_MAX_CALLS = 20;
    /** 统计窗口（毫秒） */
    private static final long WINDOW_MS = 60_000;
    /** 触发限制后的封禁时长（毫秒） */
    private static final long BLOCK_MS = 60_000;
    /** 条目上限，防止伪造 IP 把表撑爆 */
    private static final int MAX_TRACKED_IPS = 10_000;

    private static class Window {
        final AtomicInteger count = new AtomicInteger();
        volatile long windowStart = System.currentTimeMillis();
        volatile long blockedUntil = 0;
    }

    private static final Map<String, Window> ipWindows = new ConcurrentHashMap<>();

    private CommandRateLimiter() {
    }

    public static boolean isBlocked(String ip) {
        Window w = ipWindows.get(ip);
        return w != null && w.blockedUntil > System.currentTimeMillis();
    }

    /**
     * 记录一次已放行的命令调用。
     */
    public static void recordCall(String ip) {
        if (ip == null || ip.isEmpty()) {
            return;
        }
        if (ipWindows.size() >= MAX_TRACKED_IPS && !ipWindows.containsKey(ip)) {
            return;
        }
        Window w = ipWindows.computeIfAbsent(ip, k -> new Window());
        long now = System.currentTimeMillis();
        synchronized (w) {
            if (now - w.windowStart >= WINDOW_MS) {
                w.windowStart = now;
                w.count.set(0);
            }
            int calls = w.count.incrementAndGet();
            if (calls > IP_MAX_CALLS && w.blockedUntil <= now) {
                w.blockedUntil = now + BLOCK_MS;
                LOGGER.warn("Console rate limit hit for {} ({} calls in window), blocking for {} seconds",
                        ip, calls, BLOCK_MS / 1000);
            }
        }
    }

    /**
     * 清理过期窗口条目（由定时任务调用）。
     */
    public static void cleanup() {
        long now = System.currentTimeMillis();
        ipWindows.entrySet().removeIf(e ->
                now - e.getValue().windowStart >= WINDOW_MS && e.getValue().blockedUntil < now);
    }
}
