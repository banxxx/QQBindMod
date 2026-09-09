package com.poso.qqbind.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 本地内存缓存管理器，用于存储绑定状态（gameId -> qq），减少数据库查询。
 * 支持TTL过期和后台清理。
 */
public class CacheManager {
    private static final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();

    static {
        // 每30秒清理一次过期条目
        cleaner.scheduleAtFixedRate(CacheManager::cleanExpired, 30, 30, TimeUnit.SECONDS);
    }

    public static void put(String gameId, String qq, int ttlSeconds) {
        long expireAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        cache.put(gameId, new CacheEntry(qq, expireAt));
    }

    public static String get(String gameId) {
        CacheEntry entry = cache.get(gameId);
        if (entry == null) return null;
        if (System.currentTimeMillis() > entry.expireAt) {
            cache.remove(gameId);
            return null;
        }
        return entry.qq; // 返回绑定的QQ，若为null则表示未绑定
    }

    public static void invalidate(String gameId) {
        cache.remove(gameId);
    }

    public static void clear() {
        cache.clear();
    }

    private static void cleanExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> now > entry.getValue().expireAt);
    }

    private static class CacheEntry {
        final String qq;        // null 表示未绑定
        final long expireAt;

        CacheEntry(String qq, long expireAt) {
            this.qq = qq;
            this.expireAt = expireAt;
        }
    }
}