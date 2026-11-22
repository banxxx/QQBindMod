package com.poso.qqbind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.poso.qqbind.official.OfficialException;
import com.poso.qqbind.official.OfficialRuntimeRegistry;
import com.poso.qqbind.official.OfficialRuntimeSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String CONFIG_FILE = "config/qqbind/qqbind-config.json";
    private static final OfficialRuntimeRegistry BINDING_RUNTIME = new OfficialRuntimeRegistry();

    public static OfficialRuntimeRegistry bindingRuntime() { return BINDING_RUNTIME; }
    public static boolean isLegacyBindingEnabled() { return BINDING_RUNTIME.legacyAllowed(); }
    public static boolean isOfficialBindingSelected() { return BINDING_RUNTIME.officialSelected(); }

    // 配置字段（静态，全局可访问）
    public static int HTTP_PORT = 25566;
    /** HTTP API 监听地址：默认 0.0.0.0 保持旧行为，生产环境建议 127.0.0.1 或内网网卡地址 */
    public static String HTTP_BIND_HOST = "0.0.0.0";
    /** 默认占位 token——保持该值时拒绝启动 HTTP API */
    public static final String DEFAULT_API_TOKEN = "change-me-to-a-random-token";
    public static String API_TOKEN = DEFAULT_API_TOKEN;
    public static boolean ENABLE_WHITELIST_CHECK = true;
    public static String DATA_FILE_PATH = "qqbind/bindings.json";
    public static String SERVER_ID = "default";          // 服务器唯一标识
    /** 控制台命令接口令牌：留空即不注册 /api/console */
    public static String CONSOLE_TOKEN = "";
    /** 额外禁用的命令（逗号分隔），与代码内的永久硬禁清单叠加 */
    public static String CONSOLE_BANNED_COMMANDS = "";

    // 消息模板配置
    public static String QQ_GROUP = "123456789";
    public static String TITLE_TEMPLATE = "§c您尚未绑定游戏ID！";
    public static String SUBTITLE_TEMPLATE = "§e请加入QQ群 {qqGroup} 发送 §b/绑定 {token} §e完成绑定。";
    public static String ACTION_BAR_TEMPLATE = "§c您尚未绑定游戏ID！请加入QQ群 {qqGroup} 发送 §b/绑定 {token}";
    public static String BIND_SUCCESS_TITLE = "§a绑定成功！";
    public static String BIND_SUCCESS_SUBTITLE = "§e祝您游戏愉快";
    public static String BIND_SUCCESS_ACTION_BAR = "§a已解除限制，您可以正常游戏了";

    // 存储相关配置
    public static String STORAGE_MODE = "hybrid";   // "local" | "remote" | "hybrid"
    public static String JDBC_URL = "jdbc:mysql://<host>:4000/qqbind_db?useSSL=true&serverTimezone=UTC";
    public static String DB_USER = "";
    public static String DB_PASSWORD = "";
    public static int CACHE_TTL_SECONDS = 60;
    /** 数据库不可用时的健康检查/重试间隔（秒） */
    public static int DB_RETRY_INTERVAL_SECONDS = 30;
    /** 数据库最大重试次数，0 表示不限次数（到达上限后退避为低频探测） */
    public static int DB_MAX_RETRIES = 0;

    /** 上一次 load() 的失败诊断文本；null 表示配置文件解析成功 */
    private static String loadError = null;

    /**
     * 加载或创建配置文件
     */
    public static void load() {
        Path configPath = Paths.get(CONFIG_FILE);
        loadError = null;
        if (!Files.exists(configPath)) {
            if (!BINDING_RUNTIME.canCreateDefault()
                    || Files.exists(configPath.resolveSibling("official-selection.marker"),LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(Paths.get("qqbind","official"),LinkOption.NOFOLLOW_LINKS)) {
                loadError="配置文件缺失，当前目标未改变；不自动重建配置，请在本机恢复后完整停启。";
                LOGGER.error(loadError);
                return;
            }
            createDefaultConfig(configPath);
            return;
        }

        try {
            if (Files.size(configPath) > 1024 * 1024) throw new OfficialException("INVALID_INPUT");
            String raw = Files.readString(configPath, StandardCharsets.UTF_8);
            JsonObject json = OfficialRuntimeSettings.document(raw);
            // Remember an explicit official selection even when another field
            // is invalid; deleting that config must not enable old identities.
            if (json.has("bindingPlatform") && json.get("bindingPlatform").isJsonPrimitive()
                    && json.get("bindingPlatform").getAsJsonPrimitive().isString()
                    && json.get("bindingPlatform").getAsString().equals("qq_official")) persistOfficialSelectionMarker();
            applyConfig(json);
            LOGGER.info("Loaded configuration from {}", CONFIG_FILE);
        } catch (Exception e) {
            loadError = e instanceof OfficialException official && official.code().equals("RESTART_REQUIRED")
                    ? "绑定平台或官方来源变更需要维护窗口完整停启，当前运行目标未改变。"
                    : "配置未应用；请在本机检查配置后重试或完整停启，已验证的运行目标保持不变。";
            LOGGER.error(loadError); // No parser excerpt, secret value, path or cause.
        }
    }

    /**
     * 解析失败诊断文本（含文件路径、异常、可疑不可见字符的位置）；null = 上次解析成功
     */
    public static String getLoadError() {
        return loadError;
    }

    /**
     * 解析失败时把「看不见」的字符位置一起写出来：面板网页编辑器或聊天粘贴常把
     * U+00A0/U+200B/U+FEFF/U+3000 混进缩进，光看 Gson 的行号指不到它，代价却是整份配置退回默认值。
     */
    private static String describeFailure(Path configPath, Exception e) {
        StringBuilder msg = new StringBuilder();
        msg.append("配置文件解析失败，本次启动所有配置项都退回模组内置默认值：")
                .append("\n  文件：").append(configPath.toAbsolutePath())
                .append("\n  异常：").append(e);
        String suspects = locateInvisibleOutsideStrings(configPath);
        if (suspects != null) {
            msg.append("\n  可疑字符：").append(suspects)
                    .append(" —— 这些不可见空白出现在 JSON 字符串之外，会被 JSON 解析拒绝");
        }
        msg.append("\n  连带后果：apiToken 退回占位值 → HTTP API 拒绝启动（绑定/在线/TPS 等接口全不可用）；")
                .append("jdbcUrl 退回 <host> 占位 → 远程数据库连不上；enableWhitelistCheck 退回 true → 未绑定玩家会被拦在门外。")
                .append("\n  处理：按上面的行/列删掉那行的行首空白后用键盘重打两个空格（别整段复制粘贴），")
                .append("或删掉该文件让模组重新生成一份再逐项填回。");
        return msg.toString();
    }

    /**
     * 只扫 JSON 字符串字面量之外的位置，找不可见空白；字符串内的中文模板属正常内容，不上报。
     */
    private static String locateInvisibleOutsideStrings(Path configPath) {
        String raw;
        try {
            raw = Files.readString(configPath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
        StringBuilder hits = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        int line = 1;
        int column = 0;
        int count = 0;
        for (int i = 0; i < raw.length() && count < 5; i++) {
            char c = raw.charAt(i);
            column++;
            if (c == '\n') {
                line++;
                column = 0;
            }
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (isInvisibleSpace(c)) {
                if (count > 0) {
                    hits.append("、");
                }
                hits.append(String.format("U+%04X（行 %d 列 %d）", (int) c, line, column));
                count++;
            }
        }
        return count == 0 ? null : hits.toString();
    }

    /** Java 的 isWhitespace 不含 U+00A0/U+200B/U+FEFF，逐个点名后再兜住其余 Unicode 空白 */
    private static boolean isInvisibleSpace(char c) {
        return c == '\u00a0' || c == '\u200b' || c == '\ufeff' || c == '\u3000' || c == '\u2007' || c == '\u202f'
                || (c > 0x7e && Character.isWhitespace(c));
    }

    /**
     * 重载配置（用于 /qqbind reload 命令）
     */
    public static void reload() {
        load();
        if (loadError == null) LOGGER.info("Configuration reloaded");
    }

    /**
     * 运行时切换「白名单校验」：内存立即生效，同时写回配置文件，
     * 否则服务器下次重启会回到旧值，开关就成了只有本次有效的临时按钮。
     *
     * @return 配置文件是否写成功（内存值无论如何已经生效）
     */
    public static synchronized boolean setWhitelistCheck(boolean enabled) {
        if (!isLegacyBindingEnabled()) return false;
        ENABLE_WHITELIST_CHECK = enabled;
        return persistOne("enableWhitelistCheck", enabled);
    }

    /**
     * QQ 官方 V2 的在线登录取值开关。调用方必须已经校验官方 Token 与作用域；
     * 与旧 setWhitelistCheck 分开，避免官方运行期误走 OneBot 写入语义。
     * 运行期快照由 OfficialRuntimeSession 同步更新。
     *
     * @return 配置文件是否写成功（内存值无论如何已经生效）
     */
    public static synchronized boolean applyOfficialWhitelistCheck(boolean enabled) {
        ENABLE_WHITELIST_CHECK = enabled;
        return persistOne("enableWhitelistCheck", enabled);
    }

    /**
     * 只改一个键的读改写：其它键原样保留，先写临时文件再原子替换，避免留下半截配置。
     */
    private static boolean persistOne(String key, boolean value) {
        Path configPath = Paths.get(CONFIG_FILE);
        try {
            JsonObject json;
            if (Files.exists(configPath)) {
                try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                    json = JsonParser.parseReader(reader).getAsJsonObject();
                }
            } else {
                json = new JsonObject();
            }
            json.addProperty(key, value);

            Files.createDirectories(configPath.getParent());
            Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(json), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to persist config key {}", key, e);
            return false;
        }
    }

    /**
     * 将 JSON 配置应用到静态字段
     */
    private static void applyConfig(JsonObject json) {
        // Validate all old scalar conversions before publishing any static value.
        for (String name : new String[]{"httpBindHost","apiToken","dataFilePath","serverId","consoleToken","consoleBannedCommands",
                "qqGroup","titleTemplate","subtitleTemplate","actionBarTemplate","bindSuccessTitle","bindSuccessSubtitle",
                "bindSuccessActionBar","storageMode","jdbcUrl","dbUser","dbPassword"}) {
            if (json.has(name)) json.get(name).getAsString();
        }
        for (String name : new String[]{"httpPort","cacheTtlSeconds","dbRetryIntervalSeconds","dbMaxRetries"}) {
            if (json.has(name)) json.get(name).getAsInt();
        }
        if (json.has("enableWhitelistCheck")) json.get("enableWhitelistCheck").getAsBoolean();
        OfficialRuntimeSettings candidate=OfficialRuntimeSettings.parse(json);
        BINDING_RUNTIME.apply(candidate,() -> {
            if (candidate.official()) persistOfficialSelectionMarker();
            applyLegacyFields(json);
        });
    }

    /** Sticky refusal marker: missing config must not cold-boot back to OneBot.
     * It contains no identity/credential and is never used as auth or read back.
     */
    private static void persistOfficialSelectionMarker() {
        Path marker=Paths.get(CONFIG_FILE).resolveSibling("official-selection.marker");
        if (Files.exists(marker,LinkOption.NOFOLLOW_LINKS)) return;
        try {
            Files.createDirectories(marker.getParent());
            try (FileChannel channel=FileChannel.open(marker,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap("qq_official\n".getBytes(StandardCharsets.US_ASCII));
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
        } catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
    }

    private static void applyLegacyFields(JsonObject json) {
        if (json.has("httpPort")) HTTP_PORT = json.get("httpPort").getAsInt();
        if (json.has("httpBindHost")) HTTP_BIND_HOST = json.get("httpBindHost").getAsString();
        if (json.has("apiToken")) API_TOKEN = json.get("apiToken").getAsString();
        if (json.has("enableWhitelistCheck")) ENABLE_WHITELIST_CHECK = json.get("enableWhitelistCheck").getAsBoolean();
        if (json.has("dataFilePath")) DATA_FILE_PATH = json.get("dataFilePath").getAsString();
        if (json.has("serverId")) SERVER_ID = json.get("serverId").getAsString();
        if (json.has("consoleToken")) CONSOLE_TOKEN = json.get("consoleToken").getAsString();
        if (json.has("consoleBannedCommands")) CONSOLE_BANNED_COMMANDS = json.get("consoleBannedCommands").getAsString();

        if (json.has("qqGroup")) QQ_GROUP = json.get("qqGroup").getAsString();
        if (json.has("titleTemplate")) TITLE_TEMPLATE = json.get("titleTemplate").getAsString();
        if (json.has("subtitleTemplate")) SUBTITLE_TEMPLATE = json.get("subtitleTemplate").getAsString();
        if (json.has("actionBarTemplate")) ACTION_BAR_TEMPLATE = json.get("actionBarTemplate").getAsString();
        if (json.has("bindSuccessTitle")) BIND_SUCCESS_TITLE = json.get("bindSuccessTitle").getAsString();
        if (json.has("bindSuccessSubtitle")) BIND_SUCCESS_SUBTITLE = json.get("bindSuccessSubtitle").getAsString();
        if (json.has("bindSuccessActionBar")) BIND_SUCCESS_ACTION_BAR = json.get("bindSuccessActionBar").getAsString();

        if (json.has("storageMode")) STORAGE_MODE = json.get("storageMode").getAsString();
        if (json.has("jdbcUrl")) JDBC_URL = json.get("jdbcUrl").getAsString();
        if (json.has("dbUser")) DB_USER = json.get("dbUser").getAsString();
        if (json.has("dbPassword")) DB_PASSWORD = json.get("dbPassword").getAsString();
        if (json.has("cacheTtlSeconds")) CACHE_TTL_SECONDS = json.get("cacheTtlSeconds").getAsInt();
        if (json.has("dbRetryIntervalSeconds")) DB_RETRY_INTERVAL_SECONDS = json.get("dbRetryIntervalSeconds").getAsInt();
        if (json.has("dbMaxRetries")) DB_MAX_RETRIES = json.get("dbMaxRetries").getAsInt();
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
            defaultJson.addProperty("bindingPlatform", "onebot");
            defaultJson.addProperty("httpPort", HTTP_PORT);
            defaultJson.addProperty("httpBindHost", HTTP_BIND_HOST);
            defaultJson.addProperty("apiToken", API_TOKEN);
            defaultJson.addProperty("enableWhitelistCheck", ENABLE_WHITELIST_CHECK);
            defaultJson.addProperty("dataFilePath", DATA_FILE_PATH);
            defaultJson.addProperty("serverId", SERVER_ID);
            defaultJson.addProperty("consoleToken", CONSOLE_TOKEN);
            defaultJson.addProperty("consoleBannedCommands", CONSOLE_BANNED_COMMANDS);
            defaultJson.addProperty("qqGroup", QQ_GROUP);
            defaultJson.addProperty("titleTemplate", TITLE_TEMPLATE);
            defaultJson.addProperty("subtitleTemplate", SUBTITLE_TEMPLATE);
            defaultJson.addProperty("actionBarTemplate", ACTION_BAR_TEMPLATE);
            defaultJson.addProperty("bindSuccessTitle", BIND_SUCCESS_TITLE);
            defaultJson.addProperty("bindSuccessSubtitle", BIND_SUCCESS_SUBTITLE);
            defaultJson.addProperty("bindSuccessActionBar", BIND_SUCCESS_ACTION_BAR);
            defaultJson.addProperty("storageMode", STORAGE_MODE);
            defaultJson.addProperty("jdbcUrl", JDBC_URL);
            defaultJson.addProperty("dbUser", DB_USER);
            defaultJson.addProperty("dbPassword", DB_PASSWORD);
            defaultJson.addProperty("cacheTtlSeconds", CACHE_TTL_SECONDS);
            defaultJson.addProperty("dbRetryIntervalSeconds", DB_RETRY_INTERVAL_SECONDS);
            defaultJson.addProperty("dbMaxRetries", DB_MAX_RETRIES);

            String jsonStr = GSON.toJson(defaultJson);
            Files.writeString(configPath, jsonStr);
            applyConfig(defaultJson);
            LOGGER.info("Created default config file at {}", CONFIG_FILE);
        } catch (Exception e) {
            loadError="默认配置创建失败，绑定入口未初始化。";
            LOGGER.error(loadError);
        }
    }

    /**
     * token 仍为默认占位值或为空时返回 true（此状态下禁止启动 HTTP API）
     */
    public static boolean isApiTokenUnsafe() {
        return API_TOKEN == null || API_TOKEN.isEmpty() || DEFAULT_API_TOKEN.equals(API_TOKEN);
    }

    /**
     * 配置了 consoleToken 即启用 /api/console；留空表示不注册该接口
     */
    public static boolean isConsoleEnabled() {
        return CONSOLE_TOKEN != null && !CONSOLE_TOKEN.isEmpty();
    }

    /**
     * 已启用控制台接口但令牌强度不足（短于 16 位或仍是默认占位值）。
     * 该接口等同服务器控制台，弱令牌一旦暴露后果远大于绑定接口。
     */
    public static boolean isConsoleTokenWeak() {
        return isConsoleEnabled()
                && (CONSOLE_TOKEN.length() < 16 || DEFAULT_API_TOKEN.equals(CONSOLE_TOKEN));
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
