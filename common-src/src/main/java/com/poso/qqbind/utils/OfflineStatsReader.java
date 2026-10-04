package com.poso.qqbind.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.poso.qqbind.core.PlayerActivityManager;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 离线玩家统计数据读取器
 * 从服务器的世界存档目录中读取 world/stats/<uuid>.json
 */
public class OfflineStatsReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(OfflineStatsReader.class);

    /**
     * 从磁盘读取离线玩家的统计数据
     * @return 若无法找到玩家或统计文件，返回 null
     */
    public static Map<String, Object> getPlayerStatsFromDisk(MinecraftServer server, String playerName) {
        try {
            // 1. 查找玩家 UUID
            UUID uuid = findPlayerUUID(server, playerName);
            if (uuid == null) {
                LOGGER.warn("无法找到玩家 {} 的 UUID", playerName);
                return null;
            }

            // 2. 定位 stats 文件路径
            Path statsDir = server.getWorldPath(LevelResource.ROOT).resolve("stats");
            Path statsFile = statsDir.resolve(uuid.toString() + ".json");
            if (!Files.exists(statsFile)) {
                LOGGER.warn("玩家 {} 的统计文件不存在: {}", playerName, statsFile);
                return null;
            }

            // 3. 解析 JSON，提取 minecraft:custom 部分的统计
            Map<String, Integer> customStats = new HashMap<>();
            try (Reader reader = Files.newBufferedReader(statsFile)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                if (root.has("stats")) {
                    JsonObject statsObj = root.getAsJsonObject("stats");
                    if (statsObj.has("minecraft:custom")) {
                        JsonObject customObj = statsObj.getAsJsonObject("minecraft:custom");
                        for (Map.Entry<String, com.google.gson.JsonElement> entry : customObj.entrySet()) {
                            try {
                                customStats.put(entry.getKey(), entry.getValue().getAsInt());
                            } catch (Exception ignored) {
                                // 跳过非整数字段
                            }
                        }
                    }
                }
            }

            // 4. 复用 StatUtils 生成完整统计数据
            return StatUtils.getPlayerStatsFromMap(playerName, uuid.toString(), customStats);

        } catch (Exception e) {
            LOGGER.error("读取玩家 {} 的离线统计数据失败", playerName, e);
            return null;
        }
    }

    /**
     * 查找玩家 UUID。
     * 注意：这里绝不能调用 server.getProfileCache().get(name)——原版实现在缓存未命中
     * 或条目过期时会向 Mojang 会话服务发起按名查询，并把查到的档案 add 进缓存、
     * 立即 save() 重写 usercache.json，导致从未在本服登录过的玩家出现在该文件中。
     * 因此只使用以下三种无副作用的本地来源，按优先级依次尝试：
     */
    private static UUID findPlayerUUID(MinecraftServer server, String playerName) {
        // 来源1：当前在线的玩家，直接从内存玩家列表取
        ServerPlayer online = server.getPlayerList().getPlayerByName(playerName);
        if (online != null) {
            return online.getUUID();
        }

        // 来源2：本地活动记录（来源为真实登录/退出事件，覆盖所有上过线的玩家）
        try {
            UUID uuid = PlayerActivityManager.findUUIDByName(playerName);
            if (uuid != null) {
                return uuid;
            }
        } catch (Exception e) {
            LOGGER.debug("从活动记录查找 {} 失败: {}", playerName, e.getMessage());
        }

        // 来源3：只读 usercache.json 文件作为兜底（只读文件，不触碰 profile cache）
        try {
            Path usercache = Path.of("usercache.json");
            if (Files.exists(usercache)) {
                try (Reader reader = Files.newBufferedReader(usercache)) {
                    JsonArray arr = JsonParser.parseReader(reader).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        if (obj.has("name") && playerName.equalsIgnoreCase(obj.get("name").getAsString())) {
                            return UUID.fromString(obj.get("uuid").getAsString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("从 usercache.json 查找 {} 失败: {}", playerName, e.getMessage());
        }

        return null;
    }
}