package com.poso.qqbind.official;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.poso.qqbind.QQBindConfig;
import java.nio.file.*;
import java.sql.Driver;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.*;

/** Concrete hybrid core assembly with actual configuration/source leases.
 * The same on-demand I/O worker serves login codes and the local online-effect outbox.
 * Startup opens no database connection.
 */
public final class OfficialRuntimeSession implements AutoCloseable {
    private static final long[] EFFECT_RETRY_NANOS = {2_000_000_000L, 10_000_000_000L, 30_000_000_000L,
            60_000_000_000L, 300_000_000_000L};
    private final OfficialRuntimeRegistry registry;
    private final OfficialRuntimeSettings settings;
    private final BooleanSupplier onGameThread;
    private OfficialModState state;
    private OfficialMirrorAuthority source;
    private OfficialTokenService tokens;
    private OfficialLoginCodes loginCodes;
    private OfficialEffectQueue effects;
    private volatile OfficialWhitelistHost whitelistHost;
    private OfficialWhitelistFile whitelistFile;
    private OfficialWhitelistFile.Snapshot initialWhitelist;
    private volatile OfficialWhitelistQueue whitelist;
    private volatile String whitelistStatus = "not_installed";
    private Future<?> maintenance;
    private long nextMaintenance;
    private boolean maintenanceAttempted;
    private int maintenanceFailures;
    private volatile boolean snapshotReady;
    private volatile long nextSnapshotRefresh;
    private OfficialTokenApi api;
    private OfficialJdbcCenter.Connections reads;
    private OfficialJdbcCenter.Connections writes;
    private OfficialNativeAdmin nativeAdmin;
    private OfficialMirrorService mirror;
    private OfficialLocalAuthority local;
    private OfficialBindingApi bindings;
    private OfficialCommandService commands;
    private volatile OfficialCommandService.Executor commandExecutor=(action,payload,active) -> { throw new OfficialException("NOT_SUPPORTED"); };
    /** Game-thread revalidation hook supplied by BindingBootstrap; returns {online,released}. */
    private volatile Function<Boolean,int[]> whitelistToggle = enabled -> new int[]{0,0};
    public OfficialRuntimeSession(OfficialRuntimeRegistry registry,OfficialRuntimeSettings settings,Path root,
                                  Supplier<Driver> driver,BooleanSupplier onGameThread) {
        this(registry, settings, root, driver, onGameThread, notice -> { });
    }

    public OfficialRuntimeSession(OfficialRuntimeRegistry registry, OfficialRuntimeSettings settings, Path root,
                                  Supplier<Driver> driver, BooleanSupplier onGameThread,
                                  Consumer<OfficialLoginCodes.Notice> notifyGame) {
        this.registry=Objects.requireNonNull(registry); this.settings=Objects.requireNonNull(settings);
        Objects.requireNonNull(root); Objects.requireNonNull(driver); Objects.requireNonNull(onGameThread);
        Objects.requireNonNull(notifyGame);
        this.onGameThread=onGameThread;
        if (!settings.official()) throw new OfficialException("NOT_SUPPORTED");
        Path stateDirectory = settings.stateDirectory();
        registry.register(this,settings);
        try {
            Path base=root.toAbsolutePath().normalize(),directory=base.resolve(stateDirectory).normalize();
            if (!directory.startsWith(base)) throw new OfficialException("STORAGE_ERROR");
            Path next=base;
            if (Files.isSymbolicLink(base)) throw new OfficialException("STORAGE_ERROR");
            for (Path segment : base.relativize(directory)) { next=next.resolve(segment); if (Files.isSymbolicLink(next)) throw new OfficialException("STORAGE_ERROR"); }
            // A persistent fresh=true flag must NEVER recreate a missing primary
            // in an existing directory after an earlier successful installation.
            boolean create=settings.createFresh() && !Files.exists(directory,LinkOption.NOFOLLOW_LINKS);
            state=new OfficialModState(directory,settings.scope(),create,settings.mode().equals("local") ? "local" : "center");
            if (settings.mode().equals("local")) {
                mirror=new OfficialMirrorService(settings.scope(),state,null,onGameThread);
                local=new OfficialLocalAuthority(settings.scope(),mirror);
            } else {
                source=new OfficialMirrorAuthority(settings.scope(),state,scope -> registry.acquire(this,scope),onGameThread);
                mirror=source.mirror();
                reads=registry.connections(this,settings,driver,OfficialJdbcConnections.Access.READ_ONLY);
                writes=registry.connections(this,settings,driver,OfficialJdbcConnections.Access.READ_WRITE);
            }
            OfficialJdbcCenter center=local==null ? new OfficialJdbcCenter(settings.scope(),reads) : null;
            tokens=new OfficialTokenService(settings.scope(),state,id -> {
                try (var ignored=registry.acquire(this,settings.scope())) { return local==null ? center.find(id) : local.find(id); }
            });
            OfficialTokenApi.Authorizer authorizer=(scope,path) -> registry.active(this) && settings.scope().equals(scope);
            api=local==null ? OfficialTokenApi.withAuthority(tokens,authorizer,source) : new OfficialTokenApi(tokens,authorizer);
            loginCodes = new OfficialLoginCodes(tokens, this::mayIssueCodeFast, this::issueLoginCodeOnIo, notifyGame);
            effects = new OfficialEffectQueue(mirror);
            OfficialNativeAdmin.Repository repository=new OfficialNativeAdmin.Repository() {
                public com.google.gson.JsonArray rows() { return local==null ? source.snapshots(reads).load().rows() : local.rows(); }
                public JsonObject operation(String id) { return local==null ? center.readOperation(id) : local.operation(id); }
                public OfficialCenterMutations mutations(OfficialCenterMutations.PolicyGuard guard) {
                    return new OfficialCenterMutations(settings.scope(),local==null ? new OfficialJdbcTransactions(settings.scope(),writes) : local,guard);
                }
                public void committed(JsonObject operation) { refreshSnapshotOnIo(); }
                public com.google.gson.JsonArray operations() { if (local==null) throw new OfficialException("NOT_SUPPORTED"); return local.operations(); }
            };
            nativeAdmin = new OfficialNativeAdmin(settings.scope(),settings.revision(),repository);
            bindings=new OfficialBindingApi(settings,repository,tokens,this::bindingReadyOnIo);
            commands=new OfficialCommandService(settings.scope(),mirror,new OfficialCommandService.Executor() {
                public String execute(String action,String payload,BooleanSupplier active) { return commandExecutor.execute(action,payload,active); }
                public JsonObject takeOutput() { return commandExecutor.takeOutput(); }
            },this::active);
        } catch (Exception e) {
            registry.stop(this,this::release,1000);
            if (e instanceof OfficialException official) throw official;
            throw new OfficialException("STORAGE_ERROR");
        }
    }
    /** Cheap preflight before the HTTP host reads a potentially large body. */
    public void authorizeHttp(String authorization) {
        if (onGameThread.getAsBoolean()) throw new OfficialException("NOT_SUPPORTED");
        try (var ignored=registry.acquire(this,settings.scope())) {
            if (!settings.authenticates(authorization) && !settings.authenticatesConsole(authorization)) throw new OfficialException("UNAUTHORIZED");
        }
    }
    /** HTTP host passes the actual header, never an auth boolean. */
    public OfficialTokenApi.Reply dispatch(String method,String path,JsonObject request,String authorization) {
        if (onGameThread.getAsBoolean()) throw new OfficialException("NOT_SUPPORTED");
        // This route mutates the live settings snapshot and must not run under a
        // registry read lease (a write lock would deadlock the same thread).
        if (path.equals("/api/v2/binding/settings/whitelist")) return dispatchWhitelist(request,authorization);
        try (var ignored=registry.acquire(this,settings.scope())) {
            if (path.equals("/api/v2/binding/command")) {
                String action=OfficialModState.string(request,"action");
                if (!(action.equals("console") ? settings.authenticatesConsole(authorization) : settings.authenticates(authorization))) throw new OfficialException("UNAUTHORIZED");
                if (!settings.scope().wire().entrySet().stream().allMatch(e -> e.getValue().equals(request.get(e.getKey())))) throw new OfficialException("SCOPE_MISMATCH");
                JsonObject data=commands.dispatch(request);
                JsonObject body=new JsonObject(); body.addProperty("success",true); body.addProperty("code","OK"); body.addProperty("message","OK"); body.add("data",data); return new OfficialTokenApi.Reply(200,body);
            }
            if (OfficialMirrorAuthority.SYNC_PATH.equals(path)) {
                if (local!=null) throw new OfficialException("SCOPE_MISMATCH");
                return api.dispatchSync(method,path,request,authorization);
            }
            if (!settings.authenticates(authorization)) throw new OfficialException("UNAUTHORIZED");
            if (path.endsWith("/capabilities") || path.endsWith("/check") || path.endsWith("/bind") || path.endsWith("/unbind") || path.endsWith("/operations") || path.contains("/operations/")) {
                OfficialTokenApi.Reply reply=bindings.dispatch(method,path,request);
                if (local==null && method.equals("GET") && path.startsWith("/api/v2/binding/operations/")
                        && java.util.Set.of("DB_UNAVAILABLE","RESULT_UNKNOWN","SCOPE_MISMATCH").contains(reply.body().get("code").getAsString())) {
                    // The token route can still report this server's durable
                    // receipt when its center connection is unavailable. It
                    // never substitutes local state for a center decision.
                    OfficialTokenApi.Reply receipt=api.dispatch(method,path,request,true);
                    if (receipt.body().getAsJsonObject("data").has("reservation")) return receipt;
                }
                return reply;
            }
            return api.dispatch(method,path,request,true);
        }
    }
    /** Official-scoped live toggle; never touches legacy HTTP handlers. */
    private OfficialTokenApi.Reply dispatchWhitelist(JsonObject request,String authorization) {
        if (!active()) throw new OfficialException("NOT_SUPPORTED");
        if (request == null || !settings.authenticates(authorization)) throw new OfficialException("UNAUTHORIZED");
        JsonElement version=request.get("protocol_version");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
                || version.getAsInt() != 2) throw new OfficialException("NOT_SUPPORTED");
        if (!settings.scope().wire().entrySet().stream().allMatch(e -> e.getValue().equals(request.get(e.getKey()))))
            throw new OfficialException("SCOPE_MISMATCH");
        Set<String> expected=new HashSet<>(settings.scope().wire().keySet()); expected.add("enabled");
        if (!request.keySet().equals(expected)) throw new OfficialException("INVALID_INPUT");
        JsonElement enabled=request.get("enabled");
        if (enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean())
            throw new OfficialException("INVALID_INPUT");
        boolean value=enabled.getAsBoolean();
        settings.setWhitelistCheck(value);
        boolean persisted=QQBindConfig.applyOfficialWhitelistCheck(value);
        int online=0,released=0;
        try { int[] counts=whitelistToggle.apply(value); online=counts[0]; released=counts[1]; }
        catch (Exception ignored) {
            // The switch itself is already durable; online snapshot failure must not
            // turn a successful configuration change into a client-side retry.
        }
        JsonObject data=new JsonObject(); data.addProperty("protocol_version",2);
        data.add("scope",settings.scope().wire()); data.addProperty("enabled",value);
        data.addProperty("persisted",persisted); data.addProperty("online_players",online);
        data.addProperty("released",released);
        JsonObject body=new JsonObject(); body.addProperty("success",true); body.addProperty("code","OK");
        body.addProperty("message","OK"); body.add("data",data);
        return new OfficialTokenApi.Reply(200,body);
    }
    public void refreshSnapshotOnIo() {
        if (onGameThread.getAsBoolean()) throw new OfficialException("NOT_SUPPORTED");
        try (var ignored=registry.acquire(this,settings.scope())) {
            if (local==null) source.merge(source.snapshots(reads).load());
            else local.acknowledgeOnIo();
            snapshotReady = true;
            nextSnapshotRefresh=System.nanoTime()+60_000_000_000L;
        }
    }
    private boolean mayIssueCodeFast(String gameId) {
        return active() && settings.whitelistCheck() && snapshotReady && mirror.mayIssueCodeFast(gameId);
    }
    private OfficialTokenService.Issued issueLoginCodeOnIo(OfficialLoginCodes.Login login) {
        if (onGameThread.getAsBoolean()) throw new OfficialException("NOT_SUPPORTED");
        try (var ignored = registry.acquire(this, settings.scope())) {
            if (!mayIssueCodeFast(login.gameId)) throw new OfficialException("NOT_SUPPORTED");
            return tokens.issue(login);
        }
    }
    public OfficialLoginCodes loginCodes() { return loginCodes; }
    public boolean snapshotReady() { return active() && snapshotReady; }
    public boolean pendingReservationsFast() { return active() && tokens.hasPendingReservationsFast(); }
    /** Operator-requested read/terminal reconciliation, never a binding mutation. */
    public void requestReconcileOnGame() {
        if (!onGameThread.getAsBoolean() || !active()) throw new OfficialException("NOT_SUPPORTED");
        snapshotReady=false; maintenanceAttempted=false;
    }
    /** Called by a loader only after its real PlayerList is available. No I/O. */
    public void installWhitelistOnGame(OfficialWhitelistHost host) {
        if (!onGameThread.getAsBoolean() || !active()) throw new OfficialException("NOT_SUPPORTED");
        if (whitelistHost != null) throw new OfficialException("CONFLICT");
        Objects.requireNonNull(host);
        OfficialWhitelistFile file = new OfficialWhitelistFile(host.filePath(), settings.scope(),
                () -> active() && host.activeFast(), onGameThread);
        try {
            host.attachOnGame(file);
            initialWhitelist = file.captureOnGame(host.snapshotOnGame());
            whitelistFile = file; whitelistHost = host; whitelistStatus = "initializing";
        } catch (Exception error) {
            host.close(); file.close();
            whitelistStatus = "blocked_" + (error instanceof OfficialException o ? o.code() : "NOT_SUPPORTED");
            throw new OfficialException("NOT_SUPPORTED");
        }
    }
    public String whitelistStatus() {
        if (whitelist != null && whitelistFile != null) {
            if (!whitelistHost.activeFast()) return "blocked_CONFLICT";
            if (whitelistFile.needsRecoveryFast()) return "awaiting_confirmation";
            if (!whitelistFile.readyFast()) return "blocked_STORAGE_ERROR";
        }
        return whitelistStatus;
    }
    /** Called once per native server tick; never waits for I/O or a Future. */
    public void tick(OfficialMirrorService.GameEffects gameEffects) {
        if (!onGameThread.getAsBoolean()) throw new OfficialException("CONFLICT");
        if (!active()) return;
        nativeAdmin.tick();
        effects.executeOnGame(gameEffects);
        if (whitelistHost != null) {
            try {
                whitelistHost.tickOnGame();
                OfficialWhitelistQueue queue = whitelist;
                if (queue != null) queue.advanceOnGame();
            } catch (RuntimeException error) {
                whitelistStatus = "blocked_" + (error instanceof OfficialException o ? o.code() : "NOT_SUPPORTED");
            }
        }
        OfficialWhitelistQueue queue = whitelist;
        boolean whitelistIo = whitelistHost != null && (queue == null || queue.needsIo());
        boolean refreshDue=local==null && System.nanoTime()-nextSnapshotRefresh>=0;
        if (snapshotReady && !refreshDue && !effects.needsIo() && !whitelistIo && !tokens.hasPendingReservationsFast()
                || maintenance != null && !maintenance.isDone()) return;
        long now = System.nanoTime();
        if (maintenanceAttempted && now - nextMaintenance < 0) return;
        maintenanceAttempted = true;
        nextMaintenance = now + EFFECT_RETRY_NANOS[maintenanceFailures];
        try {
            maintenance = loginCodes.submitMaintenance(this::processEffectsOnIo);
        } catch (RejectedExecutionException ignored) { /* Login requests own this bounded queue too. */ }
    }

    private void processEffectsOnIo() {
        try (var ignored = registry.acquire(this, settings.scope())) {
            boolean failed = false;
            if (!snapshotReady || local==null && System.nanoTime()-nextSnapshotRefresh>=0) {
                try { refreshSnapshotOnIo(); }
                catch (RuntimeException ignoredSnapshot) { failed = true; }
            }
            // A center outage must not stall effects from this scope's durable mirror.
            try { effects.captureOnIo(); } catch (RuntimeException ignoredOnline) { failed = true; }
            // Finalize already-terminal center decisions even if the plugin's
            // original finalize response was lost or either process restarted.
            // Unresolved operations stay reserved; this is never a user action.
            if (tokens.hasPendingReservationsFast()) {
                try { if (!tokens.recoverTerminalReservationsOnIo(4)) failed=true; }
                catch (RuntimeException ignoredReservation) { failed=true; }
            }
            if (whitelistHost != null) {
                try {
                    if (whitelist == null) {
                        // A missing existing whitelist is never silently recreated.
                        whitelistFile.initializeOnIo(initialWhitelist);
                        whitelist = new OfficialWhitelistQueue(settings.scope(), mirror, whitelistFile,
                                whitelistHost, whitelistHost, () -> active() && whitelistHost.activeFast(), onGameThread);
                        initialWhitelist = null;
                    }
                    if (whitelist.needsIo() && !whitelist.advanceOnIo()) failed = true;
                    whitelistStatus = whitelistFile.needsRecoveryFast() ? "awaiting_confirmation"
                            : whitelistFile.readyFast() ? "ready" : "blocked_STORAGE_ERROR";
                } catch (RuntimeException error) {
                    failed = true;
                    whitelistStatus = "blocked_" + (error instanceof OfficialException o ? o.code() : "STORAGE_ERROR");
                }
            }
            if (local!=null) try { local.acknowledgeOnIo(); } catch (RuntimeException ignoredLocalAck) { failed=true; }
            maintenanceFailures = failed ? Math.min(maintenanceFailures + 1, EFFECT_RETRY_NANOS.length - 1) : 0;
        } catch (RuntimeException ignored) {
            maintenanceFailures = Math.min(maintenanceFailures + 1, EFFECT_RETRY_NANOS.length - 1);
        }
    }
    /** Memory-only, no configuration lock or file/DB access on the game thread. */
    public boolean isBoundFast(String gameId) { return active() && mirror != null && mirror.isBoundFast(gameId); }
    public void commandExecutor(OfficialCommandService.Executor executor) { commandExecutor=Objects.requireNonNull(executor); }
    public void whitelistToggle(Function<Boolean,int[]> handler) { whitelistToggle=Objects.requireNonNull(handler); }
    public boolean bindingReadyFast() {
        return active() && snapshotReady && whitelist!=null && "ready".equals(whitelistStatus()) && (local!=null || settings.allowCenterSync());
    }
    private void bindingReadyOnIo() {
        if (!bindingReadyFast()) throw new OfficialException("NOT_SUPPORTED");
        if (local==null) new OfficialJdbcTransactions(settings.scope(),writes).transact(tx -> null);
    }
    public String restrictionMarker(String playerId, String mode) {
        return active() ? settings.restrictionMarker(playerId, mode) : null;
    }
    public boolean verifiesRestrictionMarker(String playerId, String mode, String marker) {
        return active() && settings.verifiesRestrictionMarker(playerId, mode, marker);
    }
    public void reconcileActiveOnline(String gameId, Runnable restore) {
        if (active()) mirror.reconcileActiveOnline(gameId, restore);
    }
    public boolean active() { return registry.active(this) && !settings.createFresh(); }
    public boolean nativeRequest(String action,String argument,String actor,BooleanSupplier permission,Consumer<String> reply) {
        if (!onGameThread.getAsBoolean() || !active()) return false;
        return nativeAdmin.enqueue(action,argument,actor,permission,reply,work -> loginCodes.submitMaintenance(() -> {
            try (var ignored=registry.acquire(this,settings.scope())) { work.run(); }
        }));
    }
    public boolean stop(long timeoutMillis) {
        if (whitelist != null) whitelist.close();
        if (effects != null) effects.close();
        if (loginCodes != null) loginCodes.close();
        return registry.stop(this, this::release, timeoutMillis);
    }
    private void release() {
        if (nativeAdmin != null) nativeAdmin.close();
        if (whitelist != null) whitelist.close();
        if (whitelistHost != null) whitelistHost.close();
        if (whitelistFile != null) whitelistFile.close();
        if (effects != null) effects.close();
        if (loginCodes != null) loginCodes.close();
        if (source != null) source.close();
        else if (mirror != null) mirror.close();
        if (state != null) state.close();
    }
    @Override public void close() { if (!stop(5000)) throw new OfficialException("RESULT_UNKNOWN"); }
    @Override public String toString() { return "OfficialRuntimeSession[redacted]"; }
}
