package com.poso.qqbind.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.poso.qqbind.core.MainThread;
import com.poso.qqbind.core.PlayerActivity;
import com.poso.qqbind.core.PlayerActivityManager;
import com.poso.qqbind.core.PlayerStats;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全服统计批量读取，供 GET /api/stats/all 使用。
 * 离线遍历存档 stats 目录（按 mtime + 大小增量缓存），在线回主线程读实时计数器并覆盖磁盘值；
 * 名单来自活动记录（真实登录事件），usercache.json 只读兜底。
 */
public final class StatsBulkReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatsBulkReader.class);

    /** 离线索引的刷新间隔；在线数值每次请求都重新读取，不受这个缓存影响 */
    private static final long TTL_MS = 60_000L;
    private static final int MAX_PLAYERS = 10_000;
    private static final Object LOCK = new Object();

    private static volatile OfflineIndex index;

    private StatsBulkReader() {
    }

    private static final class FileEntry {
        final long mtime;
        final long size;
        final Map<String, Integer> custom;

        FileEntry(long mtime, long size, Map<String, Integer> custom) {
            this.mtime = mtime;
            this.size = size;
            this.custom = custom;
        }
    }

    private static final class OfflineIndex {
        final long builtAt;
        final long dirMtime;
        final Map<UUID, FileEntry> files;
        final Map<UUID, String> names;
        final Map<UUID, Long> lastSeen;
        final boolean truncated;

        OfflineIndex(long builtAt, long dirMtime, Map<UUID, FileEntry> files,
                     Map<UUID, String> names, Map<UUID, Long> lastSeen, boolean truncated) {
            this.builtAt = builtAt;
            this.dirMtime = dirMtime;
            this.files = files;
            this.names = names;
            this.lastSeen = lastSeen;
            this.truncated = truncated;
        }
    }

    private static final class OnlineRow {
        final UUID uuid;
        final String name;
        final Map<String, Integer> values;

        OnlineRow(UUID uuid, String name, Map<String, Integer> values) {
            this.uuid = uuid;
            this.name = name;
            this.values = values;
        }
    }

    /** 组装返回体；keys 必须已经过白名单过滤。 */
    public static JsonObject collect(MinecraftServer server, List<String> keys) {
        OfflineIndex idx = getIndex(server);
        long now = System.currentTimeMillis();

        Map<UUID, String> names = new HashMap<>(idx.names);
        Map<UUID, Long> lastSeen = new HashMap<>(idx.lastSeen);
        Map<UUID, Map<String, Integer>> rows = new LinkedHashMap<>();

        for (Map.Entry<UUID, FileEntry> entry : idx.files.entrySet()) {
            UUID uuid = entry.getKey();
            String name = names.get(uuid);
            if (name == null || name.isEmpty()) {
                // 没有可展示的名字：跳过（方案已定，不显示“未知玩家”）
                continue;
            }
            Map<String, Integer> values = new LinkedHashMap<>();
            for (String key : keys) {
                Object statKey = StatUtils.RANK_STAT_KEYS.get(key);
                values.put(key, statKey == null ? 0
                        : entry.getValue().custom.getOrDefault(String.valueOf(statKey), 0));
            }
            rows.put(uuid, values);
            lastSeen.put(uuid, Math.max(lastSeen.getOrDefault(uuid, 0L), entry.getValue().mtime));
        }

        try {
            List<OnlineRow> online = MainThread.call(3000, srv -> {
                List<OnlineRow> list = new ArrayList<>();
                for (ServerPlayer player : srv.getPlayerList().getPlayers()) {
                    Map<String, Integer> values = new LinkedHashMap<>();
                    for (String key : keys) {
                        Object statKey = StatUtils.RANK_STAT_KEYS.get(key);
                        values.put(key, statKey == null ? 0 : PlayerStats.get(player, statKey));
                    }
                    list.add(new OnlineRow(player.getUUID(), player.getName().getString(), values));
                }
                return list;
            });
            for (OnlineRow row : online) {
                names.put(row.uuid, row.name);
                lastSeen.put(row.uuid, now);
                rows.put(row.uuid, row.values);
            }
        } catch (Throwable t) {
            // 主线程卡住时退回磁盘值；刚上线且还没有 stats 文件的玩家会短暂缺失
            LOGGER.warn("[StatsAll] 在线统计读取失败，按磁盘值返回: {}", t.getMessage());
        }

        JsonObject data = new JsonObject();
        data.addProperty("updated_at", idx.builtAt);
        data.addProperty("truncated", idx.truncated);
        data.addProperty("total", rows.size());
        JsonArray players = new JsonArray();
        for (Map.Entry<UUID, Map<String, Integer>> entry : rows.entrySet()) {
            JsonObject player = new JsonObject();
            player.addProperty("uuid", entry.getKey().toString());
            player.addProperty("name", names.get(entry.getKey()));
            player.addProperty("last_seen", lastSeen.getOrDefault(entry.getKey(), 0L));
            JsonObject stats = new JsonObject();
            for (Map.Entry<String, Integer> value : entry.getValue().entrySet()) {
                stats.addProperty(value.getKey(), value.getValue());
            }
            player.add("stats", stats);
            players.add(player);
        }
        data.add("players", players);
        return data;
    }

    private static OfflineIndex getIndex(MinecraftServer server) {
        OfflineIndex current = index;
        long now = System.currentTimeMillis();
        long dirMtime = dirMtime(server);
        if (current != null && now - current.builtAt < TTL_MS && dirMtime == current.dirMtime) {
            return current;
        }
        synchronized (LOCK) {
            current = index;
            now = System.currentTimeMillis();
            dirMtime = dirMtime(server);
            if (current != null && now - current.builtAt < TTL_MS && dirMtime == current.dirMtime) {
                return current;
            }
            OfflineIndex fresh = scan(server, current, dirMtime);
            index = fresh;
            return fresh;
        }
    }

    private static OfflineIndex scan(MinecraftServer server, OfflineIndex prev, long dirMtime) {
        long builtAt = System.currentTimeMillis();
        Path dir;
        try {
            dir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        } catch (Throwable t) {
            LOGGER.warn("[StatsAll] 无法定位 stats 目录: {}", t.getMessage());
            return empty(builtAt);
        }
        if (dir == null || !Files.isDirectory(dir)) {
            return empty(builtAt);
        }

        Map<UUID, String> names = new HashMap<>();
        Map<UUID, Long> lastSeen = new HashMap<>();
        try {
            for (Map.Entry<UUID, PlayerActivity> entry : PlayerActivityManager.snapshotAll().entrySet()) {
                PlayerActivity activity = entry.getValue();
                if (activity.name != null && !activity.name.isEmpty()) {
                    names.put(entry.getKey(), activity.name);
                }
                long seen = activity.getLastActive();
                if (seen > 0) {
                    lastSeen.put(entry.getKey(), seen);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[StatsAll] 读取活动记录失败: {}", t.getMessage());
        }

        readUsercache(names);

        List<Path> paths = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path path : stream) {
                if (uuidOf(path) == null) {
                    continue;
                }
                if (paths.size() >= MAX_PLAYERS) {
                    truncated = true;
                    break;
                }
                paths.add(path);
            }
        } catch (Throwable t) {
            LOGGER.warn("[StatsAll] 枚举 stats 目录失败: {}", t.getMessage());
        }
        paths.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));

        Map<UUID, FileEntry> files = new HashMap<>();
        for (Path path : paths) {
            UUID uuid = uuidOf(path);
            if (uuid == null) {
                continue;
            }
            long mtime = lastModified(path);
            long size = sizeOf(path);
            FileEntry old = prev == null ? null : prev.files.get(uuid);
            FileEntry entry = (old != null && old.mtime == mtime && old.size == size)
                    ? old : new FileEntry(mtime, size, readCustom(path));
            files.put(uuid, entry);
        }
        return new OfflineIndex(builtAt, dirMtime, files, names, lastSeen, truncated);
    }

    private static OfflineIndex empty(long builtAt) {
        return new OfflineIndex(builtAt, -1L, new HashMap<>(), new HashMap<>(), new HashMap<>(), false);
    }

    private static UUID uuidOf(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(".json")) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - ".json".length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Integer> readCustom(Path path) {
        Map<String, Integer> custom = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonElement statsElement = root.get("stats");
            if (statsElement == null || !statsElement.isJsonObject()) {
                return custom;
            }
            JsonElement customElement = statsElement.getAsJsonObject().get("minecraft:custom");
            if (customElement == null || !customElement.isJsonObject()) {
                return custom;
            }
            for (Map.Entry<String, JsonElement> entry : customElement.getAsJsonObject().entrySet()) {
                try {
                    custom.put(entry.getKey(), entry.getValue().getAsInt());
                } catch (Exception ignored) {
                    // 跳过非整数字段
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[StatsAll] 读取统计文件失败 {}: {}", path.getFileName(), t.getMessage());
        }
        return custom;
    }

    private static void readUsercache(Map<UUID, String> names) {
        try {
            Path cache = Path.of("usercache.json");
            if (!Files.exists(cache)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(cache)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonArray()) {
                    return;
                }
                JsonArray array = root.getAsJsonArray();
                for (JsonElement element : array) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject obj = element.getAsJsonObject();
                    if (!obj.has("uuid") || !obj.has("name")) {
                        continue;
                    }
                    try {
                        UUID uuid = UUID.fromString(obj.get("uuid").getAsString());
                        String name = obj.get("name").getAsString();
                        if (!name.isEmpty()) {
                            names.putIfAbsent(uuid, name);
                        }
                    } catch (Exception ignored) {
                        // 跳过无效条目
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("[StatsAll] 读取 usercache.json 失败: {}", t.getMessage());
        }
    }

    private static long dirMtime(MinecraftServer server) {
        try {
            Path dir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
            return dir != null && Files.isDirectory(dir) ? Files.getLastModifiedTime(dir).toMillis() : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 启动后由后台线程调用，避免玩家第一次查询时吃全量扫描。 */
    public static void warmUp(MinecraftServer server) {
        if (server == null) {
            return;
        }
        try {
            long started = System.nanoTime();
            OfflineIndex idx = getIndex(server);
            LOGGER.info("[StatsAll] 缓存预热完成：{} 人，{} ms",
                    idx.files.size(), (System.nanoTime() - started) / 1_000_000);
        } catch (Throwable t) {
            LOGGER.warn("[StatsAll] 缓存预热失败: {}", t.getMessage());
        }
    }
}
