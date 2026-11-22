package com.poso.qqbind.official;

import com.google.gson.*;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.BindingBootstrap;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.poso.qqbind.official.OfficialCenterTests.*;

/** Real config/bridge source, fake logging/legacy/game/driver dependencies.
 * The ONLY config read is created here under the explicit new scratch cwd.
 */
public final class QQBindConfigBridgeTests {
    static Path config;
    static void write(JsonObject value) throws Exception {
        Files.createDirectories(config.getParent()); Files.writeString(config,value.toString());
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Explicit scratch root, scenario and synthetic fixture required");
        Path cwd=Path.of("").toAbsolutePath().normalize(),expected=Path.of(args[0]).toAbsolutePath().normalize();
        check(cwd.equals(expected) && cwd.getFileName().toString().startsWith("official-java-") && cwd.getParent().getFileName().toString().equals(".scratch"));
        config=cwd.resolve("config/qqbind/qqbind-config.json"); passed=0;
        if (args[1].equals("onebot")) {
            test("legacy_document_keeps_lazy_onebot_start_and_old_ids",() -> {
                JsonObject json=new JsonObject(); json.addProperty("apiToken","SYNTHETIC_LEGACY_TOKEN"); json.addProperty("serverId","old-short-id"); json.addProperty("storageMode","local"); write(json);
                QQBindConfig.load(); check(QQBindConfig.getLoadError() == null && QQBindConfig.isLegacyBindingEnabled());
                AtomicInteger loads=new AtomicInteger(); check(BindingBootstrap.start(() -> loads::incrementAndGet) != null);
                check(loads.get() == 1 && BindingBootstrap.legacyActive() && QQBindConfig.SERVER_ID.equals("old-short-id"));
            });
            test("v2_http_in_onebot_does_not_fall_back_to_old_manager",() -> {
                var exchange=new OfficialHttpTests.Exchange("POST",OfficialHttpHandler.PREFIX+"/sync","SYNTHETIC_PRIVATE_BODY"); exchange.forbidRead=true;
                OfficialHttpTests.run(new OfficialHttpHandler(BindingBootstrap::authorizeOfficialHttp,BindingBootstrap::dispatchOfficialHttp),exchange,"NOT_SUPPORTED",400);
                check(com.mysql.cj.jdbc.Driver.created == 0 && com.poso.qqbind.core.BindingManager.created == 1);
            });
            test("invalid_scalar_does_not_partially_replace_legacy_credentials",() -> {
                JsonObject bad=new JsonObject(); bad.addProperty("apiToken","SYNTHETIC_REPLACEMENT_TOKEN"); bad.addProperty("httpPort","SYNTHETIC_PRIVATE_BAD_INTEGER"); write(bad);
                QQBindConfig.reload(); check(QQBindConfig.getLoadError() != null && QQBindConfig.API_TOKEN.equals("SYNTHETIC_LEGACY_TOKEN"));
                check(org.slf4j.LoggerFactory.messages.stream().noneMatch(s -> s.contains("SYNTHETIC_PRIVATE")));
            });
            test("live_platform_switch_is_rejected_and_old_target_stays_selected",() -> {
                write(OfficialRuntimeTests.config()); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() != null && QQBindConfig.isLegacyBindingEnabled() && QQBindConfig.API_TOKEN.equals("SYNTHETIC_LEGACY_TOKEN"));
                check(BindingBootstrap.stop());
            });
        } else if (args[1].equals("official")) {
            test("official_bridge_never_constructs_old_storage_or_opens_driver",() -> {
                JsonObject document=OfficialRuntimeTests.config(); write(document); QQBindConfig.load();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.isOfficialBindingSelected() && !QQBindConfig.isLegacyBindingEnabled());
                var settings=OfficialRuntimeSettings.parse(document); try (var state=new OfficialModState(cwd.resolve(settings.stateDirectory()),settings.scope(),true)) { }
                check(BindingBootstrap.start(() -> { throw new AssertionError("Legacy storage supplier must stay untouched"); }) == null);
                check(BindingBootstrap.status().equals("official_core_only") && com.mysql.cj.jdbc.Driver.created == 0 && com.poso.qqbind.core.BindingManager.created == 0);
            });
            test("v2_http_uses_native_bootstrap_and_real_session_without_driver",() -> {
                var handler=new OfficialHttpHandler(BindingBootstrap::authorizeOfficialHttp,BindingBootstrap::dispatchOfficialHttp);
                var denied=new OfficialHttpTests.Exchange("POST",OfficialHttpHandler.PREFIX+"/sync","SYNTHETIC_PRIVATE_BODY"); denied.forbidRead=true; denied.request.set("Authorization","Bearer SYNTHETIC_WRONG_TOKEN");
                OfficialHttpTests.run(handler,denied,"UNAUTHORIZED",401);
                OfficialHttpTests.run(handler,OfficialHttpTests.get("/capabilities"),"NOT_SUPPORTED",400);
                JsonObject body=S.wire(); body.addProperty("token_code","123456");
                OfficialHttpTests.run(handler,OfficialHttpTests.post("/token/validate",body.toString()),"TOKEN_INVALID",400);
                check(com.mysql.cj.jdbc.Driver.created == 0 && com.poso.qqbind.core.BindingManager.created == 0);
            });
            test("old_whitelist_mutation_does_not_touch_synthetic_config_in_official_mode",() -> {
                String before=Files.readString(config); check(!QQBindConfig.setWhitelistCheck(false)); check(Files.readString(config).equals(before));
            });
            test("official_reload_cannot_rotate_source_and_never_logs_bad_parser_excerpt",() -> {
                JsonObject changed=OfficialRuntimeTests.config(); changed.addProperty("apiToken","SYNTHETIC_ROTATED_TOKEN"); write(changed); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() != null && QQBindConfig.API_TOKEN.equals(OfficialRuntimeTests.TOKEN));
                Files.writeString(config,"{\"apiToken\":SYNTHETIC_PRIVATE_PARSE_VALUE}"); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() != null && org.slf4j.LoggerFactory.messages.stream().noneMatch(s -> s.contains("SYNTHETIC_PRIVATE")));
            });
            test("missing_config_after_activation_is_not_regenerated_from_private_runtime_values",() -> {
                Files.delete(config); QQBindConfig.reload(); check(QQBindConfig.getLoadError() != null && !Files.exists(config));
                check(QQBindConfig.isOfficialBindingSelected() && !QQBindConfig.isLegacyBindingEnabled()); check(BindingBootstrap.stop());
            });
            test("stopped_native_bootstrap_rejects_http_before_reading_body",() -> {
                var exchange=new OfficialHttpTests.Exchange("POST",OfficialHttpHandler.PREFIX+"/sync","SYNTHETIC_PRIVATE_BODY"); exchange.forbidRead=true;
                OfficialHttpTests.run(new OfficialHttpHandler(BindingBootstrap::authorizeOfficialHttp,BindingBootstrap::dispatchOfficialHttp),exchange,"NOT_SUPPORTED",400);
                check(com.mysql.cj.jdbc.Driver.created == 0);
            });
        } else if (args[1].equals("missing")) {
            test("cold_missing_config_after_official_selection_cannot_create_onebot_defaults",() -> {
                check(!Files.exists(config) && Files.exists(config.resolveSibling("official-selection.marker")));
                QQBindConfig.load(); check(QQBindConfig.getLoadError() != null && !QQBindConfig.isLegacyBindingEnabled() && !Files.exists(config));
                check(BindingBootstrap.start(() -> { throw new AssertionError("Missing official config must not initialize old storage"); }) == null);
                check(BindingBootstrap.stop());
            });
        } else if (args[1].equals("game")) {
            JsonObject shared = OfficialModState.parse(Files.readString(Path.of(args[2]))).getAsJsonObject();
            check(shared.get("synthetic").getAsBoolean());
            OfficialCenterTests.fixture = shared;
            com.mysql.cj.jdbc.Driver.delegate = new OfficialSourceTests.SnapshotDb().driver();
            JsonObject document = OfficialRuntimeTests.config();
            write(document);
            QQBindConfig.load();
            check(QQBindConfig.getLoadError() == null);
            var settings = OfficialRuntimeSettings.parse(document);
            try (var state = new OfficialModState(cwd.resolve(settings.stateDirectory()), settings.scope(), true)) { }
            check(BindingBootstrap.start(() -> { throw new AssertionError("Unexpected legacy storage"); }) == null);
            check(BindingBootstrap.status().equals("official_core_only"));
            OfficialGameHostTests.run();
            check(com.mysql.cj.jdbc.Driver.created > 0);
        } else throw new IllegalArgumentException("Known synthetic scenario required");
        System.out.println("Config bridge " + args[1] + ": " + passed + " tests passed (test doubles; no Minecraft/real config)");
    }
}
