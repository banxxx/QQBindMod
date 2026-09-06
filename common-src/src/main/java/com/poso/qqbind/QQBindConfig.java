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
import java.util.UUID;

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
    public static String SERVER_ID = "default";          // 新增：服务器唯一标识

    // 消息模板配置
    public static String QQ_GROUP = "123456789";
    public static String TITLE_TEMPLATE = "§c您尚未绑定游戏ID！";
    public static String SUBTITLE_TEMPLATE = "§e请加入QQ群 {qqGroup} 发送 §b/绑定 {token} §e完成绑定。";
    public static String ACTION_BAR_TEMPLATE = "§c您尚未绑定游戏ID！请加入QQ群 {qqGroup} 发送 §b/绑定 {token}";
    public static String BIND_SUCCESS_TITLE = "§a绑定成功！";
    public static String BIND_SUCCESS_SUBTITLE = "§e祝您游戏愉快";
    public static String BIND_SUCCESS_ACTION_BAR = "§a已解除限制，您可以正常游戏了";

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
        if (json.has("serverId")) SERVER_ID = json.get("serverId").getAsString();

        if (json.has("qqGroup")) QQ_GROUP = json.get("qqGroup").getAsString();
        if (json.has("titleTemplate")) TITLE_TEMPLATE = json.get("titleTemplate").getAsString();
        if (json.has("subtitleTemplate")) SUBTITLE_TEMPLATE = json.get("subtitleTemplate").getAsString();
        if (json.has("actionBarTemplate")) ACTION_BAR_TEMPLATE = json.get("actionBarTemplate").getAsString();
        if (json.has("bindSuccessTitle")) BIND_SUCCESS_TITLE = json.get("bindSuccessTitle").getAsString();
        if (json.has("bindSuccessSubtitle")) BIND_SUCCESS_SUBTITLE = json.get("bindSuccessSubtitle").getAsString();
        if (json.has("bindSuccessActionBar")) BIND_SUCCESS_ACTION_BAR = json.get("bindSuccessActionBar").getAsString();
    }

    /**
     * 创建默认配置文件
     */
    private static void createDefaultConfig(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());

            // 如果 serverId 未设置，生成一个随机 UUID（仅用于首次生成）
            if (SERVER_ID.equals("default")) {
                SERVER_ID = UUID.randomUUID().toString().substring(0, 8);
            }

            JsonObject defaultJson = new JsonObject();
            defaultJson.addProperty("httpPort", HTTP_PORT);
            defaultJson.addProperty("apiToken", API_TOKEN);
            defaultJson.addProperty("enableWhitelistCheck", ENABLE_WHITELIST_CHECK);
            defaultJson.addProperty("dataFilePath", DATA_FILE_PATH);
            defaultJson.addProperty("serverId", SERVER_ID);
            defaultJson.addProperty("qqGroup", QQ_GROUP);
            defaultJson.addProperty("titleTemplate", TITLE_TEMPLATE);
            defaultJson.addProperty("subtitleTemplate", SUBTITLE_TEMPLATE);
            defaultJson.addProperty("actionBarTemplate", ACTION_BAR_TEMPLATE);
            defaultJson.addProperty("bindSuccessTitle", BIND_SUCCESS_TITLE);
            defaultJson.addProperty("bindSuccessSubtitle", BIND_SUCCESS_SUBTITLE);
            defaultJson.addProperty("bindSuccessActionBar", BIND_SUCCESS_ACTION_BAR);

            String jsonStr = GSON.toJson(defaultJson);
            Files.writeString(configPath, jsonStr);
            LOGGER.info("Created default config file at {}", CONFIG_FILE);
        } catch (Exception e) {
            LOGGER.error("Failed to create default config", e);
        }
    }

    /**
     * 工具方法：将消息模板中的 {qqGroup} 占位符替换为实际群号
     * @param template 模板字符串
     * @return 替换后的字符串
     */
    public static String formatMessage(String template) {
        if (template == null) return "";
        return template.replace("{qqGroup}", QQ_GROUP);
    }

    /**
     * 工具方法：将消息模板中的 {qqGroup} 和 {token} 占位符替换为实际值
     * @param template 模板字符串
     * @param token 令牌（可为 null）
     * @return 替换后的字符串
     */
    public static String formatMessage(String template, String token) {
        if (template == null) return "";
        String result = template.replace("{qqGroup}", QQ_GROUP);
        if (token != null) {
            result = result.replace("{token}", token);
        }
        return result;
    }
}