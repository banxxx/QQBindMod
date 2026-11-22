package com.poso.qqbind.official;

import com.google.gson.*;
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
    private final String mode,revision,token;
    private final boolean fresh,publish;
    private volatile boolean whitelist;
    private final Properties database;
    private String consoleToken = "";
    private OfficialRuntimeSettings(boolean official,OfficialScope scope,String mode,String revision,String token,
                                    boolean fresh,boolean publish,boolean whitelist,Properties database) {
        this.official=official; this.scope=scope; this.mode=mode; this.revision=revision; this.token=token;
        this.fresh=fresh; this.publish=publish; this.whitelist=whitelist; this.database=database;
    }
    public static JsonObject document(String raw) {
        try {
            if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 1024*1024) throw new IOException();
            JsonElement parsed=OfficialModState.parse(raw);
            if (!parsed.isJsonObject()) throw new IOException(); return parsed.getAsJsonObject();
        } catch (Exception e) { throw new OfficialException("INVALID_INPUT"); }
    }
    private static String text(JsonObject value,String name) { return OfficialModState.string(value,name); }
    private static boolean bool(JsonObject value,String name,boolean fallback) {
        if (!value.has(name)) return fallback;
        JsonElement item=value.get(name);
        if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) throw new OfficialException("INVALID_INPUT"); return item.getAsBoolean();
    }
    public static OfficialRuntimeSettings parse(JsonObject json) {
        try {
            String platform=json.has("bindingPlatform") ? text(json,"bindingPlatform") : "onebot";
            if (!json.has("bindingPlatform") && (json.has("official") || json.has("environmentId") || json.has("bindingGroupId"))) throw new OfficialException("INVALID_INPUT");
            if (platform.equals("onebot")) return new OfficialRuntimeSettings(false,null,"legacy","1","",false,false,true,new Properties());
            if (!platform.equals("qq_official")) throw new OfficialException("INVALID_INPUT");
            OfficialScope scope=new OfficialScope(text(json,"environmentId"),text(json,"bindingGroupId"),text(json,"serverId"));
            String mode=text(json,"storageMode"),token=text(json,"apiToken");
            if (!Set.of("hybrid","remote","local").contains(mode) || !safeToken(token)) throw new OfficialException("INVALID_INPUT");
            JsonObject official=json.getAsJsonObject("official");
            if (official == null || !Set.of("configurationRevision","createFreshState","allowCenterSync","database").containsAll(official.keySet())) throw new OfficialException("INVALID_INPUT");
            String revision=text(official,"configurationRevision"); OfficialScope.revision(revision);
            Properties database=new Properties();
            if (!mode.equals("local")) {
                JsonObject db=official.getAsJsonObject("database");
                if (db == null || !db.keySet().equals(Set.of("host","port","database","user","password"))) throw new OfficialException("INVALID_INPUT");
                String host=text(db,"host"),name=text(db,"database"); JsonElement port=db.get("port");
                if (!host.matches("[A-Za-z0-9.-]{1,253}") || !name.matches("[A-Za-z0-9_]{1,64}")
                        || port == null || !port.isJsonPrimitive() || !port.getAsJsonPrimitive().isNumber() || !port.getAsString().matches("[0-9]{1,5}")) throw new OfficialException("INVALID_INPUT");
                int number=port.getAsInt(); if (number < 1 || number > 65535) throw new OfficialException("INVALID_INPUT");
                database.setProperty("host",host); database.setProperty("port",Integer.toString(number)); database.setProperty("database",name);
                for (String field : List.of("user","password")) {
                    String value=text(db,field); if (value.isEmpty() || value.indexOf(0) >= 0) throw new OfficialException("INVALID_INPUT"); database.setProperty(field,value);
                }
            } else if (official.has("database")) throw new OfficialException("INVALID_INPUT");
            OfficialRuntimeSettings result=new OfficialRuntimeSettings(true,scope,mode,revision,token,bool(official,"createFreshState",false),
                bool(official,"allowCenterSync",false),bool(json,"enableWhitelistCheck",true),database);
            result.consoleToken=json.has("consoleToken") ? text(json,"consoleToken") : "";
            if (!result.consoleToken.isEmpty() && (!safeToken(result.consoleToken) || result.consoleToken.equals(token))) throw new OfficialException("INVALID_INPUT");
            if (mode.equals("local") && result.allowCenterSync()) throw new OfficialException("SCOPE_MISMATCH");
            return result;
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("INVALID_INPUT"); }
    }
    private static boolean safeToken(String token) {
        return token != null && token.length() >= 16 && token.length() <= 1024 && !token.equals("change-me-to-a-random-token")
            && token.chars().noneMatch(c -> c <= 32 || c >= 127);
    }
    public boolean official() { return official; }
    public OfficialScope scope() { if (!official) throw new OfficialException("NOT_SUPPORTED"); return scope; }
    public String mode() { return mode; }
    public String revision() { return revision; }
    public boolean createFresh() { return fresh; }
    public boolean allowCenterSync() { return publish; }
    public boolean whitelistCheck() { return whitelist; }
    /** Live server-local switch. Caller authenticates the official scope first. */
    void setWhitelistCheck(boolean enabled) { whitelist=enabled; }
    public Path stateDirectory() {
        try {
            String group=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope().groupId().getBytes(StandardCharsets.UTF_8)));
            Path directory=Path.of("qqbind","official",scope().environmentId(),scope().serverId(),group);
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
            && whitelist == other.whitelist && consoleToken.equals(other.consoleToken) && database.equals(other.database);
    }
    @Override public String toString() { return official ? "BindingRuntimeSettings[qq_official,redacted]" : "BindingRuntimeSettings[onebot]"; }
}
