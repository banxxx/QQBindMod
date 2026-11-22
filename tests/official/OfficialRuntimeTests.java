package com.poso.qqbind.official;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.poso.qqbind.official.OfficialCenterTests.*;

/** Synthetic documents/new temp scopes/actual lease locks, no runtime config. */
public final class OfficialRuntimeTests {
    public static final String TOKEN="SYNTHETIC_OFFICIAL_RUNTIME_TOKEN";
    public static JsonObject config() {
        JsonObject value=new JsonObject(); value.addProperty("bindingPlatform","qq_official"); value.addProperty("environmentId",S.environmentId());
        value.addProperty("bindingGroupId",S.groupId()); value.addProperty("serverId",S.serverId()); value.addProperty("storageMode","hybrid");
        value.addProperty("apiToken",TOKEN); value.addProperty("enableWhitelistCheck",true); JsonObject official=new JsonObject(),db=new JsonObject();
        official.addProperty("configurationRevision","1"); official.addProperty("createFreshState",false); official.addProperty("allowCenterSync",true);
        db.addProperty("host","db.example.invalid"); db.addProperty("port",4000); db.addProperty("database","synthetic_official");
        db.addProperty("user","SYNTHETIC_DB_USER"); db.addProperty("password","SYNTHETIC_NOT_A_REAL_PASSWORD"); official.add("database",db); value.add("official",official); return value;
    }
    static OfficialRuntimeSettings selected(OfficialRuntimeRegistry registry,JsonObject document) {
        registry.apply(OfficialRuntimeSettings.parse(document),() -> {}); return registry.pin();
    }
    static Path root() throws Exception {
        Path root=Files.createTempDirectory("official-runtime-synthetic-");
        try (var state=new OfficialModState(root.resolve(OfficialRuntimeSettings.parse(config()).stateDirectory()),S,true)) { }
        return root;
    }
    static java.sql.Driver forbiddenDriver() { throw new AssertionError("No driver/network expected"); }
    static JsonObject request() { JsonObject value=S.wire(); value.addProperty("operation_id",OP); value.add("effects",operation("committed").getAsJsonArray("effects")); return value; }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Explicit synthetic fixture required");
        fixture=OfficialModState.parse(Files.readString(Path.of(args[0]))).getAsJsonObject(); check(fixture.get("synthetic").getAsBoolean()); passed=0;
        test("legacy_default_and_unknown_or_ambiguous_platform_are_distinct",() -> {
            check(!OfficialRuntimeSettings.parse(new JsonObject()).official());
            JsonObject unknown=config(); unknown.addProperty("bindingPlatform","qq-official"); expect("INVALID_INPUT",() -> OfficialRuntimeSettings.parse(unknown));
            JsonObject ambiguous=config(); ambiguous.remove("bindingPlatform"); expect("INVALID_INPUT",() -> OfficialRuntimeSettings.parse(ambiguous));
        });
        test("official_scope_token_database_and_boolean_types_are_strict",() -> {
            for (String key : List.of("serverId","bindingGroupId","apiToken","boolean","host","port")) {
                JsonObject value=config();
                switch(key) {
                    case "serverId" -> value.addProperty(key,"old-short-id");
                    case "bindingGroupId" -> value.addProperty(key," SYNTHETIC_GROUP_A");
                    case "apiToken" -> value.addProperty(key,"change-me-to-a-random-token");
                    case "boolean" -> value.getAsJsonObject("official").addProperty("allowCenterSync","true");
                    case "host" -> value.getAsJsonObject("official").getAsJsonObject("database").addProperty("host","a.invalid,b.invalid");
                    case "port" -> value.getAsJsonObject("official").getAsJsonObject("database").addProperty("port","4000");
                }
                expect("INVALID_INPUT",() -> OfficialRuntimeSettings.parse(value));
            }
        });
        test("config_document_rejects_duplicate_keys_and_oversize_without_excerpts",() -> {
            expect("INVALID_INPUT",() -> OfficialRuntimeSettings.document("{\"bindingPlatform\":\"onebot\",\"bindingPlatform\":\"qq_official\"}"));
            expect("INVALID_INPUT",() -> OfficialRuntimeSettings.document(" ".repeat(1024*1024+1)));
            try { OfficialRuntimeSettings.document("{\"apiToken\":SYNTHETIC_PRIVATE}"); } catch (OfficialException e) { check(!e.getMessage().contains("SYNTHETIC_PRIVATE")); }
        });
        test("settings_are_redacted_and_return_only_defensive_credentials",() -> {
            var settings=OfficialRuntimeSettings.parse(config()); Properties copy=settings.databaseProperties(); copy.setProperty("password","changed");
            check(!settings.databaseProperties().getProperty("password").equals("changed")); check(!settings.toString().contains(TOKEN));
            check(settings.authenticates("Bearer " + TOKEN) && !settings.authenticates("Bearer wrong"));
            check(settings.stateDirectory().startsWith(Path.of("qqbind","official",S.environmentId(),S.serverId())));
            check(settings.stateDirectory().getFileName().toString().matches("[0-9a-f]{64}"));
            JsonObject other=config(); other.addProperty("bindingGroupId","SYNTHETIC_OTHER_GROUP"); check(!settings.stateDirectory().equals(OfficialRuntimeSettings.parse(other).stateDirectory()));
        });
        test("failed_cold_pin_cannot_later_silently_fall_back_to_onebot",() -> {
            var registry=new OfficialRuntimeRegistry(); check(!registry.legacyAllowed()); expect("NOT_SUPPORTED",registry::pin);
            expect("RESTART_REQUIRED",() -> registry.apply(OfficialRuntimeSettings.parse(new JsonObject()),() -> { throw new AssertionError(); }));
            check(!registry.canCreateDefault());
        });
        test("onebot_hot_config_remains_supported_but_platform_switch_is_rejected",() -> {
            var registry=new OfficialRuntimeRegistry(); selected(registry,new JsonObject()); registry.apply(OfficialRuntimeSettings.parse(new JsonObject()),() -> {});
            expect("RESTART_REQUIRED",() -> registry.apply(OfficialRuntimeSettings.parse(config()),() -> { throw new AssertionError(); })); check(registry.legacyAllowed());
        });
        test("pinned_official_identity_credentials_mode_or_revision_require_restart",() -> {
            for (String key : List.of("environmentId","bindingGroupId","serverId","apiToken","storageMode","configurationRevision","createFreshState","allowCenterSync","password")) {
                var registry=new OfficialRuntimeRegistry(); var original=selected(registry,config()); JsonObject changed=config();
                switch(key) {
                    case "environmentId","serverId" -> changed.addProperty(key,"22222222-2222-4222-8222-222222222222");
                    case "bindingGroupId" -> changed.addProperty(key,"SYNTHETIC_OTHER_GROUP");
                    case "apiToken" -> changed.addProperty(key,"SYNTHETIC_ROTATED_RUNTIME_TOKEN");
                    case "storageMode" -> changed.addProperty(key,"remote");
                    case "configurationRevision" -> changed.getAsJsonObject("official").addProperty(key,"2");
                    case "password" -> changed.getAsJsonObject("official").getAsJsonObject("database").addProperty(key,"SYNTHETIC_ROTATED_PASSWORD");
                    default -> changed.getAsJsonObject("official").addProperty(key,key.equals("createFreshState"));
                }
                expect("RESTART_REQUIRED",() -> registry.apply(OfficialRuntimeSettings.parse(changed),() -> { throw new AssertionError(); })); check(registry.pin() == original);
            }
        });
        test("official_old_http_gate_keeps_only_approved_get_queries",() -> {
            var registry=new OfficialRuntimeRegistry(); selected(registry,config());
            for (String path : List.of("/api/status","/api/tps","/api/stats","/api/stats/all","/api/stats/Player_A")) check(registry.allowsLegacyHttp("GET",path) && !registry.allowsLegacyHttp("POST",path));
            for (String path : List.of("/api/bind","/api/unbind","/api/check","/api/validate_token","/api/sync_binding","/api/cache/invalidate","/api/console","/api/broadcast","/api/whitelist_check","/api/stats/../bind")) check(!registry.allowsLegacyHttp("GET",path) && !registry.allowsLegacyHttp("POST",path));
        });
        test("session_start_is_lazy_and_never_imports_legacy_binding_file",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=selected(registry,config()); Path root=root(),old=root.resolve("qqbind/bindings.json");
            Files.createDirectories(old.getParent()); Files.writeString(old,"SYNTHETIC_OLD_BINDING_MARKER");
            try (var session=new OfficialRuntimeSession(registry,settings,root,OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                check(session.active() && !session.isBoundFast("Player_A")); check(Files.exists(root.resolve(settings.stateDirectory()).resolve("official-state.json")));
                check(Files.readString(old).equals("SYNTHETIC_OLD_BINDING_MARKER"));
            }
        });
        test("fresh_flag_does_not_recreate_a_lost_primary_in_existing_scope",() -> {
            JsonObject fresh=config(); fresh.getAsJsonObject("official").addProperty("createFreshState",true);
            var registry=new OfficialRuntimeRegistry(); var settings=selected(registry,fresh); Path root=Files.createTempDirectory("official-runtime-fresh-synthetic-");
            try (var init=new OfficialRuntimeSession(registry,settings,root,OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                check(!init.active() && !init.isBoundFast("Player_A"));
                expect("NOT_SUPPORTED",() -> init.dispatch("POST",OfficialMirrorAuthority.SYNC_PATH,request(),"Bearer " + TOKEN));
            }
            Path primary=root.resolve(settings.stateDirectory()).resolve("official-state.json"); Files.delete(primary);
            expect("STORAGE_ERROR",() -> new OfficialRuntimeSession(registry,settings,root,OfficialRuntimeTests::forbiddenDriver,() -> false)); check(!Files.exists(primary));
        });
        test("missing_state_without_explicit_fresh_or_unsupported_mode_has_no_fallback",() -> {
            JsonObject json=config(); json.getAsJsonObject("official").addProperty("createFreshState",false);
            var registry=new OfficialRuntimeRegistry(); var settings=selected(registry,json); Path root=Files.createTempDirectory("official-runtime-missing-synthetic-");
            expect("STORAGE_ERROR",() -> new OfficialRuntimeSession(registry,settings,root,OfficialRuntimeTests::forbiddenDriver,() -> false)); check(!Files.exists(root.resolve(settings.stateDirectory())));
            var remote=new OfficialRuntimeRegistry(); json=config(); json.addProperty("storageMode","remote"); var unsupported=selected(remote,json); Path other=Files.createTempDirectory("official-runtime-remote-synthetic-");
            // remote 已接通中心库+本地持久镜像；没有显式 fresh 时缺主文件只能报 STORAGE_ERROR，
            // 不能悄悄新建状态，更不能回退到旧 OneBot 存储。
            expect("STORAGE_ERROR",() -> new OfficialRuntimeSession(remote,unsupported,other,OfficialRuntimeTests::forbiddenDriver,() -> false)); check(!Files.exists(other.resolve(unsupported.stateDirectory())));
        });
        test("real_configuration_lease_authorizes_sync_and_fast_check_without_driver",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=selected(registry,config());
            try (var session=new OfficialRuntimeSession(registry,settings,root(),OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                expect("UNAUTHORIZED",() -> session.dispatch("GET","/api/v2/binding/capabilities",S.wire(),"Bearer wrong"));
                var result=session.dispatch("POST",OfficialMirrorAuthority.SYNC_PATH,request(),"Bearer " + TOKEN);
                check(result.body().get("success").getAsBoolean() && session.isBoundFast("PLAYER_A"));
                check(session.dispatch("GET","/api/v2/binding/capabilities",S.wire(),"Bearer " + TOKEN).body().get("code").getAsString().equals("NOT_SUPPORTED"));
            }
        });
        test("official_live_whitelist_setting_is_scoped_typed_and_persisted",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=selected(registry,config());
            try (var session=new OfficialRuntimeSession(registry,settings,root(),OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                AtomicReference<Boolean> applied=new AtomicReference<>();
                session.whitelistToggle(enabled -> { applied.set(enabled); return new int[]{3,1}; });
                JsonObject body=S.wire(); body.addProperty("enabled",false);
                var reply=session.dispatch("POST","/api/v2/binding/settings/whitelist",body,"Bearer " + TOKEN);
                check(reply.status() == 200 && reply.body().get("success").getAsBoolean());
                JsonObject data=reply.body().getAsJsonObject("data");
                check(!data.get("enabled").getAsBoolean() && data.get("persisted").getAsBoolean());
                check(data.get("online_players").getAsInt() == 3 && data.get("released").getAsInt() == 1);
                check(Boolean.FALSE.equals(applied.get()) && !settings.whitelistCheck());
                JsonObject saved=OfficialModState.parse(Files.readString(Path.of("config/qqbind/qqbind-config.json"))).getAsJsonObject();
                check(!saved.get("enableWhitelistCheck").getAsBoolean());
                JsonObject wrong=S.wire(); wrong.addProperty("group_id","SYNTHETIC_OTHER_GROUP"); wrong.addProperty("enabled",true);
                expect("SCOPE_MISMATCH",() -> session.dispatch("POST","/api/v2/binding/settings/whitelist",wrong,"Bearer " + TOKEN));
                JsonObject extra=S.wire(); extra.addProperty("enabled",true); extra.addProperty("operation_id",OP);
                expect("INVALID_INPUT",() -> session.dispatch("POST","/api/v2/binding/settings/whitelist",extra,"Bearer " + TOKEN));
                JsonObject text=S.wire(); text.addProperty("enabled","true");
                expect("INVALID_INPUT",() -> session.dispatch("POST","/api/v2/binding/settings/whitelist",text,"Bearer " + TOKEN));
                expect("UNAUTHORIZED",() -> session.dispatch("POST","/api/v2/binding/settings/whitelist",body,"Bearer wrong"));
                check(!settings.whitelistCheck() && Boolean.FALSE.equals(applied.get()));
            }
        });
        test("snapshot_uses_actual_configuration_lease_and_explicit_fake_driver",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=selected(registry,config()); var db=new OfficialSourceTests.SnapshotDb(); AtomicInteger driver=new AtomicInteger();
            try (var session=new OfficialRuntimeSession(registry,settings,root(),() -> { driver.incrementAndGet(); return db.driver(); },() -> false)) {
                check(driver.get() == 0); session.refreshSnapshotOnIo(); check(driver.get() == 1 && session.isBoundFast("Player_B") && !session.isBoundFast("Player_A"));
            }
        });
        test("main_thread_io_is_rejected_while_fast_mirror_reads_remain_available",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=selected(registry,config()); AtomicBoolean main=new AtomicBoolean();
            try (var session=new OfficialRuntimeSession(registry,settings,root(),OfficialRuntimeTests::forbiddenDriver,main::get)) {
                session.dispatch("POST",OfficialMirrorAuthority.SYNC_PATH,request(),"Bearer " + TOKEN); main.set(true);
                expect("NOT_SUPPORTED",session::refreshSnapshotOnIo); expect("NOT_SUPPORTED",() -> session.dispatch("GET","/api/v2/binding/capabilities",S.wire(),"Bearer " + TOKEN));
                check(session.isBoundFast("Player_A"));
            }
        });
        test("reload_does_not_wait_on_a_reader_and_stop_timeout_prevents_replacement",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=selected(registry,config()); Path root=root();
            var session=new OfficialRuntimeSession(registry,settings,root,OfficialRuntimeTests::forbiddenDriver,() -> false);
            ExecutorService pool=Executors.newSingleThreadExecutor(); CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
            try {
                Future<?> reader=pool.submit(() -> { try (var lease=registry.acquire(session,S)) { held.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } } });
                check(held.await(2,TimeUnit.SECONDS)); expect("CONFLICT",() -> registry.apply(OfficialRuntimeSettings.parse(config()),() -> {}));
                check(!session.stop(0) && !session.active());
                expect("CONFLICT",() -> new OfficialRuntimeSession(registry,settings,root,OfficialRuntimeTests::forbiddenDriver,() -> false));
                release.countDown(); reader.get(2,TimeUnit.SECONDS); check(session.stop(1000));
                try (var next=new OfficialRuntimeSession(registry,settings,root,OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                    check(next.active()); expect("NOT_SUPPORTED",() -> session.dispatch("GET","/api/v2/binding/capabilities",S.wire(),"Bearer " + TOKEN));
                }
            } finally { release.countDown(); pool.shutdownNow(); session.close(); }
        });
        test("fast_binding_read_does_not_wait_for_configuration_writer",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=selected(registry,config());
            try (var session=new OfficialRuntimeSession(registry,settings,root(),OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                session.dispatch("POST",OfficialMirrorAuthority.SYNC_PATH,request(),"Bearer " + TOKEN);
                ExecutorService pool=Executors.newSingleThreadExecutor(); CountDownLatch publishing=new CountDownLatch(1),release=new CountDownLatch(1);
                try {
                    Future<?> writer=pool.submit(() -> registry.apply(OfficialRuntimeSettings.parse(config()),() -> { publishing.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }));
                    check(publishing.await(2,TimeUnit.SECONDS)); check(session.isBoundFast("Player_A")); release.countDown(); writer.get(2,TimeUnit.SECONDS);
                } finally { release.countDown(); pool.shutdownNow(); }
            }
        });
        test("center_outage_after_restart_keeps_mirror_login_and_local_online_effects_available",() -> {
            var registry=new OfficialRuntimeRegistry(); var settings=selected(registry,config()); Path root=root();
            try (var seed=new OfficialRuntimeSession(registry,settings,root,OfficialRuntimeTests::forbiddenDriver,() -> false)) {
                check(seed.dispatch("POST",OfficialMirrorAuthority.SYNC_PATH,request(),"Bearer " + TOKEN).body().get("success").getAsBoolean());
            }
            AtomicReference<Thread> game=new AtomicReference<>(); AtomicInteger attempts=new AtomicInteger(),online=new AtomicInteger();
            try (var session=new OfficialRuntimeSession(registry,settings,root,() -> {
                attempts.incrementAndGet(); throw new OfficialException("DB_UNAVAILABLE");
            },() -> Thread.currentThread() == game.get())) {
                check(!session.snapshotReady() && session.isBoundFast("Player_A"));
                var login=session.loginCodes().login(new UUID(2,3),"Player_X");
                check(!session.loginCodes().request(login));
                game.set(Thread.currentThread());
                var adapter=new OfficialMirrorService.GameEffects() {
                    public void whitelist(String name,boolean active) { throw new AssertionError("Whitelist is not connected"); }
                    public void online(String name,boolean active) {
                        check(Thread.currentThread() == game.get()); online.incrementAndGet();
                    }
                };
                for (int i=0;i<300 && online.get()==0;i++) { session.tick(adapter); Thread.sleep(10); }
                check(attempts.get()>0 && online.get()>0 && !session.snapshotReady() && session.isBoundFast("Player_A"));
            }
        });
        System.out.println("Official runtime: " + passed + " tests passed (synthetic config/leases/files only)");
    }
}
