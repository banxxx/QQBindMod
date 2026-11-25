package com.poso.qqbind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.poso.qqbind.official.OfficialException;
import com.poso.qqbind.official.OfficialRuntimeRegistry;
import com.poso.qqbind.official.OfficialRuntimeSettings;
import com.poso.qqbind.utils.Jsonc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.util.Set;
import java.util.UUID;

/**
 * 模组配置管理类，负责加载和保存 `qqbind-config.jsonc` 配置文件.
 * 文件使用 JSONC：支持 //、/* *&#47; 注释和尾逗号；旧的 `qqbind-config.json` 仍可读取.
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
    /** 新格式；JSONC 允许用户写注释，解析前会剥离注释和尾逗号。 */
    private static final String CONFIG_FILE_JSONC = "config/qqbind/qqbind-config.jsonc";
    /** 旧版路径：仅在 .jsonc 不存在时读取，不自动迁移/覆盖。 */
    private static final String CONFIG_FILE_JSON = "config/qqbind/qqbind-config.json";
    private static final OfficialRuntimeRegistry BINDING_RUNTIME = new OfficialRuntimeRegistry();

    public static OfficialRuntimeRegistry bindingRuntime() { return BINDING_RUNTIME; }
    public static boolean isLegacyBindingEnabled() { return BINDING_RUNTIME.legacyAllowed(); }
    public static boolean isOfficialBindingSelected() { return BINDING_RUNTIME.officialSelected(); }

    /**
     * 当前生效的配置路径：优先 .jsonc；两者都没有时返回 .jsonc（新建文件用）。
     * 两个文件同时存在时以 .jsonc 为准，并在日志里提示，避免用户改了 .json 却不生效。
     */
    private static Path activeConfigPath() {
        Path jsonc = Paths.get(CONFIG_FILE_JSONC);
        if (Files.exists(jsonc)) return jsonc;
        Path json = Paths.get(CONFIG_FILE_JSON);
        return Files.exists(json) ? json : jsonc;
    }

    /** 给面板/日志展示的配置路径，不含任何配置值。 */
    public static String getConfigFilePath() {
        return activeConfigPath().toString();
    }

    // 配置字段（静态，全局可访问）
    public static int HTTP_PORT = 25566;
    // 默认绑定平台
    public static String BINDING_PLATFORM = "onebot";
    /** HTTP API 监听地址：默认 0.0.0.0 保持旧行为，生产环境建议 127.0.0.1 或内网网卡地址 */
    public static String HTTP_BIND_HOST = "0.0.0.0";
    /** 默认占位 token——保持该值时拒绝启动 HTTP API */
    public static final String DEFAULT_API_TOKEN = "change-me-to-a-random-token";

    /** 当前配置文件格式版本；缺失表示旧版兼容格式。 */
    public static final int CONFIG_VERSION = 2;

    /** 两个平台共用的顶层字段；平台配置段里再写一遍会被拒绝，避免同一个令牌/端口/文案出现两份值。 */
    private static final Set<String> COMMON_CONFIG_KEYS = Set.of(
            "configVersion",
            "httpPort", "httpBindHost", "apiToken", "consoleToken", "consoleBannedCommands",
            "enableWhitelistCheck",
            "titleTemplate", "subtitleTemplate", "actionBarTemplate",
            "bindSuccessTitle", "bindSuccessSubtitle", "bindSuccessActionBar");
    /** OneBot 段只放平台/存储字段；serverId 可省略，由模组在进程内生成。 */
    private static final Set<String> ONEBOT_CONFIG_KEYS = Set.of(
            "serverId", "dataFilePath", "qqGroup", "groupHint",
            "storageMode", "jdbcUrl", "dbUser", "dbPassword", "cacheTtlSeconds",
            "dbRetryIntervalSeconds", "dbMaxRetries");
    /**
     * 官方段：作用域、群展示名、运行时开关和中心库都直接放在这一段；
     * official 仅用于兼容旧版嵌套写法。environmentId/serverId 必须是规范 UUID。
     */
    private static final Set<String> OFFICIAL_CONFIG_KEYS = Set.of(
            "environmentId", "bindingGroupId", "groupHint", "serverId", "storageMode",
            "dataDirectory", "cacheTtlSeconds", "dbRetryIntervalSeconds", "dbMaxRetries",
            "configurationRevision", "createFreshState", "allowCenterSync",
            "jdbcUrl", "dbUser", "dbPassword", "official");
    /** 允许出现在文件顶层的全部键；未知键一律拒绝，避免拼写错误被静默忽略。 */
    private static final Set<String> TOP_LEVEL_CONFIG_KEYS = Set.of(
            "configVersion", "bindingPlatform", "onebot", "qq_official",
            "httpPort", "httpBindHost", "apiToken", "consoleToken", "consoleBannedCommands",
            "enableWhitelistCheck",
            "titleTemplate", "subtitleTemplate", "actionBarTemplate",
            "bindSuccessTitle", "bindSuccessSubtitle", "bindSuccessActionBar",
            "dataFilePath", "serverId", "qqGroup", "storageMode", "jdbcUrl", "dbUser", "dbPassword",
            "dataDirectory", "cacheTtlSeconds", "dbRetryIntervalSeconds", "dbMaxRetries",
            "environmentId", "bindingGroupId", "groupHint",
            "configurationRevision", "createFreshState", "allowCenterSync", "official");
    /** 只属于 OneBot、出现在官方模式下应拒绝的顶层字段。 */
    private static final Set<String> ONEBOT_ONLY_TOP_LEVEL = Set.of("dataFilePath", "qqGroup");
    /** 只属于官方、出现在 OneBot 模式下应拒绝的顶层字段。 */
    private static final Set<String> OFFICIAL_ONLY_TOP_LEVEL = Set.of(
            "environmentId", "bindingGroupId", "dataDirectory",
            "configurationRevision", "createFreshState", "allowCenterSync", "official");
    public static String API_TOKEN = DEFAULT_API_TOKEN;
    public static boolean ENABLE_WHITELIST_CHECK = true;
    public static String DATA_FILE_PATH = "qqbind/bindings.json";
    public static String SERVER_ID = "default";          // 服务器唯一标识
    /** 控制台命令接口令牌：留空即不注册 /api/console */
    public static String CONSOLE_TOKEN = "";
    /** 额外禁用的命令（逗号分隔），与代码内的永久硬禁清单叠加 */
    public static String CONSOLE_BANNED_COMMANDS = "";

    // 消息模板配置。{groupHint} / {bindCommand} / {token} 由当前平台填写：
    // OneBot 取 onebot.groupHint（留空自动用 qqGroup），官方取 qq_official.groupHint。
    // {qqGroup} 仅保留给旧模板兼容；新配置建议用前三个占位符。
    public static String QQ_GROUP = "123456789";
    /**
     * 官方模式下 {groupHint} 的展示值（只填群号数字即可）。
     * bindingGroupId 是 group_openid，对玩家不可读，不能直接当群提示；
     * 该字段只用于展示，不参与鉴权或数据隔离。
     */
    public static String OFFICIAL_GROUP_HINT = "当前官方群";
    /** OneBot 的 {groupHint}；留空时自动使用 QQ_GROUP，不必和 qqGroup 重复填写。 */
    public static String ONEBOT_GROUP_HINT = "";
    public static String TITLE_TEMPLATE = "§c您尚未绑定游戏ID！";
    public static String SUBTITLE_TEMPLATE = "§e请在{groupHint}发送 §b{bindCommand} §e完成绑定。";
    public static String ACTION_BAR_TEMPLATE = "§c您尚未绑定游戏ID！请在{groupHint}发送 §b{bindCommand}";
    public static String BIND_SUCCESS_TITLE = "§a绑定成功！";
    public static String BIND_SUCCESS_SUBTITLE = "§e祝您游戏愉快";
    public static String BIND_SUCCESS_ACTION_BAR = "§a已解除限制，您可以正常游戏了";
    /** 旧配置没有 {bindCommand} 时，官方模式改用这几条内置文案，避免把 /绑定 指令发给官方玩家。 */
    public static final String OFFICIAL_DEFAULT_TITLE_TEMPLATE = "§c您尚未绑定游戏ID！";
    public static final String OFFICIAL_DEFAULT_SUBTITLE_TEMPLATE = "§e请在当前官方群发送 §b{bindCommand} §e完成绑定。";
    public static final String OFFICIAL_DEFAULT_ACTION_BAR_TEMPLATE = "§c您尚未绑定游戏ID！请在当前官方群发送 §b{bindCommand}";

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
        Path configPath = activeConfigPath();
        if (Files.exists(Paths.get(CONFIG_FILE_JSONC)) && Files.exists(Paths.get(CONFIG_FILE_JSON))) {
            LOGGER.warn("同时存在 {} 和 {}，当前使用 .jsonc；请删除不再使用的那一份。", CONFIG_FILE_JSONC, CONFIG_FILE_JSON);
        }
        loadError = null;
        if (!Files.exists(configPath)) {
            boolean officialTrace = isOfficialBindingSelected()
                    || Files.exists(configPath.resolveSibling("official-selection.marker"),LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(Paths.get("qqbind","official"),LinkOption.NOFOLLOW_LINKS);
            if (officialTrace) {
                // 官方痕迹存在时只能生成官方占位模板：绝不能生成可运行的 OneBot
                // 默认配置，否则会在官方配置丢失后静默切回旧身份。
                createOfficialRecoveryConfig(configPath);
                return;
            }
            if (!BINDING_RUNTIME.canCreateDefault()) {
                loadError="配置文件缺失；当前进程已有运行配置，为避免在线令牌变化不自动重建。"
                        + "无官方痕迹时完整停启会自动生成默认配置；否则请恢复配置后完整停启。";
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
            LOGGER.info("Loaded configuration from {}", configPath);
        } catch (Exception e) {
            loadError = e instanceof OfficialException official && official.code().equals("RESTART_REQUIRED")
                    ? "绑定平台或官方来源变更需要维护窗口完整停启，当前运行目标未改变。"
                    : "配置未应用；请检查 configVersion=2、bindingPlatform、当前平台段，以及字段名、类型和取值范围后重试或完整停启。新版配置的未知顶层字段和跨平台字段也会被拒绝，已验证的运行目标保持不变。";
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
        Path configPath = activeConfigPath();
        try {
            String raw = Files.exists(configPath) ? Files.readString(configPath, StandardCharsets.UTF_8) : "{}";
            String updated = null;
            if (configPath.getFileName().toString().endsWith(".jsonc")) {
                // 单键原位替换，保留用户注释和排版；找不到可替换的布尔值时再退回重写。
                updated = Jsonc.updateTopLevelBoolean(raw, key, value);
            }
            if (updated == null) {
                JsonObject json = OfficialRuntimeSettings.document(raw);
                json.addProperty(key, value);
                updated = GSON.toJson(json);
            }

            Files.createDirectories(configPath.getParent());
            Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
            Files.writeString(tmp, updated, StandardCharsets.UTF_8);
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
     * 把「公共顶层字段 + onebot/qq_official 平台段」整理成旧的单层配置供现有校验器使用。
     *
     * <p>兼容规则：
     * <ul>
     *   <li>没有平台段时原样返回，旧版扁平 OneBot/官方配置继续工作；</li>
     *   <li>只要出现平台段，就必须显式写 bindingPlatform，避免两个段并存时猜错目标；</li>
     *   <li>只读取当前平台段，另一段即使有占位值或损坏内容也不参与校验；</li>
     *   <li>平台段中的同名字段覆盖顶层旧字段，便于先加段、再逐步迁移；</li>
     *   <li>公共字段只能放顶层，平台段里出现会被拒绝，避免令牌/端口出现两份来源。</li>
     * </ul>
     * 当前平台段缺失时仍回退到顶层扁平字段，这是旧配置的兼容路径；不会回退到另一个平台。
     */
    private static JsonObject normalizeConfig(JsonObject raw) {
        boolean hasOnebot = raw.has("onebot");
        boolean hasOfficial = raw.has("qq_official");
        if (!hasOnebot && !hasOfficial) return raw.deepCopy();

        JsonElement selector = raw.get("bindingPlatform");
        if (selector == null || !selector.isJsonPrimitive() || !selector.getAsJsonPrimitive().isString()) {
            throw new OfficialException("INVALID_INPUT");
        }
        String platform = selector.getAsString();
        if (!platform.equals("onebot") && !platform.equals("qq_official")) {
            throw new OfficialException("INVALID_INPUT");
        }

        JsonObject normalized = raw.deepCopy();
        normalized.remove("onebot");
        normalized.remove("qq_official");
        String sectionName = platform.equals("onebot") ? "onebot" : "qq_official";
        if (!raw.has(sectionName)) return normalized;

        JsonElement sectionElement = raw.get(sectionName);
        if (!sectionElement.isJsonObject()) throw new OfficialException("INVALID_INPUT");
        JsonObject section = sectionElement.getAsJsonObject();
        Set<String> allowed = platform.equals("onebot") ? ONEBOT_CONFIG_KEYS : OFFICIAL_CONFIG_KEYS;
        for (String key : section.keySet()) {
            // 公共字段留在顶层；未知字段也拒绝，避免拼写错误被静默忽略。
            if (COMMON_CONFIG_KEYS.contains(key) || !allowed.contains(key)) {
                throw new OfficialException("INVALID_INPUT");
            }
        }
        for (var entry : section.entrySet()) normalized.add(entry.getKey(), entry.getValue());
        return normalized;
    }

    /**
     * 将 JSON 配置应用到静态字段
     */
    private static void applyConfig(JsonObject raw) {
        validateTopLevelKeys(raw);
        // 平台段是新格式；必须显式声明版本，避免半升级配置被当成旧格式猜测。
        if ((raw.has("onebot") || raw.has("qq_official")) && !raw.has("configVersion")) {
            throw new OfficialException("INVALID_INPUT");
        }
        JsonObject json = normalizeConfig(raw);
        validateAndNormalizeValues(json);
        OfficialRuntimeSettings candidate=OfficialRuntimeSettings.parse(json);
        // 平台专属字段出现在另一平台的扁平配置里通常意味着平台切换后没清理旧字段，
        // 静默忽略会让人误以为已经生效，因此这里直接拒绝。
        shiftCheck(json, candidate.official() ? ONEBOT_ONLY_TOP_LEVEL : OFFICIAL_ONLY_TOP_LEVEL);
        BINDING_RUNTIME.apply(candidate,() -> {
            if (candidate.official()) persistOfficialSelectionMarker();
            applyLegacyFields(json, candidate.official());
        });
    }

    private static void validateTopLevelKeys(JsonObject raw) {
        // 旧版扁平配置没有 configVersion/平台段，历史上允许存在未识别键（例如手工备注或旧版本字段）；
        // 为保持可直接升级，这里只对 v2/分段配置严格拒绝未知键，已知字段仍照常校验。
        if (!raw.has("configVersion") && !raw.has("onebot") && !raw.has("qq_official")) return;
        for (String key : raw.keySet()) {
            if (!TOP_LEVEL_CONFIG_KEYS.contains(key)) throw new OfficialException("INVALID_INPUT");
        }
    }

    private static void shiftCheck(JsonObject json, Set<String> forbidden) {
        for (String key : forbidden) {
            if (json.has(key)) throw new OfficialException("INVALID_INPUT");
        }
    }

    /**
     * 所有类型、取值范围和命令列表都在发布任何静态值之前校验并规范化；
     * 失败时整份配置不应用，避免出现半套新值、半套旧值。
     */
    private static void validateAndNormalizeValues(JsonObject json) {
        if (json.has("configVersion")) requireConfigVersion(json.get("configVersion"));
        for (String name : new String[]{"httpBindHost","apiToken","dataFilePath","serverId","consoleToken",
                "qqGroup","groupHint","titleTemplate","subtitleTemplate","actionBarTemplate",
                "bindSuccessTitle","bindSuccessSubtitle","bindSuccessActionBar","storageMode",
                "dataDirectory","jdbcUrl","dbUser","dbPassword"}) {
            if (json.has(name)) requireString(json.get(name));
        }
        requireNonEmpty(json, "httpBindHost");
        requireNonEmpty(json, "dataFilePath");
        requireNonEmpty(json, "dataDirectory");
        requireNonEmpty(json, "qqGroup");
        requireInteger(json, "httpPort", 1, 65535);
        requireInteger(json, "cacheTtlSeconds", 0, Integer.MAX_VALUE);
        requireInteger(json, "dbRetryIntervalSeconds", 1, Integer.MAX_VALUE);
        requireInteger(json, "dbMaxRetries", 0, Integer.MAX_VALUE);
        if (json.has("enableWhitelistCheck")) requireBoolean(json.get("enableWhitelistCheck"));
        if (json.has("storageMode")) {
            String mode=json.get("storageMode").getAsString();
            if (!Set.of("local","remote","hybrid").contains(mode)) throw new OfficialException("INVALID_INPUT");
        }
        if (json.has("consoleBannedCommands")) {
            json.addProperty("consoleBannedCommands", normalizeCommandList(json.get("consoleBannedCommands")));
        }
    }

    private static void requireConfigVersion(JsonElement element) {
        if (!isNumber(element)) throw new OfficialException("INVALID_INPUT");
        String value=element.getAsString();
        if (!value.matches("[0-9]{1,9}") || Integer.parseInt(value) != CONFIG_VERSION) {
            throw new OfficialException("INVALID_INPUT");
        }
    }

    private static void requireString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new OfficialException("INVALID_INPUT");
        }
    }

    private static boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    private static boolean isBoolean(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean();
    }

    private static void requireBoolean(JsonElement element) {
        if (!isBoolean(element)) throw new OfficialException("INVALID_INPUT");
    }

    private static void requireNonEmpty(JsonObject json, String name) {
        if (json.has(name) && json.get(name).getAsString().trim().isEmpty()) {
            throw new OfficialException("INVALID_INPUT");
        }
    }

    private static void requireInteger(JsonObject json, String name, int min, int max) {
        if (!json.has(name)) return;
        JsonElement element=json.get(name);
        if (!isNumber(element) || !element.getAsString().matches("[0-9]{1,10}")) {
            throw new OfficialException("INVALID_INPUT");
        }
        long value=Long.parseLong(element.getAsString());
        if (value < min || value > max) throw new OfficialException("INVALID_INPUT");
    }

    /**
     * 新格式用字符串数组；旧格式的逗号分隔字符串继续兼容。
     * 内部仍保存为逗号分隔字符串，命令执行器不需要改。
     */
    private static String normalizeCommandList(JsonElement element) {
        StringBuilder result=new StringBuilder();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (!isString(item)) throw new OfficialException("INVALID_INPUT");
                appendCommand(result, item.getAsString(), true);
            }
            return result.toString();
        }
        if (isString(element)) {
            for (String part : element.getAsString().split(",", -1)) appendCommand(result, part, false);
            return result.toString();
        }
        throw new OfficialException("INVALID_INPUT");
    }

    private static void appendCommand(StringBuilder result, String raw, boolean strict) {
        String command=raw == null ? "" : raw.trim();
        if (command.isEmpty()) {
            if (strict) throw new OfficialException("INVALID_INPUT");
            return;
        }
        if (command.indexOf(',') >= 0 || command.indexOf('\0') >= 0 || command.length() > 128) {
            throw new OfficialException("INVALID_INPUT");
        }
        if (result.length() > 0) result.append(',');
        result.append(command);
        if (result.length() > 2048) throw new OfficialException("INVALID_INPUT");
    }

    /** Sticky refusal marker: missing config must not cold-boot back to OneBot.
     * It contains no identity/credential and is never used as auth or read back.
     */
    private static void persistOfficialSelectionMarker() {
        Path marker=activeConfigPath().resolveSibling("official-selection.marker");
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

    private static void applyLegacyFields(JsonObject json, boolean official) {
        if (json.has("httpPort")) HTTP_PORT = json.get("httpPort").getAsInt();
        if (json.has("httpBindHost")) HTTP_BIND_HOST = json.get("httpBindHost").getAsString();
        if (json.has("apiToken")) API_TOKEN = json.get("apiToken").getAsString();
        if (json.has("enableWhitelistCheck")) ENABLE_WHITELIST_CHECK = json.get("enableWhitelistCheck").getAsBoolean();
        if (json.has("dataFilePath")) DATA_FILE_PATH = json.get("dataFilePath").getAsString();
        if (json.has("serverId")) {
            String configured = json.get("serverId").getAsString().trim();
            // 空值/模板占位值不覆盖进程内已生成的短标识，否则每次 reload 都会换一个命名空间。
            if (!configured.isEmpty() && !configured.equals("default") && !configured.equals("<SERVER_UUID>")) {
                SERVER_ID = configured;
            }
        }
        if (SERVER_ID == null || SERVER_ID.isEmpty() || SERVER_ID.equals("default")) {
            // OneBot 的 serverId 只是本进程 TokenInfo 的命名空间，不参与跨服权威；
            // 新格式允许省略、留空或保留 <SERVER_UUID> 占位，进程内生成一次即可。
            // 官方 serverId 是作用域的一部分，OfficialRuntimeSettings 已强制要求规范 UUID。
            SERVER_ID = UUID.randomUUID().toString().substring(0, 8);
        }
        if (json.has("consoleToken")) CONSOLE_TOKEN = json.get("consoleToken").getAsString();
        if (json.has("consoleBannedCommands")) CONSOLE_BANNED_COMMANDS = json.get("consoleBannedCommands").getAsString();

        if (json.has("qqGroup")) QQ_GROUP = json.get("qqGroup").getAsString();
        // 每次加载先回到默认值：用户删掉 groupHint 或在平台段间切换后，不应继续沿用上一次的展示值。
        if (official) {
            OFFICIAL_GROUP_HINT = "当前官方群";
            if (json.has("groupHint")) {
                String hint=json.get("groupHint").getAsString().trim();
                if (!hint.isEmpty()) OFFICIAL_GROUP_HINT = hint;
            }
        } else {
            ONEBOT_GROUP_HINT = "";
            if (json.has("groupHint")) ONEBOT_GROUP_HINT = json.get("groupHint").getAsString().trim();
        }
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
     * 官方痕迹存在但主配置丢失时，写一份不可直接运行的官方占位配置。
     *
     * <p>不能改成生成可运行的 OneBot 默认值：官方状态可能仍含绑定/操作记录，
     * 静默切回 OneBot 会让插件与本服身份错位。占位文件只用于让用户有地方填写，
     * 本次和后续启动在占位值被替换前都会继续拒绝 HTTP API。
     */
    private static void createOfficialRecoveryConfig(Path configPath) {
        try {
            try {
                persistOfficialSelectionMarker();
            } catch (Exception e) {
                // 已有官方状态目录时即使标记写入失败也继续生成模板；日志不带路径或秘密。
                LOGGER.error("官方恢复模板准备时无法写入选择标记，继续保留 fail-closed。");
            }

            Files.createDirectories(configPath.getParent());
            String nl = System.lineSeparator();
            StringBuilder text = new StringBuilder(4096);
            text.append("/*").append(nl);
            text.append("QQBind 官方配置恢复模板（JSONC）").append(nl);
            text.append("填完真实值后完整停启").append(nl);
            text.append("配置丢失但官方状态仍在时，HTTP API 保持关闭").append(nl);
            text.append("*/").append(nl);
            text.append("{").append(nl);
            text.append("  /* 格式版本，固定为 2 */").append(nl);
            text.append("  \"configVersion\": ").append(CONFIG_VERSION).append(",").append(nl);
            text.append("  /* 当前绑定平台 */").append(nl);
            text.append("  \"bindingPlatform\": \"qq_official\",").append(nl);
            text.append("  /* HTTP API 端口，修改后需重启 */").append(nl);
            text.append("  \"httpPort\": ").append(HTTP_PORT).append(",").append(nl);
            text.append("  /* 监听地址，0.0.0.0 表示允许远程访问 */").append(nl);
            text.append("  \"httpBindHost\": ").append(quote(HTTP_BIND_HOST)).append(",").append(nl);
            text.append("  /* API 令牌，必须与插件配置保持一致 */").append(nl);
            text.append("  \"apiToken\": ").append(quote(DEFAULT_API_TOKEN)).append(",").append(nl);
            text.append("  /* 是否拦截未绑定玩家 */").append(nl);
            text.append("  \"enableWhitelistCheck\": ").append(ENABLE_WHITELIST_CHECK).append(",").append(nl);
            text.append("  /* 面板控制台令牌；留空不启用 /api/console，启用时至少 16 位 */").append(nl);
            text.append("  \"consoleToken\": \"\",").append(nl);
            text.append("  /* 控制台额外禁用的命令名，[] 表示不额外禁用 */").append(nl);
            text.append("  \"consoleBannedCommands\": [],").append(nl);
            text.append("  /* 未绑定提示标题 */").append(nl);
            text.append("  \"titleTemplate\": ").append(quote(TITLE_TEMPLATE)).append(",").append(nl);
            text.append("  /* 未绑定提示副标题；{groupHint}=各平台段 groupHint 的值，{bindCommand}=绑定指令 */").append(nl);
            text.append("  \"subtitleTemplate\": ").append(quote(SUBTITLE_TEMPLATE)).append(",").append(nl);
            text.append("  /* 未绑定提示动作栏 */").append(nl);
            text.append("  \"actionBarTemplate\": ").append(quote(ACTION_BAR_TEMPLATE)).append(",").append(nl);
            text.append("  /* 绑定成功标题 */").append(nl);
            text.append("  \"bindSuccessTitle\": ").append(quote(BIND_SUCCESS_TITLE)).append(",").append(nl);
            text.append("  /* 绑定成功副标题 */").append(nl);
            text.append("  \"bindSuccessSubtitle\": ").append(quote(BIND_SUCCESS_SUBTITLE)).append(",").append(nl);
            text.append("  /* 绑定成功动作栏 */").append(nl);
            text.append("  \"bindSuccessActionBar\": ").append(quote(BIND_SUCCESS_ACTION_BAR)).append(",").append(nl);
            text.append(nl);
            text.append("  /* 官方平台配置 */").append(nl);
            text.append("  \"qq_official\": {").append(nl);
            text.append("    /* 官方环境 ID */").append(nl);
            text.append("    \"environmentId\": \"<RESTORE_ENVIRONMENT_UUID>\",").append(nl);
            text.append("    /* 绑定群的 openid */").append(nl);
            text.append("    \"bindingGroupId\": \"<RESTORE_GROUP_OPENID>\",").append(nl);
            text.append("    /* 显示用群号，必须替换为真实群号；只填数字 */").append(nl);
            text.append("    \"groupHint\": \"123456789\",").append(nl);
            text.append("    /* 本服 UUID；从插件「群与服务器 → 编辑服务器」复制，必须完全一致 */").append(nl);
            text.append("    \"serverId\": \"<RESTORE_SERVER_UUID>\",").append(nl);
            text.append("    /* 存储模式：local=本地，hybrid=混合，remote=远程 */").append(nl);
            text.append("    \"storageMode\": \"local\",").append(nl);
            text.append("    /* 本地状态固定保存在 qqbind/official/ 下的环境/服务器/群子目录，无需配置 */").append(nl);
            text.append("    /* 中心库状态刷新间隔（秒），0 表示每次维护都刷新 */").append(nl);
            text.append("    \"cacheTtlSeconds\": 60,").append(nl);
            text.append("    /* 维护失败后的首次重试间隔（秒） */").append(nl);
            text.append("    \"dbRetryIntervalSeconds\": 30,").append(nl);
            text.append("    /* 最大退避次数，0 表示不限 */").append(nl);
            text.append("    \"dbMaxRetries\": 0,").append(nl);
            text.append("    /* 官方配置修订号，通常保持 1；仅在官方中心配置升级时修改 */").append(nl);
            text.append("    \"configurationRevision\": \"1\",").append(nl);
            text.append("    /* 是否重建本地状态 */").append(nl);
            text.append("    \"createFreshState\": false,").append(nl);
            text.append("    /* 是否允许中心库同步 */").append(nl);
            text.append("    \"allowCenterSync\": false,").append(nl);
            text.append("    /* 中心数据库地址；local 时自动忽略，hybrid/remote 时读取 */").append(nl);
            text.append("    \"jdbcUrl\": \"jdbc:mysql://<DB_HOST>:3306/<DB_NAME>\",").append(nl);
            text.append("    /* 中心数据库用户名；local 时自动忽略 */").append(nl);
            text.append("    \"dbUser\": \"<DATABASE_USER>\",").append(nl);
            text.append("    /* 中心数据库密码；local 时自动忽略 */").append(nl);
            text.append("    \"dbPassword\": \"<DATABASE_PASSWORD>\"").append(nl);
            text.append("  }").append(nl);
            text.append("}").append(nl);

            Files.writeString(configPath, text.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            loadError = "配置文件缺失，已生成官方恢复模板（bindingPlatform=qq_official）；HTTP API 保持关闭。"
                    + "请填写真实 environmentId、bindingGroupId、serverId、apiToken、storageMode/database、"
                    + "configurationRevision 与 allowCenterSync 后完整停启。";
            LOGGER.error(loadError);
        } catch (FileAlreadyExistsException e) {
            loadError = "配置文件在加载期间已被创建；请检查后重新加载或完整停启。";
            LOGGER.error(loadError);
        } catch (Exception e) {
            loadError = "配置文件缺失，官方恢复模板生成失败；HTTP API 保持关闭。请检查 config/qqbind 目录权限后完整停启。";
            LOGGER.error(loadError);
        }
    }

    /**
     * 创建默认配置文件。所有常用项直接生效，两个平台段的数据库字段也默认写出；
     * storageMode=local 时运行时会自动忽略这些字段，不会建立数据库连接。
     */
    private static void createDefaultConfig(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            // 新生成的配置先用本地存储，避免带着占位 JDBC 连接串尝试远程库；
            // 需要跨服时再改 hybrid/remote 并补数据库字段。
            String text = defaultConfigTemplate();
            Files.writeString(configPath, text, StandardCharsets.UTF_8);
            applyConfig(OfficialRuntimeSettings.document(text));
            LOGGER.info("Created default config file at {}", configPath);
        } catch (Exception e) {
            loadError="默认配置创建失败，绑定入口未初始化。";
            LOGGER.error(loadError);
        }
    }

    /** JSONC 模板：全部字段默认生效；local 模式由运行期自动忽略数据库连接字段。 */
    private static String defaultConfigTemplate() {
        String nl = System.lineSeparator();
        StringBuilder text = new StringBuilder(8192);
        text.append("/*").append(nl);
        text.append("QQBind 配置（JSONC）").append(nl);
        text.append("修改后执行 /qqbind reload").append(nl);
        text.append("端口、存储模式、绑定平台、官方运行期参数变更需完整停启").append(nl);
        text.append("*/").append(nl);
        text.append("{").append(nl);
        text.append("  /* 格式版本，固定为 2 */").append(nl);
        text.append("  \"configVersion\": ").append(CONFIG_VERSION).append(",").append(nl);
        text.append("  /* 当前生效平台：onebot 或 qq_official */").append(nl);
        text.append("  \"bindingPlatform\": ").append(quote(BINDING_PLATFORM)).append(",").append(nl);
        text.append(nl);
        text.append("  /* HTTP API 端口，修改后需重启 */").append(nl);
        text.append("  \"httpPort\": ").append(HTTP_PORT).append(",").append(nl);
        text.append("  /* 监听地址，0.0.0.0 表示允许远程访问 */").append(nl);
        text.append("  \"httpBindHost\": ").append(quote(HTTP_BIND_HOST)).append(",").append(nl);
        text.append("  /* API 令牌，必须与插件配置保持一致 */").append(nl);
        text.append("  \"apiToken\": ").append(quote(API_TOKEN)).append(",").append(nl);
        text.append("  /* 是否拦截未绑定玩家 */").append(nl);
        text.append("  \"enableWhitelistCheck\": ").append(ENABLE_WHITELIST_CHECK).append(",").append(nl);
        text.append(nl);
        text.append("  /* 面板控制台令牌；留空不启用 /api/console，启用时至少 16 位 */").append(nl);
        text.append("  \"consoleToken\": \"\",").append(nl);
        text.append("  /* 控制台额外禁用的命令名，[] 表示不额外禁用 */").append(nl);
        text.append("  \"consoleBannedCommands\": [],").append(nl);
        text.append("  /* 未绑定提示标题 */").append(nl);
        text.append("  \"titleTemplate\": ").append(quote(TITLE_TEMPLATE)).append(",").append(nl);
        text.append("  /* 未绑定提示副标题；{groupHint}=各平台段 groupHint 的值，{bindCommand}=绑定指令 */").append(nl);
        text.append("  \"subtitleTemplate\": ").append(quote(SUBTITLE_TEMPLATE)).append(",").append(nl);
        text.append("  /* 未绑定提示动作栏 */").append(nl);
        text.append("  \"actionBarTemplate\": ").append(quote(ACTION_BAR_TEMPLATE)).append(",").append(nl);
        text.append("  /* 绑定成功标题 */").append(nl);
        text.append("  \"bindSuccessTitle\": ").append(quote(BIND_SUCCESS_TITLE)).append(",").append(nl);
        text.append("  /* 绑定成功副标题 */").append(nl);
        text.append("  \"bindSuccessSubtitle\": ").append(quote(BIND_SUCCESS_SUBTITLE)).append(",").append(nl);
        text.append("  /* 绑定成功动作栏 */").append(nl);
        text.append("  \"bindSuccessActionBar\": ").append(quote(BIND_SUCCESS_ACTION_BAR)).append(",").append(nl);
        text.append(nl);
        text.append("  /* OneBot 平台配置 */").append(nl);
        text.append("  \"onebot\": {").append(nl);
        text.append("    /* QQ 群号，必须替换为真实群号；也用于数据隔离 */").append(nl);
        text.append("    \"qqGroup\": ").append(quote(QQ_GROUP)).append(",").append(nl);
        text.append("    /* 显示用群号；留空自动使用 qqGroup */").append(nl);
        text.append("    \"groupHint\": \"\",").append(nl);
        text.append("    /* 存储模式：local=本地，hybrid=混合，remote=远程 */").append(nl);
        text.append("    \"storageMode\": \"local\",").append(nl);
        text.append("    /* 本服标识；可留空或保留占位，仅在进程内生成，不写回本文件 */").append(nl);
        text.append("    \"serverId\": \"<SERVER_UUID>\",").append(nl);
        text.append("    /* 本地绑定数据固定保存在 qqbind/bindings.json，无需配置 */").append(nl);
        text.append("    /* 内存缓存秒数 */").append(nl);
        text.append("    \"cacheTtlSeconds\": ").append(CACHE_TTL_SECONDS).append(",").append(nl);
        text.append("    /* 数据库重试间隔秒 */").append(nl);
        text.append("    \"dbRetryIntervalSeconds\": ").append(DB_RETRY_INTERVAL_SECONDS).append(",").append(nl);
        text.append("    /* 最大重试次数，0 表示不限 */").append(nl);
        text.append("    \"dbMaxRetries\": ").append(DB_MAX_RETRIES).append(",").append(nl);
        text.append("    /* 中心数据库地址，非 local 时填写 */").append(nl);
        text.append("    \"jdbcUrl\": \"jdbc:mysql://<DB_HOST>:3306/<DB_NAME>\",").append(nl);
        text.append("    /* 数据库用户名，非 local 时填写 */").append(nl);
        text.append("    \"dbUser\": \"<DATABASE_USER>\",").append(nl);
        text.append("    /* 数据库密码，非 local 时填写 */").append(nl);
        text.append("    \"dbPassword\": \"<DATABASE_PASSWORD>\"").append(nl);
        text.append("  },").append(nl);
        text.append(nl);
        text.append("  /* QQ 官方平台配置；切换平台时只需改 bindingPlatform */").append(nl);
        text.append("  \"qq_official\": {").append(nl);
        text.append("    /* 官方环境 ID */").append(nl);
        text.append("    \"environmentId\": \"<ENVIRONMENT_UUID>\",").append(nl);
        text.append("    /* 绑定群的 openid */").append(nl);
        text.append("    \"bindingGroupId\": \"<GROUP_OPENID>\",").append(nl);
        text.append("    /* 显示用群号，必须替换为真实群号；只填数字 */").append(nl);
        text.append("    \"groupHint\": \"123456789\",").append(nl);
        text.append("    /* 本服 UUID；从插件「群与服务器 → 编辑服务器」复制，必须完全一致 */").append(nl);
        text.append("    \"serverId\": \"<SERVER_UUID>\",").append(nl);
        text.append("    /* 存储模式：local=本地，hybrid=混合，remote=远程 */").append(nl);
        text.append("    \"storageMode\": \"local\",").append(nl);
        text.append("    /* 本地状态固定保存在 qqbind/official/ 下的环境/服务器/群子目录，无需配置 */").append(nl);
        text.append("    /* 中心库状态刷新间隔（秒），0 表示每次维护都刷新 */").append(nl);
        text.append("    \"cacheTtlSeconds\": 60,").append(nl);
        text.append("    /* 维护失败后的首次重试间隔（秒） */").append(nl);
        text.append("    \"dbRetryIntervalSeconds\": 30,").append(nl);
        text.append("    /* 最大退避次数，0 表示不限 */").append(nl);
        text.append("    \"dbMaxRetries\": 0,").append(nl);
        text.append("    /* 官方配置修订号，通常保持 1；仅在官方中心配置升级时修改 */").append(nl);
        text.append("    \"configurationRevision\": \"1\",").append(nl);
        text.append("    /* 是否重建本地状态 */").append(nl);
        text.append("    \"createFreshState\": false,").append(nl);
        text.append("    /* 是否允许中心库同步 */").append(nl);
        text.append("    \"allowCenterSync\": false,").append(nl);
        text.append("    /* 中心数据库地址；local 时自动忽略，hybrid/remote 时读取 */").append(nl);
        text.append("    \"jdbcUrl\": \"jdbc:mysql://<DB_HOST>:3306/<DB_NAME>\",").append(nl);
        text.append("    /* 中心数据库用户名；local 时自动忽略 */").append(nl);
        text.append("    \"dbUser\": \"<DATABASE_USER>\",").append(nl);
        text.append("    /* 中心数据库密码；local 时自动忽略 */").append(nl);
        text.append("    \"dbPassword\": \"<DATABASE_PASSWORD>\"").append(nl);
        text.append("  }").append(nl);
        text.append("}").append(nl);
        return text.toString();
    }

    /** 将任意字符串安全地写成 JSON 字符串字面量（模板生成用）。 */
    private static String quote(String value) {
        return GSON.toJson(value == null ? "" : value);
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
     * 工具方法：将消息模板中的 {qqGroup} 和 {token} 占位符替换为实际值。
     * 只适用于旧模板；新模板应使用下面的平台上下文重载。
     * @param template 模板字符串
     * @param token 令牌（可为 null）
     * @return 替换后的字符串
     */
    public static String formatMessage(String template, String token) {
        // 调用方都处在 OneBot 旧路径；{groupHint} 优先用 onebot.groupHint，留空回退 qqGroup。
        String hint = ONEBOT_GROUP_HINT == null || ONEBOT_GROUP_HINT.isEmpty() ? QQ_GROUP : ONEBOT_GROUP_HINT;
        return formatMessage(template, token, hint, token == null ? null : "/绑定 " + token);
    }

    /**
     * 工具方法：按当前平台填写模板占位符。
     *
     * <p>{groupHint} 是“去哪个群发送”的展示文本：OneBot 取 onebot.groupHint，
     * 留空时回退 qqGroup；官方取 qq_official.groupHint（未配置时为“当前官方群”）。
     * {bindCommand} 是完整绑定指令（OneBot 为 /绑定 令牌，官方为 /mc bind 令牌），
     * 因此同一份模板可以被两个平台复用，不需要额外的群号/指令字段。
     * {qqGroup} 只为旧模板保留，新模板不要再用。
     *
     * @param template 模板字符串
     * @param token 令牌（可为 null）
     * @param groupHint 群提示（可为 null；沿用 {qqGroup} 的旧值）
     * @param bindCommand 完整绑定指令（可为 null）
     * @return 替换后的字符串
     */
    public static String formatMessage(String template, String token, String groupHint, String bindCommand) {
        if (template == null) return "";
        String result = template.replace("{qqGroup}", QQ_GROUP);
        if (groupHint != null) result = result.replace("{groupHint}", groupHint);
        if (bindCommand != null) result = result.replace("{bindCommand}", bindCommand);
        if (token != null) result = result.replace("{token}", token);
        return result;
    }
}
