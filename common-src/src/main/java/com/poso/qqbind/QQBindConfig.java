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

    // ===== 新增：消息模板配置 =====
    public static String QQ_GROUP = "123456789";                           // 默认 QQ 群号，用户可自定义
    public static String TITLE_TEMPLATE = "§c您尚未绑定游戏ID！";          // 大标题模板
    public static String SUBTITLE_TEMPLATE = "§e请加入QQ群 {qqGroup} 发送 /绑定 指令完成绑定。"; // 副标题模板
    public static String ACTION_BAR_TEMPLATE = "§c您尚未绑定游戏ID！请加入QQ群 {qqGroup} 完成绑定。"; // 操作栏模板
    public static String CHAT_TEMPLATE = "§c您尚未绑定游戏ID！\n§e请加入QQ群 {qqGroup} 发送 /绑定 指令完成绑定。"; // 聊天栏模板
    public static String BIND_SUCCESS_TITLE = "§a绑定成功！";
    public static String BIND_SUCCESS_SUBTITLE = "§e祝您游戏愉快";
    public static String BIND_SUCCESS_ACTION_BAR = "§a已解除限制，您可以正常游戏了";
    public static String BIND_SUCCESS_CHAT = "§a绑定成功！您现在可以正常游戏了。";

    // 保留旧的 kickMessage 作为向后兼容，但优先使用模板
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

        // 新模板字段（若缺失则使用默认值）
        if (json.has("qqGroup")) QQ_GROUP = json.get("qqGroup").getAsString();
        if (json.has("titleTemplate")) TITLE_TEMPLATE = json.get("titleTemplate").getAsString();
        if (json.has("subtitleTemplate")) SUBTITLE_TEMPLATE = json.get("subtitleTemplate").getAsString();
        if (json.has("actionBarTemplate")) ACTION_BAR_TEMPLATE = json.get("actionBarTemplate").getAsString();
        if (json.has("chatTemplate")) CHAT_TEMPLATE = json.get("chatTemplate").getAsString();
        if (json.has("bindSuccessTitle")) BIND_SUCCESS_TITLE = json.get("bindSuccessTitle").getAsString();
        if (json.has("bindSuccessSubtitle")) BIND_SUCCESS_SUBTITLE = json.get("bindSuccessSubtitle").getAsString();
        if (json.has("bindSuccessActionBar")) BIND_SUCCESS_ACTION_BAR = json.get("bindSuccessActionBar").getAsString();
        if (json.has("bindSuccessChat")) BIND_SUCCESS_CHAT = json.get("bindSuccessChat").getAsString();

        // 若存在旧的 kickMessage 且没有 chatTemplate，则将其作为聊天模板的备选
        if (json.has("kickMessage") && !json.has("chatTemplate")) {
            CHAT_TEMPLATE = json.get("kickMessage").getAsString();
        }
        // 同时更新 KICK_MESSAGE 字段以保持兼容（某些旧代码可能仍引用）
        if (json.has("kickMessage")) {
            KICK_MESSAGE = json.get("kickMessage").getAsString();
        }
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
            defaultJson.addProperty("qqGroup", QQ_GROUP);
            defaultJson.addProperty("titleTemplate", TITLE_TEMPLATE);
            defaultJson.addProperty("subtitleTemplate", SUBTITLE_TEMPLATE);
            defaultJson.addProperty("actionBarTemplate", ACTION_BAR_TEMPLATE);
            defaultJson.addProperty("chatTemplate", CHAT_TEMPLATE);
            defaultJson.addProperty("bindSuccessTitle", BIND_SUCCESS_TITLE);
            defaultJson.addProperty("bindSuccessSubtitle", BIND_SUCCESS_SUBTITLE);
            defaultJson.addProperty("bindSuccessActionBar", BIND_SUCCESS_ACTION_BAR);
            defaultJson.addProperty("bindSuccessChat", BIND_SUCCESS_CHAT);
            // 保留旧的 kickMessage 以向后兼容
            defaultJson.addProperty("kickMessage", KICK_MESSAGE);

            String jsonStr = GSON.toJson(defaultJson);
            Files.writeString(configPath, jsonStr);
            LOGGER.info("Created default config file at {}", CONFIG_FILE);
        } catch (Exception e) {
            LOGGER.error("Failed to create default config", e);
        }
    }

    /**
     * 工具方法：将消息模板中的占位符 {qqGroup} 替换为实际群号
     * @param template 模板字符串
     * @return 替换后的字符串
     */
    public static String formatMessage(String template) {
        if (template == null) return "";
        return template.replace("{qqGroup}", QQ_GROUP);
    }
}