package com.poso.qqbind.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 玩家活动管理器（内存缓存 + 异步写盘）
 *
 * 职责：
 *  - 记录每个玩家的最后登录/退出时间戳
 *  - 维护全局"最后活动时间"和"最后活动玩家"（O(1) 查询，避免遍历）
 *  - 定期异步写盘，避免阻塞主线程
 *  - 服务器关闭时同步写盘
 *  - 启动时补全崩溃场景（lastQuit == -1 → 补为 lastLogin）
 *
 * 数据结构（last_activity.json）：
 * {
 *   "players": {
 *     "uuid-string": { "name": "POSOO", "lastLogin": 1736550000000, "lastQuit": 1736553000000 },
 *     ...
 *   }
 * }
 */
public class PlayerActivityManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerActivityManager.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path DATA_FILE = Paths.get("config", "qqbind", "last_activity.json");
    private static final long WRITE_INTERVAL_SECONDS = 30;

    // ---- 内存缓存 ----
    private static final Map<UUID, PlayerActivity> players = new ConcurrentHashMap<>();

    /** 全局最后活动时间戳（毫秒），0 表示无记录 */
    private static volatile long lastActivityTimestamp = 0;
    /** 全局最后活动玩家名 */
    private static volatile String lastActivityPlayer = null;

    /** 是否有未写入磁盘的变更 */
    private static final AtomicBoolean dirty = new AtomicBoolean(false);

    private static ScheduledExecutorService scheduler;

    /**
     * 初始化：加载磁盘文件、补全崩溃场景、启动异步写盘线程
     * 应在服务器启动阶段调用一次。
     */
    public static synchronized void init() {
        // 1. 加载磁盘文件
        loadFromDisk();

        // 2. 补全崩溃场景：lastQuit == -1 的玩家，视为"上次会话因崩溃而结束"
        long now = System.currentTimeMillis();
        for (PlayerActivity activity : players.values()) {
            if (activity.lastLogin > 0 && activity.lastQuit <= 0) {
                activity.lastQuit = activity.lastLogin;
            }
        }

        // 3. 重新计算全局最后活动时间
        recalcGlobalLast();

        // 4. 启动异步写盘调度器（每 30 秒检查一次，只在有变更时写）
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "QQBind-ActivityWriter");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(
                    PlayerActivityManager::flushIfDirty,
                    WRITE_INTERVAL_SECONDS, WRITE_INTERVAL_SECONDS, TimeUnit.SECONDS
            );
        }

        LOGGER.info("PlayerActivityManager initialized, {} player records loaded.", players.size());
    }

    /**
     * 关闭：最后一次同步写盘
     * 应在服务器停止阶段调用。
     */
    public static synchronized void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // 最后一次同步写盘（不管 dirty 状态）
        saveToDisk();
        LOGGER.info("PlayerActivityManager shutdown, data flushed.");
    }

    /**
     * 玩家登录时调用
     */
    public static void onPlayerLogin(UUID uuid, String name) {
        long now = System.currentTimeMillis();
        PlayerActivity activity = players.computeIfAbsent(uuid, k -> new PlayerActivity());
        activity.name = name;
        activity.lastLogin = now;
        activity.lastQuit = -1;  // 标记为"当前在线"

        // 更新全局最后活动
        if (now > lastActivityTimestamp) {
            lastActivityTimestamp = now;
            lastActivityPlayer = name;
        }

        dirty.set(true);
    }

    /**
     * 玩家退出时调用
     */
    public static void onPlayerLogout(UUID uuid, String name) {
        long now = System.currentTimeMillis();
        PlayerActivity activity = players.computeIfAbsent(uuid, k -> new PlayerActivity());
        activity.name = name;
        activity.lastQuit = now;

        if (now > lastActivityTimestamp) {
            lastActivityTimestamp = now;
            lastActivityPlayer = name;
        }

        dirty.set(true);
    }

    /**
     * 服务器正常关闭前，把所有在线玩家的 lastQuit 补为当前时间。
     * 由平台入口在 ServerStoppedEvent / SERVER_STOPPING 时调用。
     */
    public static void markAllOnlinePlayersAsQuit(java.util.Collection<UUID> onlineUuids) {
        long now = System.currentTimeMillis();
        for (UUID uuid : onlineUuids) {
            PlayerActivity activity = players.get(uuid);
            if (activity != null && activity.lastQuit <= 0) {
                activity.lastQuit = now;
            }
        }
        if (now > lastActivityTimestamp && !onlineUuids.isEmpty()) {
            lastActivityTimestamp = now;
        }
        dirty.set(true);
    }

    /**
     * 获取全局最后活动信息（供 /api/status 使用）
     * @return LastActivityInfo 或 null（无任何记录时）
     */
    public static LastActivityInfo getLastActivity() {
        if (lastActivityTimestamp <= 0) {
            return null;
        }
        return new LastActivityInfo(lastActivityTimestamp, lastActivityPlayer);
    }

    // ============ 内部实现 ============

    private static void recalcGlobalLast() {
        long maxTs = 0;
        String maxPlayer = null;
        for (PlayerActivity activity : players.values()) {
            long ts = activity.getLastActive();
            if (ts > maxTs) {
                maxTs = ts;
                maxPlayer = activity.name;
            }
        }
        lastActivityTimestamp = maxTs;
        lastActivityPlayer = maxPlayer;
    }

    private static void flushIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            try {
                saveToDiskInternal();
            } catch (Exception e) {
                LOGGER.error("Failed to flush player activity data", e);
                // 写失败时恢复 dirty 标记，下次继续尝试
                dirty.set(true);
            }
        }
    }

    private static synchronized void saveToDisk() {
        try {
            saveToDiskInternal();
            dirty.set(false);
        } catch (Exception e) {
            LOGGER.error("Failed to save player activity data", e);
        }
    }

    private static void saveToDiskInternal() throws Exception {
        // 确保目录存在
        Files.createDirectories(DATA_FILE.getParent());

        // 构建 JSON
        JsonObject root = new JsonObject();
        JsonObject playersObj = new JsonObject();
        for (Map.Entry<UUID, PlayerActivity> entry : players.entrySet()) {
            PlayerActivity a = entry.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("name", a.name == null ? "" : a.name);
            o.addProperty("lastLogin", a.lastLogin);
            o.addProperty("lastQuit", a.lastQuit);
            playersObj.add(entry.getKey().toString(), o);
        }
        root.add("players", playersObj);

        // 原子写入：先写临时文件，再重命名
        Path tmpFile = DATA_FILE.resolveSibling(DATA_FILE.getFileName() + ".tmp");
        Files.writeString(tmpFile, GSON.toJson(root), StandardCharsets.UTF_8);
        Files.move(tmpFile, DATA_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void loadFromDisk() {
        players.clear();
        if (!Files.exists(DATA_FILE)) {
            LOGGER.info("No existing player activity file, starting fresh.");
            return;
        }
        try (Reader reader = Files.newBufferedReader(DATA_FILE, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("players")) return;
            JsonObject playersObj = root.getAsJsonObject("players");
            for (String key : playersObj.keySet()) {
                try {
                    UUID uuid = UUID.fromString(key);
                    JsonObject o = playersObj.getAsJsonObject(key);
                    PlayerActivity a = new PlayerActivity();
                    a.name = o.has("name") ? o.get("name").getAsString() : "";
                    a.lastLogin = o.has("lastLogin") ? o.get("lastLogin").getAsLong() : -1;
                    a.lastQuit = o.has("lastQuit") ? o.get("lastQuit").getAsLong() : -1;
                    players.put(uuid, a);
                } catch (Exception ignored) {
                    // 跳过无效条目
                }
            }
            LOGGER.info("Loaded {} player activity records from disk.", players.size());
        } catch (Exception e) {
            LOGGER.error("Failed to load player activity data", e);
        }
    }

    /** 对外返回的结构 */
    public static class LastActivityInfo {
        public final long timestamp;
        public final String playerName;

        public LastActivityInfo(long timestamp, String playerName) {
            this.timestamp = timestamp;
            this.playerName = playerName;
        }
    }
}