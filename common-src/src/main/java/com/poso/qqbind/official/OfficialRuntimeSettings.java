package com.poso.qqbind.official;

import com.google.gson.*;
import com.poso.qqbind.utils.Jsonc;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;

/** Redacted runtime selection parsed ONLY from a supplied document.
 * Everything except the server-local live whitelist switch is immutable.
 * No file, environment, old JDBC URL or legacy identity lookup is performed.
 */
public final class OfficialRuntimeSettings {
    private final boolean official;
    private final OfficialScope scope;
    private final String mode,revision,token,dataDirectory;
    private final boolean fresh,publish;
    private volatile boolean whitelist;
    private final Properties database;
    private final int cacheTtlSeconds,dbRetryIntervalSeconds,dbMaxRetries;
    private String consoleToken = "";
    private OfficialRuntimeSettings(boolean official,OfficialScope scope,String mode,String revision,String token,
                                    boolean fresh,boolean publish,boolean whitelist,Properties database,
                                    String dataDirectory,int cacheTtlSeconds,int dbRetryIntervalSeconds,int dbMaxRetries) {
        this.official=official; this.scope=scope; this.mode=mode; this.revision=revision; this.token=token;
        this.fresh=fresh; this.publish=publish; this.whitelist=whitelist; this.database=database;
        this.dataDirectory=dataDirectory; this.cacheTtlSeconds=cacheTtlSeconds;
        this.dbRetryIntervalSeconds=dbRetryIntervalSeconds; this.dbMaxRetries=dbMaxRetries;
    }
    public static JsonObject document(String raw) {
        try {
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 1024*1024) throw new IOException();
            // 配置文件允许 JSONC：先去掉注释和尾逗号，严格解析/重复键检查保持不变。
            JsonElement parsed=OfficialModState.parse(Jsonc.strip(raw));
            if (!parsed.isJsonObject()) throw new IOException(); return parsed.getAsJsonObject();
        } catch (Exception e) { throw new OfficialException("INVALID_INPUT"); }
    }
    private static String text(JsonObject value,String name) { return OfficialModState.string(value,name); }
    private static boolean bool(JsonObject value,String name,boolean fallback) {
        if (!value.has(name)) return fallback;
        JsonElement item=value.get(name);
        if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) throw new OfficialException("INVALID_INPUT"); return item.getAsBoolean();
    }
    private static int integer(JsonObject value,String name,int fallback,int min) {
        if (!value.has(name)) return fallback;
        JsonElement item=value.get(name);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) throw new OfficialException("INVALID_INPUT");
        String raw=item.getAsString();
        if (!raw.matches("[0-9]{1,10}")) throw new OfficialException("INVALID_INPUT");
        long parsed=Long.parseLong(raw);
        if (parsed < min || parsed > Integer.MAX_VALUE) throw new OfficialException("INVALID_INPUT");
        return (int)parsed;
    }
    /** 官方状态目录必须是工作目录内的相对路径；拒绝绝对路径、盘符、空段和 .. 穿越。 */
    private static String directory(String value) {
        try {
            if (value == null || value.isEmpty() || value.length() > 128
                    || value.indexOf(0) >= 0 || value.indexOf(':') >= 0) throw new IllegalArgumentException();
            Path path=Path.of(value);
            if (path.isAbsolute() || path.getRoot() != null) throw new IllegalArgumentException();
            for (Path part : path) {
                String segment=part.toString();
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException();
            }
            String normalized=path.normalize().toString().replace('\\','/');
            if (normalized.isEmpty() || normalized.equals(".") || normalized.startsWith("../")) throw new IllegalArgumentException();
            return normalized;
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("INVALID_INPUT"); }
    }
    public static OfficialRuntimeSettings parse(JsonObject json) {
        try {
            String platform=json.has("bindingPlatform") ? text(json,"bindingPlatform") : "onebot";
            if (!json.has("bindingPlatform") && (json.has("official") || json.has("environmentId") || json.has("bindingGroupId"))) throw new OfficialException("INVALID_INPUT");
            if (platform.equals("onebot")) return new OfficialRuntimeSettings(false,null,"legacy","1","",false,false,true,
                    new Properties(),"qqbind/official",60,30,0);
            if (!platform.equals("qq_official")) throw new OfficialException("INVALID_INPUT");
            OfficialScope scope=new OfficialScope(text(json,"environmentId"),text(json,"bindingGroupId"),text(json,"serverId"));
            String mode=text(json,"storageMode"),token=text(json,"apiToken");
            if (!Set.of("hybrid","remote","local").contains(mode) || !safeToken(token)) throw new OfficialException("INVALID_INPUT");
            JsonObject official=json.has("official") ? json.getAsJsonObject("official") : null;
            String revision;
            boolean fresh, publish;
            Properties database=new Properties();
            if (official != null) {
                // 旧版嵌套写法：official 对象里的字段优先，顶层同名扁平字段不再参与，
                // 这样从 OneBot 迁移过来的旧配置即使残留 jdbcUrl 也不会改变官方数据库。
                if (!Set.of("configurationRevision","createFreshState","allowCenterSync","database").containsAll(official.keySet())) throw new OfficialException("INVALID_INPUT");
                revision=text(official,"configurationRevision");
                fresh=bool(official,"createFreshState",false); publish=bool(official,"allowCenterSync",false);
                if (!mode.equals("local")) {
                    JsonObject db=official.getAsJsonObject("database");
                    if (db == null) throw new OfficialException("INVALID_INPUT");
                    if (db.keySet().equals(Set.of("jdbcUrl","dbUser","dbPassword"))) {
                        database=unifiedDatabase(db);
                    } else if (db.keySet().equals(Set.of("host","port","database","user","password"))) {
                        database=legacyDatabase(db);
                    } else throw new OfficialException("INVALID_INPUT");
                }
                // local 模式不再检查 official.database：字段可以保留，但整段被忽略，
                // 不会建立任何中心数据库连接。
            } else {
                // 新版扁平写法：官方运行时字段直接放在 qq_official 段（或扁平官方配置顶层）。
                // 出现 OneBot 专属字段说明这是一份未清理的旧配置，拒绝以免把 OneBot 数据库
                // 当成官方中心库连接。
                if (json.has("dataFilePath") || json.has("qqGroup")) throw new OfficialException("INVALID_INPUT");
                revision=text(json,"configurationRevision");
                fresh=bool(json,"createFreshState",false); publish=bool(json,"allowCenterSync",false);
                if (!mode.equals("local")) {
                    if (!json.has("jdbcUrl") || !json.has("dbUser") || !json.has("dbPassword")) throw new OfficialException("INVALID_INPUT");
                    JsonObject db=new JsonObject();
                    db.add("jdbcUrl",json.get("jdbcUrl")); db.add("dbUser",json.get("dbUser")); db.add("dbPassword",json.get("dbPassword"));
                    database=unifiedDatabase(db);
                }
                // local 模式自动排除 jdbcUrl/dbUser/dbPassword：字段存在也不读取、不校验、
                // 不建立连接，因此模板可以默认保留它们而不影响本地模式。
            }
            OfficialScope.revision(revision);
            // 与 OneBot 段对齐的通用存储参数：只影响官方运行期，不会读取 OneBot 段。
            int cacheTtl=integer(json,"cacheTtlSeconds",60,0);
            int retryInterval=integer(json,"dbRetryIntervalSeconds",30,1);
            int maxRetries=integer(json,"dbMaxRetries",0,0);
            String dataDirectory=directory(json.has("dataDirectory") ? text(json,"dataDirectory") : "qqbind/official");
            OfficialRuntimeSettings result=new OfficialRuntimeSettings(true,scope,mode,revision,token,fresh,
                publish,bool(json,"enableWhitelistCheck",true),database,dataDirectory,cacheTtl,retryInterval,maxRetries);
            result.consoleToken=json.has("consoleToken") ? text(json,"consoleToken") : "";
            if (!result.consoleToken.isEmpty() && (!safeToken(result.consoleToken) || result.consoleToken.equals(token))) throw new OfficialException("INVALID_INPUT");
            if (mode.equals("local") && result.allowCenterSync()) throw new OfficialException("SCOPE_MISMATCH");
            return result;
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("INVALID_INPUT"); }
    }
    /**
     * 与 OneBot 同名的 jdbcUrl/dbUser/dbPassword。官方连接器只使用
     * jdbc:mysql://主机[:端口]/库名 三段，并固定启用 TLS/超时等安全选项；
     * URL 里的查询参数会被忽略，不接受主机名之外的连接方式。
     */
    private static Properties unifiedDatabase(JsonObject db) {
        String url=text(db,"jdbcUrl"), user=text(db,"dbUser"), password=text(db,"dbPassword");
        if (url == null || url.length() > 2048 || !url.startsWith("jdbc:mysql://")) throw new OfficialException("INVALID_INPUT");
        String rest=url.substring("jdbc:mysql://".length());
        int query=rest.indexOf('?');
        if (query >= 0) rest=rest.substring(0,query);
        int slash=rest.indexOf('/');
        if (slash <= 0 || slash == rest.length()-1) throw new OfficialException("INVALID_INPUT");
        String authority=rest.substring(0,slash), name=rest.substring(slash+1);
        if (name.indexOf('/') >= 0 || name.indexOf('#') >= 0) throw new OfficialException("INVALID_INPUT");
        String host=authority; int number=3306;
        int colon=authority.lastIndexOf(':');
        if (colon >= 0) {
            host=authority.substring(0,colon);
            String port=authority.substring(colon+1);
            if (!port.matches("[0-9]{1,5}")) throw new OfficialException("INVALID_INPUT");
            number=Integer.parseInt(port);
            if (number < 1 || number > 65535) throw new OfficialException("INVALID_INPUT");
        }
        if (!host.matches("[A-Za-z0-9.-]{1,253}") || !name.matches("[A-Za-z0-9_]{1,64}")) throw new OfficialException("INVALID_INPUT");
        Properties result=new Properties();
        result.setProperty("host",host); result.setProperty("port",Integer.toString(number)); result.setProperty("database",name);
        for (String[] field : new String[][]{{"user",user},{"password",password}}) {
            String value=field[1];
            if (value == null || value.isEmpty() || value.indexOf(0) >= 0) throw new OfficialException("INVALID_INPUT");
            result.setProperty(field[0],value);
        }
        return result;
    }

    /** 旧版官方配置：official.database 直接给 host/port/database/user/password，继续兼容。 */
    private static Properties legacyDatabase(JsonObject db) {
        String host=text(db,"host"),name=text(db,"database"); JsonElement port=db.get("port");
        if (!host.matches("[A-Za-z0-9.-]{1,253}") || !name.matches("[A-Za-z0-9_]{1,64}")
                || port == null || !port.isJsonPrimitive() || !port.getAsJsonPrimitive().isNumber() || !port.getAsString().matches("[0-9]{1,5}")) throw new OfficialException("INVALID_INPUT");
        int number=port.getAsInt(); if (number < 1 || number > 65535) throw new OfficialException("INVALID_INPUT");
        Properties result=new Properties();
        result.setProperty("host",host); result.setProperty("port",Integer.toString(number)); result.setProperty("database",name);
        for (String field : List.of("user","password")) {
            String value=text(db,field); if (value.isEmpty() || value.indexOf(0) >= 0) throw new OfficialException("INVALID_INPUT"); result.setProperty(field,value);
        }
        return result;
    }

    private static boolean safeToken(String token) {
        return token != null && token.length() >= 16 && token.length() <= 1024 && !token.equals("change-me-to-a-random-token")
            && token.chars().noneMatch(c -> c <= 32 || c >= 127);
    }
    public boolean official() { return official; }
    public OfficialScope scope() { if (!official) throw new OfficialException("NOT_SUPPORTED"); return scope; }
    public String mode() { return mode; }
    public String revision() { return revision; }
    public String dataDirectory() { return dataDirectory; }
    public int cacheTtlSeconds() { return cacheTtlSeconds; }
    public int dbRetryIntervalSeconds() { return dbRetryIntervalSeconds; }
    public int dbMaxRetries() { return dbMaxRetries; }
    public boolean createFresh() { return fresh; }
    public boolean allowCenterSync() { return publish; }
    public boolean whitelistCheck() { return whitelist; }
    /** Live server-local switch. Caller authenticates the official scope first. */
    void setWhitelistCheck(boolean enabled) { whitelist=enabled; }
    public Path stateDirectory() {
        try {
            String group=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope().groupId().getBytes(StandardCharsets.UTF_8)));
            Path directory=Path.of(dataDirectory).resolve(scope().environmentId()).resolve(scope().serverId()).resolve(group);
            return mode.equals("local") ? directory.resolve("local") : directory;
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
    }
    /** Scope- and token-authenticated persistent player marker. */
    public String restrictionMarker(String playerId, String mode) {
        if (!official || !Set.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR").contains(mode))
            throw new OfficialException("NOT_SUPPORTED");
        try {
            String player = OfficialScope.uuid(playerId);
            String payload = "official-restriction-v1\n" + scope.environmentId() + "\n" + scope.groupId()
                    + "\n" + scope.serverId() + "\n" + player + "\n" + mode;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return "qqbind-official-restriction-v1:" + player + ":" + mode + ":"
                    + HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
    }
    public boolean verifiesRestrictionMarker(String playerId, String mode, String marker) {
        if (marker == null || marker.length() > 192 || marker.chars().anyMatch(c -> c < 33 || c >= 127)) return false;
        try {
            return MessageDigest.isEqual(restrictionMarker(playerId, mode).getBytes(StandardCharsets.US_ASCII),
                    marker.getBytes(StandardCharsets.US_ASCII));
        } catch (OfficialException e) { return false; }
    }
    String bearerToken() { return token; }
    Properties databaseProperties() { Properties copy=new Properties(); copy.putAll(database); return copy; }
    boolean authenticates(String header) {
        return official && header != null && header.length() <= 1031 && header.chars().noneMatch(c -> c < 32 || c >= 127)
            && MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.US_ASCII),header.getBytes(StandardCharsets.US_ASCII));
    }
    boolean authenticatesConsole(String header) {
        return !consoleToken.isEmpty() && header!=null && header.length()<=1031 && header.chars().noneMatch(c -> c<32 || c>=127)
            && MessageDigest.isEqual(("Bearer "+consoleToken).getBytes(StandardCharsets.US_ASCII),header.getBytes(StandardCharsets.US_ASCII));
    }
    boolean sameRuntime(OfficialRuntimeSettings other) {
        return other != null && official == other.official && Objects.equals(scope,other.scope) && mode.equals(other.mode)
            && revision.equals(other.revision) && token.equals(other.token) && fresh == other.fresh && publish == other.publish
            && whitelist == other.whitelist && consoleToken.equals(other.consoleToken) && database.equals(other.database)
            && dataDirectory.equals(other.dataDirectory) && cacheTtlSeconds == other.cacheTtlSeconds
            && dbRetryIntervalSeconds == other.dbRetryIntervalSeconds && dbMaxRetries == other.dbMaxRetries;
    }
    @Override public String toString() { return official ? "BindingRuntimeSettings[qq_official,redacted]" : "BindingRuntimeSettings[onebot]"; }
}
