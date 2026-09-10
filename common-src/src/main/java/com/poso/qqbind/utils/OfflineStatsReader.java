package com.poso.qqbind.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * 从服务器的 profile cache 或 usercache.json 中查找玩家 UUID
     */
    private static UUID findPlayerUUID(MinecraftServer server, String playerName) {
        // 方式1：从服务器的 GameProfileCache 查找（原版 API，三平台一致）
        try {
            if (server.getProfileCache() != null) {
                Optional<GameProfile> profile = server.getProfileCache().get(playerName);
                if (profile.isPresent()) {
                    return profile.get().getId();
                }
            }
        } catch (Exception e) {
            LOGGER.debug("从 profile cache 查找失败: {}", e.getMessage());
        }

        // 方式2：从 usercache.json 中查找（使用相对路径，跨平台通用）
        try {
            Path usercache = Path.of("usercache.json");
            if (Files.exists(usercache)) {
                try (Reader reader = Files.newBufferedReader(usercache)) {
                    JsonArray arr = JsonParser.parseReader(reader).getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject obj = arr.get(i).getAsJsonObject();
                        if (obj.has("name") && playerName.equals(obj.get("name").getAsString())) {
                            return UUID.fromString(obj.get("uuid").getAsString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("从 usercache.json 查找失败: {}", e.getMessage());
        }

        return null;
    }
}