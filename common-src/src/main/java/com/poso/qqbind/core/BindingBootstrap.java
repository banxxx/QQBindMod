package com.poso.qqbind.core;

import com.google.gson.JsonObject;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.official.*;
import com.poso.qqbind.server.ServerProviderHolder;
import com.poso.qqbind.storage.DataStorage;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native host assembly shared by all four loaders. Legacy storage stays lazy. */
public final class BindingBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(BindingBootstrap.class);
    private static volatile OfficialRuntimeSession official;
    private static volatile BindingManager legacy;
    private static boolean started;
    private static volatile String status="not_started";
    private static final OfficialMirrorService.GameEffects ONLINE_EFFECTS = new OfficialMirrorService.GameEffects() {
        public void whitelist(String gameId, boolean active) {
            // Vanilla add/remove synchronously saves. Only online tickets are scheduled here.
            throw new OfficialException("NOT_SUPPORTED");
        }
        public void online(String gameId, boolean active) { PlayerStateManager.applyOfficialBindingState(gameId, active); }
    };
    private BindingBootstrap() { }
    public static synchronized BindingManager start(Supplier<DataStorage> legacyStorage) {
        if (started) throw new OfficialException("CONFLICT");
        OfficialRuntimeSettings settings;
        try { settings=QQBindConfig.bindingRuntime().pin(); }
        catch (OfficialException e) { started=true; status="binding_blocked_" + e.code(); return null; }
        started=true;
        if (!settings.official()) {
            legacy=new BindingManager(legacyStorage.get()); status="onebot"; return legacy;
        }
        try {
            official=new OfficialRuntimeSession(QQBindConfig.bindingRuntime(),settings,Path.of(""),() -> {
                try { return new com.mysql.cj.jdbc.Driver(); }
                catch (java.sql.SQLException e) { throw new OfficialException("DB_UNAVAILABLE"); }
            },() -> {
                var server=ServerProviderHolder.get().getCurrentServer(); return server != null && server.isSameThread();
            }, PlayerStateManager::queueOfficialCodeNotice);
            official.commandExecutor(new OfficialCommandService.Executor() {
                private final ThreadLocal<JsonObject> output=new ThreadLocal<>();
                public String execute(String action,String payload,java.util.function.BooleanSupplier active) {
                    output.remove(); JsonObject result=CommandExecutor.executeOfficialResult(action,payload,active);
                    output.set(result); return result.get("state").getAsString();
                }
                public JsonObject takeOutput() { JsonObject value=output.get(); output.remove(); return value; }
            });
            official.whitelistToggle(enabled -> {
                try {
                    return MainThread.call(3000, server -> PlayerStateManager.revalidateOfficialWhitelist(enabled));
                } catch (Exception error) {
                    // The switch is already durable; an online snapshot failure must not
                    // make the HTTP caller retry a successful configuration change.
                    LOGGER.warn("官方登录限制已切换，但在线玩家重校验未完成: {}", error.getMessage());
                    return new int[]{0,0};
                }
            });
            status=settings.createFresh() ? "official_initialized_restart_required" : "official_core_only";
        } catch (OfficialException e) { status="official_blocked_" + e.code(); }
        catch (Exception e) { status="official_blocked_STORAGE_ERROR"; }
        return null; // Never create/read old storage as an official fallback.
    }
    public static boolean officialBoundFast(String gameId) {
        OfficialRuntimeSession value=official; return value != null && value.isBoundFast(gameId);
    }
    public static boolean officialActive() {
        OfficialRuntimeSession value = official; return value != null && value.active();
    }
    /** The loader supplies its real whitelist; a test map never enables this path. */
    public static void installOfficialWhitelist(OfficialWhitelistHost host) {
        OfficialRuntimeSession value = official;
        if (value == null || !value.active()) { host.close(); return; }
        try { value.installWhitelistOnGame(host); }
        catch (OfficialException error) { status = "official_whitelist_blocked_" + error.code(); }
    }
    static OfficialLoginCodes officialLoginCodes() {
        OfficialRuntimeSession value = official;
        return value == null ? null : value.loginCodes();
    }
    public static void tickOfficial() {
        OfficialRuntimeSession value = official;
        if (value != null && value.active()) value.tick(ONLINE_EFFECTS);
    }
    public static boolean officialSnapshotReady() {
        OfficialRuntimeSession value = official;
        return value != null && value.snapshotReady();
    }
    public static boolean officialBindingReadyFast() {
        OfficialRuntimeSession value=official;return value!=null && value.bindingReadyFast();
    }
    static String officialRestrictionMarker(String playerId, String mode) {
        OfficialRuntimeSession value = official;
        return value == null ? null : value.restrictionMarker(playerId, mode);
    }
    static boolean verifiesOfficialRestrictionMarker(String playerId, String mode, String marker) {
        OfficialRuntimeSession value = official;
        return value != null && value.verifiesRestrictionMarker(playerId, mode, marker);
    }
    static void reconcileOfficialOnline(String gameId, Runnable restore) {
        OfficialRuntimeSession value = official;
        if (value != null) value.reconcileActiveOnline(gameId, restore);
    }
    public static void authorizeOfficialHttp(String authorization) {
        OfficialRuntimeSession value=official;
        if (value == null) throw new OfficialException("NOT_SUPPORTED");
        value.authorizeHttp(authorization);
    }
    public static OfficialTokenApi.Reply dispatchOfficialHttp(String method,String path,JsonObject request,String authorization) {
        OfficialRuntimeSession value=official;
        if (value == null) throw new OfficialException("NOT_SUPPORTED");
        return value.dispatch(method,path,request,authorization);
    }
    public static boolean legacyActive() { return QQBindConfig.isLegacyBindingEnabled() && legacy != null; }
    public static String status() {
        OfficialRuntimeSession value = official;
        return value != null && value.active() ? status + ":whitelist_" + value.whitelistStatus() : status;
    }
    /** Operator-visible progress without opening configs, identities or bindings. */
    public static String statusSummary() {
        OfficialRuntimeSession value = official;
        if (value == null) return legacyActive() ? "当前绑定平台：OneBot" : "绑定服务未就绪（" + status + "）";
        String whitelist = switch (value.whitelistStatus()) {
            case "ready" -> "已接通";
            case "initializing" -> "正在初始化";
            case "awaiting_confirmation" -> "正在确认保存结果";
            case "not_installed" -> "加载器尚未安装访问桥";
            default -> "暂停（" + value.whitelistStatus() + "）";
        };
        return "当前绑定平台：QQ 官方；中心镜像：" + (value.snapshotReady() ? "已就绪" : "未就绪")
                + "；原版白名单：" + whitelist
                + "；预占对账：" + (value.pendingReservationsFast() ? "存在待确认项" : "无本地未决预占")
                + (value.bindingReadyFast() ? "；运行条件已就绪，实际兼容须完成部署验收。" : "；绑定运行条件尚未齐备，入口保持关闭。");
    }
    public static boolean requestOfficialReconcile() {
        OfficialRuntimeSession value=official;
        if (value == null || !value.active()) return false;
        try { value.requestReconcileOnGame(); return true; }
        catch (OfficialException ignored) { return false; }
    }
    public static boolean officialAdmin(String action,String argument,String actor,java.util.function.BooleanSupplier permission,java.util.function.Consumer<String> reply) {
        OfficialRuntimeSession value=official;
        try { return value != null && value.nativeRequest(action,argument,actor,permission,reply); }
        catch (OfficialException ignored) { return false; }
    }
    public static synchronized boolean stop() {
        PlayerStateManager.clearOfficialLogins();
        if (official != null) {
            if (!official.stop(5000)) { status="official_stopping"; return false; }
            official=null;
        }
        if (legacy != null) { legacy.close(); legacy=null; }
        started=false; status="stopped"; return true;
    }
}
