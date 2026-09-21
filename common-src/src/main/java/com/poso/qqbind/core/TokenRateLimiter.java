package com.poso.qqbind.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 令牌验证防爆破限流器：
 * - 按来源 IP 统计验证失败次数，超过阈值后拒绝其令牌类请求；
 * - 统计全局失败频率，抵御分布式猜测（6 位数字令牌空间有限）；
 * - 按令牌字符串统计失败次数，同一错误令牌被反复提交时直接作废该猜测。
 * 仅由 HTTP 线程调用，内部使用并发安全结构。
 */
public final class TokenRateLimiter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TokenRateLimiter.class);

    /**
     * 单个 IP 在一个窗口内允许的失败次数。
     * 注意：机器人插件会代替 QQ 用户把 /绑定 猜测转发到各服 validate_token，
     * 未命中服务器的失败都计在插件出口 IP 上，因此阈值需为正常转发留出余量，
     * 真正的每用户频率限制应在插件端做。此处拦截的是直连洪水与高强度爆破。
     */
    private static final int IP_MAX_FAILURES = 120;
    /** 全局在一个窗口内允许的失败总数（超出即临时关闭令牌模式） */
    private static final int GLOBAL_MAX_FAILURES = 200;
    /** 同一个错误令牌字符串允许的失败次数 */
    private static final int TOKEN_MAX_FAILURES = 20;
    /** 统计窗口（毫秒） */
    private static final long WINDOW_MS = 60_000;
    /** 触发限制后的封禁时长（毫秒） */
    private static final long BLOCK_MS = 5 * 60_000;

    private static class Window {
        final AtomicInteger count = new AtomicInteger();
        volatile long windowStart = System.currentTimeMillis();
        volatile long blockedUntil = 0;
    }

    private static final Map<String, Window> ipWindows = new ConcurrentHashMap<>();
    private static final Map<String, Window> tokenWindows = new ConcurrentHashMap<>();
    private static final Window globalWindow = new Window();

    private TokenRateLimiter() {
    }

    /**
     * 该 IP 当前是否被封禁（含全局封禁）。
     */
    public static boolean isBlocked(String ip) {
        long now = System.currentTimeMillis();
        if (globalWindow.blockedUntil > now) {
            return true;
        }
        Window w = ipWindows.get(ip);
        return w != null && w.blockedUntil > now;
    }

    /**
     * 该令牌字符串是否已被判定为暴力猜测（直接拒绝，不再查表）。
     */
    public static boolean isTokenBlocked(String token) {
        Window w = tokenWindows.get(token);
        return w != null && w.blockedUntil > System.currentTimeMillis();
    }

    /**
     * 记录一次验证失败（IP + 全局 + 令牌三个维度）。
     */
    public static void recordFailure(String ip, String token) {
        bump(ipWindows.computeIfAbsent(ip, k -> new Window()), ip, "IP");
        // 防止攻击流量把 per-token 表撑爆，超限时只依赖 IP/全局维度限流
        if (tokenWindows.size() < 50_000) {
            bump(tokenWindows.computeIfAbsent(token, k -> new Window()), token, "token");
        }
        bump(globalWindow, "global", "global");
    }

    /**
     * 验证成功后清除该 IP 的失败计数。
     */
    public static void recordSuccess(String ip) {
        ipWindows.remove(ip);
    }

    private static void bump(Window w, String key, String kind) {
        long now = System.currentTimeMillis();
        synchronized (w) {
            if (now - w.windowStart >= WINDOW_MS) {
                w.windowStart = now;
                w.count.set(0);
            }
            int failures = w.count.incrementAndGet();
            int max = w == globalWindow ? GLOBAL_MAX_FAILURES : (kind.equals("token") ? TOKEN_MAX_FAILURES : IP_MAX_FAILURES);
            if (failures >= max && w.blockedUntil <= now) {
                w.blockedUntil = now + BLOCK_MS;
                LOGGER.warn("Token-brute-force limit hit for {} {} ({} failures in window), blocking for {} minutes",
                        kind, key, failures, BLOCK_MS / 60_000);
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
        tokenWindows.entrySet().removeIf(e ->
                now - e.getValue().windowStart >= WINDOW_MS && e.getValue().blockedUntil < now);
        if (now - globalWindow.windowStart >= WINDOW_MS) {
            synchronized (globalWindow) {
                globalWindow.windowStart = now;
                globalWindow.count.set(0);
            }
        }
    }
}
