package com.poso.qqbind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 模组配置管理类，负责加载和保存 `qqbind-config.json` 配置文件.
 * 所有配置项以静态字段形式暴露，支持在运行时通过 {@link #reload()} 方法重新加载.
 * 首次启动时若配置文件不存在，会自动生成默认配置.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class QQBindConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(QQBindConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "config/qqbind/qqbind-config.json";

    // 配置字段（静态，全局可访问）
    public static int HTTP_PORT = 25566;
    public static String API_TOKEN = "change-me-to-a-random-token";
    public static boolean ENABLE_WHITELIST_CHECK = true;
    public static String DATA_FILE_PATH = "qqbind/bindings.json";
    public static String KICK_MESSAGE = "§c您尚未绑定游戏ID！\n§e请加入QQ群发送 /绑定 指令完成绑定。";

    /**
     * 加载或创建配置文件
     */
    public static void load() {
        Path configPath = Paths.get(CONFIG_FILE);
        if (!Files.exists(configPath)) {
            createDefaultConfig(configPath);
            return;
        }

        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            applyConfig(json);
            LOGGER.info("Loaded configuration from {}", CONFIG_FILE);
        } catch (Exception e) {
            LOGGER.error("Failed to load config, using defaults", e);
        }
    }

    /**
     * 重载配置（用于 /qqbind reload 命令）
     */
    public static void reload() {
        load();
        LOGGER.info("Configuration reloaded");
    }

    /**
     * 将 JSON 配置应用到静态字段
     */
    private static void applyConfig(JsonObject json) {
        if (json.has("httpPort")) HTTP_PORT = json.get("httpPort").getAsInt();
        if (json.has("apiToken")) API_TOKEN = json.get("apiToken").getAsString();
        if (json.has("enableWhitelistCheck")) ENABLE_WHITELIST_CHECK = json.get("enableWhitelistCheck").getAsBoolean();
        if (json.has("dataFilePath")) DATA_FILE_PATH = json.get("dataFilePath").getAsString();
        if (json.has("kickMessage")) KICK_MESSAGE = json.get("kickMessage").getAsString();
    }

    /**
     * 创建默认配置文件
     */
    private static void createDefaultConfig(Path configPath) {
        try {
            // 确保目录存在
            Files.createDirectories(configPath.getParent());

            JsonObject defaultJson = new JsonObject();
            defaultJson.addProperty("httpPort", HTTP_PORT);
            defaultJson.addProperty("apiToken", API_TOKEN);
            defaultJson.addProperty("enableWhitelistCheck", ENABLE_WHITELIST_CHECK);
            defaultJson.addProperty("dataFilePath", DATA_FILE_PATH);
            defaultJson.addProperty("kickMessage", KICK_MESSAGE);

            String jsonStr = GSON.toJson(defaultJson);
            Files.writeString(configPath, jsonStr);
            LOGGER.info("Created default config file at {}", CONFIG_FILE);
        } catch (Exception e) {
            LOGGER.error("Failed to create default config", e);
        }
    }
}