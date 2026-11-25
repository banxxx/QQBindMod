package com.poso.qqbind.official;

import com.google.gson.*;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.core.BindingBootstrap;
import com.poso.qqbind.utils.Jsonc;
import java.nio.file.*;
import java.util.List;
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
    static void writeRaw(String value) throws Exception {
        Files.createDirectories(config.getParent()); Files.writeString(config,value);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Explicit scratch root, scenario and synthetic fixture required");
        Path cwd=Path.of("").toAbsolutePath().normalize(),expected=Path.of(args[0]).toAbsolutePath().normalize();
        check(cwd.equals(expected) && cwd.getFileName().toString().startsWith("official-java-") && cwd.getParent().getFileName().toString().equals(".scratch"));
        config=cwd.resolve("config/qqbind/qqbind-config.jsonc"); passed=0;
        if (args[1].equals("onebot")) {
            test("legacy_document_keeps_lazy_onebot_start_and_old_ids",() -> {
                JsonObject json=new JsonObject(); json.addProperty("apiToken","SYNTHETIC_LEGACY_TOKEN"); json.addProperty("serverId","old-short-id"); json.addProperty("storageMode","local");
                // 旧版扁平配置可能残留历史字段；没有 configVersion/平台段时应继续兼容，而不是整份拒绝。
                json.addProperty("kickMessage","SYNTHETIC_LEGACY_UNUSED"); write(json);
                QQBindConfig.load(); check(QQBindConfig.getLoadError() == null && QQBindConfig.isLegacyBindingEnabled());
                AtomicInteger loads=new AtomicInteger(); check(BindingBootstrap.start(() -> loads::incrementAndGet) != null);
                check(loads.get() == 1 && BindingBootstrap.legacyActive() && QQBindConfig.SERVER_ID.equals("old-short-id"));
            });
            test("v2_http_in_onebot_does_not_fall_back_to_old_manager",() -> {
                var exchange=new OfficialHttpTests.Exchange("POST",OfficialHttpHandler.PREFIX+"/sync","SYNTHETIC_PRIVATE_BODY"); exchange.forbidRead=true;
                OfficialHttpTests.run(new OfficialHttpHandler(BindingBootstrap::authorizeOfficialHttp,BindingBootstrap::dispatchOfficialHttp),exchange,"NOT_SUPPORTED",400);
                check(com.mysql.cj.jdbc.Driver.created == 0 && com.poso.qqbind.core.BindingManager.created == 1);
            });
            test("jsonc_comments_trailing_commas_and_comment_preserving_toggle",() -> {
                writeRaw("// 顶部注释\n{\n  \"configVersion\": 2,\n  \"bindingPlatform\": \"onebot\",\n"
                        + "  \"apiToken\": \"SYNTHETIC_LEGACY_TOKEN\",\n  // 是否拦截\n  \"enableWhitelistCheck\": true,\n"
                        + "  /* 平台段 */\n  \"onebot\": {\n    \"serverId\": \"old-short-id\",\n    \"qqGroup\": \"123456789\",\n    \"storageMode\": \"local\",\n  },\n}\n");
                QQBindConfig.load();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.isLegacyBindingEnabled()
                        && QQBindConfig.API_TOKEN.equals("SYNTHETIC_LEGACY_TOKEN"));
                check(QQBindConfig.setWhitelistCheck(false));
                String saved=Files.readString(config);
                check(saved.contains("// 顶部注释") && saved.contains("/* 平台段 */")
                        && saved.contains("// 是否拦截")
                        && saved.contains("\"enableWhitelistCheck\": false"));
                QQBindConfig.reload();
                check(QQBindConfig.getLoadError() == null && !QQBindConfig.ENABLE_WHITELIST_CHECK);
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
                check(BindingBootstrap.status().startsWith("official_core_only") && com.mysql.cj.jdbc.Driver.created == 0 && com.poso.qqbind.core.BindingManager.created == 0);
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
            test("missing_config_after_activation_gets_only_a_secret_free_official_template",() -> {
                Files.delete(config); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() != null && Files.exists(config));
                check(QQBindConfig.isOfficialBindingSelected() && !QQBindConfig.isLegacyBindingEnabled());
                String generatedText=Files.readString(config);
                JsonObject generated=OfficialRuntimeSettings.document(generatedText);
                check(generated.get("bindingPlatform").getAsString().equals("qq_official") && generated.has("qq_official") && !generated.has("onebot"));
                check(generated.get("apiToken").getAsString().equals(QQBindConfig.DEFAULT_API_TOKEN));
                check(!generatedText.contains(OfficialRuntimeTests.TOKEN)
                        && !generatedText.contains("SYNTHETIC_DB_USER")
                        && !generatedText.contains("SYNTHETIC_NOT_A_REAL_PASSWORD"));
                check(BindingBootstrap.stop());
            });
            test("stopped_native_bootstrap_rejects_http_before_reading_body",() -> {
                var exchange=new OfficialHttpTests.Exchange("POST",OfficialHttpHandler.PREFIX+"/sync","SYNTHETIC_PRIVATE_BODY"); exchange.forbidRead=true;
                OfficialHttpTests.run(new OfficialHttpHandler(BindingBootstrap::authorizeOfficialHttp,BindingBootstrap::dispatchOfficialHttp),exchange,"NOT_SUPPORTED",400);
                check(com.mysql.cj.jdbc.Driver.created == 0);
            });
        } else if (args[1].equals("missing")) {
            test("cold_missing_config_after_official_selection_generates_only_an_official_recovery_template",() -> {
                Files.deleteIfExists(config);
                Path marker=config.resolveSibling("official-selection.marker");
                Files.createDirectories(config.getParent());
                Files.writeString(marker,"qq_official\n");
                check(!Files.exists(config) && Files.exists(marker));
                QQBindConfig.load();
                check(QQBindConfig.getLoadError() != null && !QQBindConfig.isLegacyBindingEnabled() && !QQBindConfig.isOfficialBindingSelected());
                check(Files.exists(config));
                String generatedText=Files.readString(config), beforeRetry=generatedText;
                JsonObject generated=OfficialRuntimeSettings.document(generatedText);
                check(generated.get("bindingPlatform").getAsString().equals("qq_official"));
                check(generated.get("configVersion").getAsInt() == QQBindConfig.CONFIG_VERSION);
                check(generated.has("qq_official") && !generated.has("onebot") && !generated.has("dataFilePath"));
                check(generated.has("consoleBannedCommands") && generated.has("consoleToken")
                        && generated.has("bindSuccessActionBar") && generatedText.contains("/*"));
                check(generated.get("apiToken").getAsString().equals(QQBindConfig.DEFAULT_API_TOKEN));
                JsonObject recovered=generated.getAsJsonObject("qq_official");
                check(recovered.get("environmentId").getAsString().startsWith("<RESTORE_"));
                check(!recovered.has("official") && recovered.has("configurationRevision") && recovered.has("groupHint"));
                QQBindConfig.load();
                check(QQBindConfig.getLoadError() != null && !QQBindConfig.isLegacyBindingEnabled() && Files.readString(config).equals(beforeRetry));
                check(BindingBootstrap.start(() -> { throw new AssertionError("Missing official config must not initialize old storage"); }) == null);
                check(BindingBootstrap.stop());
            });
        } else if (args[1].equals("fresh")) {
            test("fresh_missing_config_generates_a_valid_onebot_default",() -> {
                check(!Files.exists(config) && !Files.exists(config.resolveSibling("official-selection.marker")));
                QQBindConfig.load();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.isLegacyBindingEnabled() && !QQBindConfig.isOfficialBindingSelected());
                check(Files.exists(config) && !Files.exists(config.resolveSibling("official-selection.marker")));
                JsonObject generated=OfficialRuntimeSettings.document(Files.readString(config));
                check(generated.get("bindingPlatform").getAsString().equals("onebot"));
                check(generated.get("configVersion").getAsInt() == QQBindConfig.CONFIG_VERSION);
                check(generated.has("apiToken") && generated.has("onebot") && generated.has("qq_official"));
                check(!generated.has("serverId") && !generated.has("storageMode") && !generated.has("qqGroup"));
                JsonObject onebot=generated.getAsJsonObject("onebot");
                check(onebot.has("qqGroup") && onebot.has("groupHint") && onebot.has("serverId"));
                check(onebot.get("serverId").getAsString().equals("<SERVER_UUID>") && !onebot.has("dataFilePath"));
                check(onebot.get("storageMode").getAsString().equals("local") && onebot.has("jdbcUrl")
                        && onebot.has("dbUser") && onebot.has("dbPassword"));
                check(generated.has("consoleToken") && generated.has("consoleBannedCommands")
                        && generated.has("bindSuccessActionBar") && Files.readString(config).contains("/*"));
                JsonObject official=generated.getAsJsonObject("qq_official");
                check(official.has("environmentId") && official.has("groupHint")
                        && official.get("groupHint").getAsString().equals("123456789"));
                check(official.has("jdbcUrl") && official.has("dbUser") && official.has("dbPassword"));
                check(!official.has("dataDirectory") && official.has("cacheTtlSeconds")
                        && official.has("dbRetryIntervalSeconds") && official.has("dbMaxRetries"));
            });
            test("legacy_json_path_is_read_when_the_jsonc_file_is_absent",() -> {
                Files.delete(config);
                Path legacy=config.resolveSibling("qqbind-config.json");
                Files.writeString(legacy,"{\"apiToken\":\"SYNTHETIC_LEGACY_JSON_TOKEN\",\"serverId\":\"legacy-json-id\",\"storageMode\":\"local\",\"qqGroup\":\"123456789\"}");
                QQBindConfig.reload();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.isLegacyBindingEnabled()
                        && QQBindConfig.API_TOKEN.equals("SYNTHETIC_LEGACY_JSON_TOKEN")
                        && QQBindConfig.SERVER_ID.equals("legacy-json-id"));
                check(Files.exists(legacy) && !Files.exists(config));
                JsonObject jsonc=new JsonObject();
                jsonc.addProperty("apiToken","SYNTHETIC_JSONC_WINS");
                jsonc.addProperty("serverId","jsonc-wins");
                jsonc.addProperty("storageMode","local");
                jsonc.addProperty("qqGroup","123456789");
                write(jsonc); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.API_TOKEN.equals("SYNTHETIC_JSONC_WINS")
                        && QQBindConfig.getConfigFilePath().endsWith("qqbind-config.jsonc"));
                check(Files.exists(legacy) && Files.exists(config));
            });
        } else if (args[1].equals("schema")) {
            JsonObject flat=OfficialRuntimeTests.config();
            JsonObject document=new JsonObject();
            document.addProperty("configVersion",QQBindConfig.CONFIG_VERSION);
            document.addProperty("bindingPlatform","qq_official");
            document.addProperty("httpPort",25567);
            document.addProperty("httpBindHost","127.0.0.1");
            document.addProperty("apiToken",OfficialRuntimeTests.TOKEN);
            document.addProperty("consoleToken","");
            document.addProperty("consoleBannedCommands","");
            document.addProperty("enableWhitelistCheck",true);
            JsonObject officialSection=new JsonObject();
            for (String key : List.of("environmentId","bindingGroupId","serverId","storageMode")) {
                officialSection.add(key,flat.get(key));
            }
            officialSection.addProperty("groupHint","987654321");
            officialSection.addProperty("configurationRevision","1");
            officialSection.addProperty("createFreshState",false);
            officialSection.addProperty("allowCenterSync",true);
            officialSection.addProperty("jdbcUrl","jdbc:mysql://db.example.invalid:4000/synthetic_official");
            officialSection.addProperty("dbUser","SYNTHETIC_DB_USER");
            officialSection.addProperty("dbPassword","SYNTHETIC_NOT_A_REAL_PASSWORD");
            document.add("qq_official",officialSection);
            JsonObject onebotSection=new JsonObject();
            onebotSection.addProperty("serverId","synthetic-onebot-section");
            onebotSection.addProperty("dataFilePath","synthetic/onebot.json");
            onebotSection.addProperty("storageMode","local");
            onebotSection.addProperty("qqGroup","123456789");
            onebotSection.addProperty("cacheTtlSeconds",61);
            document.add("onebot",onebotSection);

            test("sectioned_official_uses_common_fields_and_only_the_selected_section",() -> {
                write(document); QQBindConfig.load();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.isOfficialBindingSelected() && !QQBindConfig.isLegacyBindingEnabled());
                check(QQBindConfig.API_TOKEN.equals(OfficialRuntimeTests.TOKEN) && QQBindConfig.HTTP_PORT == 25567
                        && QQBindConfig.HTTP_BIND_HOST.equals("127.0.0.1"));
                check(QQBindConfig.SERVER_ID.equals(S.serverId()) && QQBindConfig.STORAGE_MODE.equals("hybrid"));
                check(QQBindConfig.OFFICIAL_GROUP_HINT.equals("987654321"));
                check(QQBindConfig.DATA_FILE_PATH.equals("qqbind/bindings.json") && QQBindConfig.CACHE_TTL_SECONDS == 60);
                check(Files.exists(config.resolveSibling("official-selection.marker")));
            });

            test("official_local_keeps_database_fields_but_never_connects",() -> {
                JsonObject local=document.deepCopy();
                JsonObject section=local.getAsJsonObject("qq_official");
                section.addProperty("storageMode","local");
                section.addProperty("allowCenterSync",false);
                section.addProperty("dataDirectory","synthetic/official-state");
                section.addProperty("cacheTtlSeconds",30);
                section.addProperty("dbRetryIntervalSeconds",5);
                section.addProperty("dbMaxRetries",2);
                write(local); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.isOfficialBindingSelected());
                check(QQBindConfig.STORAGE_MODE.equals("local"));
                // 删除 groupHint 后官方展示回退到内置值，不沿用上一次的自定义群号。
                section.remove("groupHint"); write(local); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.OFFICIAL_GROUP_HINT.equals("当前官方群"));
            });

            test("sectioned_official_still_accepts_the_legacy_nested_shape",() -> {
                JsonObject legacy=document.deepCopy();
                JsonObject section=legacy.getAsJsonObject("qq_official");
                for (String key : List.of("configurationRevision","createFreshState","allowCenterSync","jdbcUrl","dbUser","dbPassword")) {
                    section.remove(key);
                }
                JsonObject runtime=new JsonObject();
                runtime.addProperty("configurationRevision","1");
                runtime.addProperty("createFreshState",false);
                runtime.addProperty("allowCenterSync",true);
                JsonObject db=new JsonObject();
                db.addProperty("host","db.example.invalid");
                db.addProperty("port",4000);
                db.addProperty("database","synthetic_official");
                db.addProperty("user","SYNTHETIC_DB_USER");
                db.addProperty("password","SYNTHETIC_NOT_A_REAL_PASSWORD");
                runtime.add("database",db);
                section.add("official",runtime);
                write(legacy); QQBindConfig.reload();
                check(QQBindConfig.getLoadError()==null && QQBindConfig.isOfficialBindingSelected());
            });

            test("invalid_sectioned_documents_fail_without_replacing_the_selected_target",() -> {
                String token=QQBindConfig.API_TOKEN, serverId=QQBindConfig.SERVER_ID;
                int port=QQBindConfig.HTTP_PORT;
                JsonObject noSelector=document.deepCopy(); noSelector.remove("bindingPlatform");
                JsonObject unknownSelector=document.deepCopy(); unknownSelector.addProperty("bindingPlatform","qq-official");
                JsonObject missingSelectedSection=document.deepCopy(); missingSelectedSection.remove("qq_official");
                JsonObject wrongSectionType=document.deepCopy(); wrongSectionType.add("qq_official",new JsonPrimitive("synthetic-broken-section"));
                JsonObject commonKeyInSection=document.deepCopy();
                commonKeyInSection.getAsJsonObject("qq_official").addProperty("apiToken","synthetic-section-token");
                JsonObject unknownKeyInSection=document.deepCopy();
                unknownKeyInSection.getAsJsonObject("qq_official").addProperty("syntheticTypo",true);
                for (JsonObject invalid : List.of(noSelector,unknownSelector,missingSelectedSection,wrongSectionType,commonKeyInSection,unknownKeyInSection)) {
                    write(invalid); QQBindConfig.reload();
                    check(QQBindConfig.getLoadError() != null && QQBindConfig.isOfficialBindingSelected() && !QQBindConfig.isLegacyBindingEnabled());
                    check(QQBindConfig.API_TOKEN.equals(token) && QQBindConfig.SERVER_ID.equals(serverId) && QQBindConfig.HTTP_PORT == port);
                }
            });

            test("selector_switches_to_onebot_section_and_ignores_inactive_section",() -> {
                JsonObject switched=document.deepCopy(); switched.addProperty("bindingPlatform","onebot");
                switched.add("qq_official",new JsonPrimitive("inactive placeholder is not validated"));
                switched.getAsJsonObject("onebot").addProperty("groupHint","987654321");
                write(switched); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.isLegacyBindingEnabled() && !QQBindConfig.isOfficialBindingSelected());
                check(QQBindConfig.API_TOKEN.equals(OfficialRuntimeTests.TOKEN) && QQBindConfig.HTTP_PORT == 25567);
                check(QQBindConfig.SERVER_ID.equals("synthetic-onebot-section") && QQBindConfig.DATA_FILE_PATH.equals("synthetic/onebot.json")
                        && QQBindConfig.STORAGE_MODE.equals("local") && QQBindConfig.CACHE_TTL_SECONDS == 61);
                check(QQBindConfig.ONEBOT_GROUP_HINT.equals("987654321"));
                check(QQBindConfig.formatMessage("x{groupHint}y","123456").equals("x987654321y"));
                check(Files.readString(config).contains("\"qq_official\""));
                // 删掉 groupHint 后必须回到 qqGroup，而不是沿用上一次的展示值。
                switched.getAsJsonObject("onebot").remove("groupHint");
                write(switched); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.ONEBOT_GROUP_HINT.isEmpty());
                check(QQBindConfig.formatMessage("x{groupHint}y","123456").equals("x123456789y"));
            });

            test("onebot_server_id_is_optional_and_generated_once_per_process",() -> {
                JsonObject omitted=document.deepCopy(); omitted.addProperty("bindingPlatform","onebot");
                omitted.getAsJsonObject("onebot").remove("serverId");
                QQBindConfig.SERVER_ID="default"; write(omitted); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.isLegacyBindingEnabled());
                String generated=QQBindConfig.SERVER_ID;
                check(!generated.equals("default") && generated.length() == 8);
                QQBindConfig.reload(); check(QQBindConfig.SERVER_ID.equals(generated));
                JsonObject blank=omitted.deepCopy();
                blank.getAsJsonObject("onebot").addProperty("serverId","");
                write(blank); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.SERVER_ID.equals(generated));
                JsonObject placeholder=omitted.deepCopy();
                placeholder.getAsJsonObject("onebot").addProperty("serverId","<SERVER_UUID>");
                write(placeholder); QQBindConfig.reload();
                check(QQBindConfig.getLoadError() == null && QQBindConfig.SERVER_ID.equals(generated));
            });

            test("shared_templates_are_common_and_render_for_each_platform",() -> {
                JsonObject withTemplates=document.deepCopy();
                withTemplates.addProperty("bindingPlatform","onebot");
                withTemplates.add("qq_official",new JsonPrimitive("inactive placeholder is not validated"));
                withTemplates.addProperty("titleTemplate","§cTEST_TITLE");
                withTemplates.addProperty("subtitleTemplate","§e去{groupHint}发 {bindCommand}");
                withTemplates.addProperty("actionBarTemplate","§c去{groupHint}发 {bindCommand}");
                write(withTemplates); QQBindConfig.reload();
                check(QQBindConfig.getLoadError()==null && QQBindConfig.isLegacyBindingEnabled());
                check(QQBindConfig.formatMessage(QQBindConfig.SUBTITLE_TEMPLATE,"123456","当前官方群","/mc bind 123456")
                        .equals("§e去当前官方群发 /mc bind 123456"));
                check(QQBindConfig.formatMessage(QQBindConfig.ACTION_BAR_TEMPLATE,"123456","123456789","/绑定 123456")
                        .equals("§c去123456789发 /绑定 123456"));

                JsonObject misplaced=withTemplates.deepCopy();
                misplaced.getAsJsonObject("onebot").addProperty("subtitleTemplate","§e去{groupHint}发 {bindCommand}");
                write(misplaced); QQBindConfig.reload();
                check(QQBindConfig.getLoadError()!=null && QQBindConfig.isLegacyBindingEnabled()
                        && QQBindConfig.SUBTITLE_TEMPLATE.equals("§e去{groupHint}发 {bindCommand}"));
            });

            test("config_version_unknown_keys_types_and_ranges_are_strict",() -> {
                JsonObject base=document.deepCopy();
                base.addProperty("bindingPlatform","onebot");
                base.add("qq_official",new JsonPrimitive("inactive placeholder is not validated"));
                base.addProperty("configVersion",QQBindConfig.CONFIG_VERSION);
                write(base); QQBindConfig.reload();
                check(QQBindConfig.getLoadError()==null && QQBindConfig.isLegacyBindingEnabled());
                int port=QQBindConfig.HTTP_PORT; String token=QQBindConfig.API_TOKEN;

                JsonObject noVersion=base.deepCopy(); noVersion.remove("configVersion");
                JsonObject oldVersion=base.deepCopy(); oldVersion.addProperty("configVersion",1);
                JsonObject stringVersion=base.deepCopy(); stringVersion.addProperty("configVersion","2");
                JsonObject fractionalVersion=base.deepCopy(); fractionalVersion.addProperty("configVersion",2.5);
                JsonObject unknownKey=base.deepCopy(); unknownKey.addProperty("syntheticTypo","x");
                JsonObject portZero=base.deepCopy(); portZero.addProperty("httpPort",0);
                JsonObject portHigh=base.deepCopy(); portHigh.addProperty("httpPort",70000);
                JsonObject portString=base.deepCopy(); portString.addProperty("httpPort","25566");
                JsonObject emptyHost=base.deepCopy(); emptyHost.addProperty("httpBindHost"," ");
                JsonObject booleanString=base.deepCopy(); booleanString.addProperty("enableWhitelistCheck","true");
                JsonObject dataDirectoryInOnebot=base.deepCopy(); dataDirectoryInOnebot.addProperty("dataDirectory","synthetic/official");
                JsonObject badMode=base.deepCopy(); badMode.getAsJsonObject("onebot").addProperty("storageMode","cloud");
                JsonObject negativeTtl=base.deepCopy(); negativeTtl.getAsJsonObject("onebot").addProperty("cacheTtlSeconds",-1);
                JsonObject zeroRetry=base.deepCopy(); zeroRetry.getAsJsonObject("onebot").addProperty("dbRetryIntervalSeconds",0);
                JsonObject negativeRetries=base.deepCopy(); negativeRetries.getAsJsonObject("onebot").addProperty("dbMaxRetries",-1);
                JsonObject emptyPath=base.deepCopy(); emptyPath.getAsJsonObject("onebot").addProperty("dataFilePath","");
                JsonObject emptyGroup=base.deepCopy(); emptyGroup.getAsJsonObject("onebot").addProperty("qqGroup"," ");
                JsonObject badCommandType=base.deepCopy();
                JsonArray badCommands=new JsonArray(); badCommands.add(1); badCommandType.add("consoleBannedCommands",badCommands);
                JsonObject badCommandObject=base.deepCopy(); badCommandObject.add("consoleBannedCommands",new JsonObject());

                for (JsonObject invalid : List.of(noVersion,oldVersion,stringVersion,fractionalVersion,unknownKey,portZero,portHigh,
                        portString,emptyHost,booleanString,dataDirectoryInOnebot,badMode,negativeTtl,zeroRetry,negativeRetries,
                        emptyPath,emptyGroup,badCommandType,badCommandObject)) {
                    write(invalid); QQBindConfig.reload();
                    check(QQBindConfig.getLoadError()!=null && QQBindConfig.isLegacyBindingEnabled());
                    check(QQBindConfig.HTTP_PORT==port && QQBindConfig.API_TOKEN.equals(token));
                }
            });

            test("console_command_array_is_normalized_and_legacy_string_still_works",() -> {
                JsonObject base=document.deepCopy();
                base.addProperty("bindingPlatform","onebot");
                base.add("qq_official",new JsonPrimitive("inactive placeholder is not validated"));
                base.addProperty("configVersion",QQBindConfig.CONFIG_VERSION);
                JsonArray commands=new JsonArray(); commands.add("op"); commands.add(" ban ");
                base.add("consoleBannedCommands",commands);
                write(base); QQBindConfig.reload();
                check(QQBindConfig.getLoadError()==null && QQBindConfig.CONSOLE_BANNED_COMMANDS.equals("op,ban"));

                base.addProperty("consoleBannedCommands"," op , , ban ");
                write(base); QQBindConfig.reload();
                check(QQBindConfig.getLoadError()==null && QQBindConfig.CONSOLE_BANNED_COMMANDS.equals("op,ban"));
            });

            test("official_flat_config_rejects_onebot_only_top_level_fields",() -> {
                JsonObject leftover=document.deepCopy(); leftover.addProperty("dataFilePath","synthetic/onebot.json");
                write(leftover); QQBindConfig.reload();
                check(QQBindConfig.getLoadError()!=null && QQBindConfig.isLegacyBindingEnabled());
            });

            test("jsonc_inserts_a_missing_key_without_breaking_trailing_comments",() -> {
                String source="{\n  \"a\": 1,\n  /* trailing */\n}\n";
                String updated=Jsonc.updateTopLevelBoolean(source,"flag",true);
                check(updated != null && updated.contains("/* trailing */") && updated.contains("\"flag\": true"));
                JsonObject parsed=OfficialRuntimeSettings.document(updated);
                check(parsed.get("a").getAsInt() == 1 && parsed.get("flag").getAsBoolean());
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
            check(BindingBootstrap.status().startsWith("official_core_only"));
            OfficialGameHostTests.run();
            check(com.mysql.cj.jdbc.Driver.created > 0);
        } else throw new IllegalArgumentException("Known synthetic scenario required");
        System.out.println("Config bridge " + args[1] + ": " + passed + " tests passed (test doubles; no Minecraft/real config)");
    }
}
