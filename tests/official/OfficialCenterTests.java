package com.poso.qqbind.official;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Fake JDBC only: no DriverManager, configured database, sockets or user files. */
public final class OfficialCenterTests {
    static JsonObject fixture;
    static int passed;
    static final OfficialScope S = new OfficialScope("11111111-1111-4111-8111-111111111111","SYNTHETIC_GROUP_A","33333333-3333-4333-8333-333333333333");
    static final String OP = "66666666-6666-4666-8666-666666666666";
    interface Checked { void run() throws Exception; }
    static void check(boolean condition) { if (!condition) throw new AssertionError("Synthetic assertion failed"); }
    static void test(String name, Checked action) throws Exception { action.run(); passed++; System.out.println("PASS " + name); }
    static void expect(String code, Checked action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (OfficialException e) { check(e.code().equals(code)); check(!e.getMessage().contains("SYNTHETIC_PRIVATE")); }
    }
    static JsonObject operation(String name) {
        for (JsonElement item : fixture.getAsJsonArray("operations")) if (item.getAsJsonObject().get("name").getAsString().equals(name)) return item.getAsJsonObject().getAsJsonObject("row").deepCopy();
        throw new AssertionError();
    }
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(OfficialCenterTests.class.getClassLoader(),new Class<?>[]{type},handler);
    }
    static Object unused(Method method) {
        throw new AssertionError("Unexpected JDBC method " + method.getName());
    }
    static class Fake {
        JsonObject metadata = fixture.getAsJsonObject("metadata").deepCopy(), row = operation("committed");
        final List<String> sql = new ArrayList<>(); final Map<Integer,String> parameters = new TreeMap<>();
        final Map<String,String> raw = new HashMap<>(); int opens, rollbacks, closes, connects; boolean duplicate;
        String failure; int errno; String connectedUrl; Properties connectedOptions;
        final Connection connection = proxy(Connection.class,(p,m,a) -> {
            switch (m.getName()) {
                case "createStatement": return statement(false);
                case "prepareStatement": check(a[0].equals(OfficialJdbcCenter.LOOKUP)); return statement(true);
                case "rollback": rollbacks++; return null;
                case "close": closes++; return null;
                case "setReadOnly": check(a[0].equals(true)); return null;
                case "setAutoCommit": check(a[0].equals(false)); return null;
                case "setTransactionIsolation": check(a[0].equals(Connection.TRANSACTION_REPEATABLE_READ)); return null;
                default: return unused(m);
            }
        });
        Connection open() { opens++; return connection; }
        Object statement(boolean prepared) {
            InvocationHandler handler = (p,m,a) -> {
                switch (m.getName()) {
                    case "setQueryTimeout": check(a[0].equals(5)); return null;
                    case "setMaxRows": check((Integer)a[0] > 0); return null;
                    case "setString": parameters.put((Integer)a[0],(String)a[1]); return null;
                    case "close": return null;
                    case "execute":
                    case "executeQuery":
                        String query = prepared ? OfficialJdbcCenter.LOOKUP : (String)a[0]; sql.add(query);
                        if (failure != null && query.contains(failure)) throw new SQLException("SYNTHETIC_PRIVATE_DB_TEXT","synthetic",errno);
                        if (m.getName().equals("execute")) return false;
                        JsonArray values;
                        if (query.equals(OfficialCenterSchema.SESSION_SQL)) { values = new JsonArray(); values.add(metadata.get("session")); }
                        else if (query.equals(OfficialCenterSchema.TABLE_SQL)) values = metadata.getAsJsonArray("tables");
                        else if (query.equals(OfficialCenterSchema.COLUMN_SQL)) values = metadata.getAsJsonArray("columns");
                        else if (query.equals(OfficialCenterSchema.INDEX_SQL)) values = metadata.getAsJsonArray("indexes");
                        else if (prepared) { values = new JsonArray(); if (row != null) { values.add(row); if (duplicate) values.add(row); } }
                        else { check(query.startsWith("SELECT ") && query.endsWith(" WHERE 1=0")); values = new JsonArray(); }
                        return result(values);
                    default: return unused(m);
                }
            };
            return prepared ? proxy(PreparedStatement.class,handler) : proxy(Statement.class,handler);
        }
        ResultSet result(JsonArray values) {
            final int[] position = {-1};
            List<String> names = values.isEmpty() ? List.of() : new ArrayList<>(values.get(0).getAsJsonObject().keySet());
            ResultSetMetaData meta = proxy(ResultSetMetaData.class,(p,m,a) -> switch (m.getName()) {
                case "getColumnCount" -> names.size();
                case "getColumnLabel" -> names.get((Integer)a[0]-1);
                default -> unused(m);
            });
            return proxy(ResultSet.class,(p,m,a) -> {
                if (m.getName().equals("next")) return ++position[0] < values.size();
                if (m.getName().equals("close")) return null;
                if (m.getName().equals("getMetaData")) return meta;
                String name = a[0] instanceof Integer index ? names.get(index-1) : (String)a[0];
                JsonElement value = values.get(position[0]).getAsJsonObject().get(name);
                switch (m.getName()) {
                    case "getObject":
                        if (value == null || value.isJsonNull()) return null;
                        if (value.getAsJsonPrimitive().isNumber()) return value.getAsNumber(); return value.getAsString();
                    case "getString": return value == null || value.isJsonNull() ? null : value.getAsString();
                    case "getCharacterStream":
                        if (raw.containsKey(name)) return new StringReader(raw.get(name));
                        return value == null || value.isJsonNull() ? null : new StringReader(value.toString());
                    default: return unused(m);
                }
            });
        }
        OfficialJdbcCenter center() { return new OfficialJdbcCenter(S,this::open); }
        Driver driver() {
            return proxy(Driver.class,(p,m,a) -> {
                if (!m.getName().equals("connect")) return unused(m);
                connects++; connectedUrl=(String)a[0]; connectedOptions=(Properties)a[1];
                if (failure != null) throw new SQLException("SYNTHETIC_PRIVATE_PASSWORD","synthetic",1045);
                return connection;
            });
        }
    }
    static Properties settings() {
        Properties p = new Properties();
        p.setProperty("host","db.example.invalid"); p.setProperty("port","4000"); p.setProperty("database","synthetic_official");
        p.setProperty("user","SYNTHETIC_DB_USER"); p.setProperty("password","SYNTHETIC_NOT_A_REAL_PASSWORD"); return p;
    }
    static void validate(JsonObject metadata) {
        OfficialCenterSchema.validate(metadata.getAsJsonArray("tables"),metadata.getAsJsonArray("columns"),metadata.getAsJsonArray("indexes"),metadata.getAsJsonObject("session"));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Explicit synthetic fixture path required");
        fixture = OfficialModState.parse(Files.readString(Path.of(args[0]))).getAsJsonObject(); check(fixture.get("synthetic").getAsBoolean());
        test("shared_schema_matches_python_and_validates", () -> {
            check(OfficialCenterSchema.spec().equals(fixture.get("schema_spec"))); validate(fixture.getAsJsonObject("metadata"));
        });
        test("shared_canonical_intents_match_bytes_and_sha256", () -> {
            for (JsonElement value : fixture.getAsJsonArray("canonical_cases")) {
                JsonObject c=value.getAsJsonObject(); OfficialIntent.validate(c.getAsJsonObject("intent"));
                check(OfficialIntent.canonical(c.get("intent")).equals(c.get("canonical").getAsString()));
                check(OfficialIntent.hash(c.get("intent")).equals(c.get("payload_hash").getAsString()));
            }
            JsonObject c=fixture.getAsJsonObject("codec");
            check(OfficialIntent.canonical(c.get("value")).equals(c.get("canonical").getAsString()));
            check(OfficialIntent.hash(c.get("value")).equals(c.get("payload_hash").getAsString()));
        });
        test("canonical_rejects_numeric_boolean_surrogate_and_extra_fields", () -> {
            for (JsonElement value : List.of(new JsonPrimitive(1),new JsonPrimitive(true),new JsonPrimitive("\ud800"))) expect("INVALID_INPUT",() -> OfficialIntent.canonical(value));
            JsonObject v=fixture.getAsJsonArray("canonical_cases").get(0).getAsJsonObject().getAsJsonObject("intent").deepCopy(); v.addProperty("token_code","123456");
            expect("INVALID_INPUT",() -> OfficialIntent.validate(v));
        });
        test("intent_rejects_unsorted_or_duplicate_fixed_servers", () -> {
            JsonObject v=fixture.getAsJsonArray("canonical_cases").get(0).getAsJsonObject().getAsJsonObject("intent").deepCopy();
            v.getAsJsonObject("intent").getAsJsonArray("target_server_ids").add(S.serverId()); expect("INVALID_INPUT",() -> OfficialIntent.validate(v));
        });
        test("schema_rejects_collation_unsigned_precision_and_nullable_drift", () -> {
            for (String field : List.of("collation_name","column_type","datetime_precision","is_nullable")) {
                JsonObject meta=fixture.getAsJsonObject("metadata").deepCopy();
                for (JsonElement value : meta.getAsJsonArray("columns")) {
                    JsonObject row=value.getAsJsonObject(); String name=row.get("column_name").getAsString();
                    if (field.equals("collation_name") && name.equals("group_id")) { row.addProperty(field,"utf8mb4_general_ci"); break; }
                    if (field.equals("column_type") && name.equals("revision")) { row.addProperty(field,"bigint"); break; }
                    if (field.equals("datetime_precision") && name.equals("bind_time")) { row.addProperty(field,0); break; }
                    if (field.equals("is_nullable") && name.equals("qq")) { row.addProperty(field,"YES"); break; }
                }
                expect("SCHEMA_MISMATCH",() -> validate(meta));
            }
        });
        test("schema_rejects_extra_unique_user_key_and_missing_index", () -> {
            JsonObject meta=fixture.getAsJsonObject("metadata").deepCopy(), index=meta.getAsJsonArray("indexes").get(0).getAsJsonObject().deepCopy();
            index.addProperty("index_name","bad_unique_user"); index.addProperty("column_name","qq"); meta.getAsJsonArray("indexes").add(index);
            expect("SCHEMA_MISMATCH",() -> validate(meta));
            JsonObject missing=fixture.getAsJsonObject("metadata").deepCopy(); missing.getAsJsonArray("indexes").remove(0);
            expect("SCHEMA_MISMATCH",() -> validate(missing));
        });
        test("schema_rejects_non_strict_or_non_utc_and_missing_table", () -> {
            for (String mode : List.of("sql_mode","time_zone","character_set_connection","table")) {
                JsonObject meta=fixture.getAsJsonObject("metadata").deepCopy();
                if (mode.equals("table")) meta.getAsJsonArray("tables").remove(0);
                else meta.getAsJsonObject("session").addProperty(mode,mode.equals("sql_mode") ? "NO_ENGINE_SUBSTITUTION" : mode.equals("time_zone") ? "SYSTEM" : "utf8mb3");
                expect("SCHEMA_MISMATCH",() -> validate(meta));
            }
        });
        test("shared_operation_states_are_verified_without_changes", () -> {
            Fake f=new Fake();
            for (JsonElement item : fixture.getAsJsonArray("operations")) {
                JsonObject row=item.getAsJsonObject().getAsJsonObject("row"); check(f.center().validate(row).equals(row));
            }
            check(f.opens == 0);
        });
        test("jdbc_lookup_is_scoped_parameterized_and_read_only", () -> {
            Fake f=new Fake(); var operation=f.center().find(OP);
            check(operation.state().equals("COMMITTED") && operation.userId().equals("SYNTHETIC_USER_A"));
            check(f.opens == 1 && f.closes == 1 && f.rollbacks == 1);
            check(f.parameters.equals(Map.of(1,OP,2,S.environmentId(),3,S.groupId())));
            check(f.sql.stream().allMatch(sql -> sql.startsWith("SELECT ")));
            operation.effects().get(0).getAsJsonObject().addProperty("revision","999"); check(f.row.getAsJsonArray("effects").get(0).getAsJsonObject().get("revision").getAsString().equals("1"));
        });
        test("missing_operation_returns_null_but_failure_never_does", () -> {
            Fake missing=new Fake(); missing.row=null; check(missing.center().find(OP) == null);
            Fake unavailable=new Fake(); unavailable.failure="FROM bindings_qqofficial_ops WHERE"; unavailable.errno=0;
            expect("DB_UNAVAILABLE",() -> unavailable.center().find(OP)); check(unavailable.opens == 1 && unavailable.closes == 1);
            expect("DB_UNAVAILABLE",() -> new OfficialJdbcCenter(S,() -> { throw new IllegalStateException("SYNTHETIC_PRIVATE_CONNECTOR"); }).find(OP));
        });
        test("schema_failure_stops_before_operation_query", () -> {
            Fake f=new Fake(); f.metadata.getAsJsonArray("tables").remove(0);
            expect("SCHEMA_MISMATCH",() -> f.center().find(OP)); check(!f.sql.contains(OfficialJdbcCenter.LOOKUP));
        });
        test("sql_privilege_failure_is_sanitized_and_not_replayed", () -> {
            Fake f=new Fake(); f.failure="FROM bindings_qqofficial_ops WHERE"; f.errno=1142;
            expect("UNAUTHORIZED",() -> f.center().find(OP)); check(f.opens == 1 && f.closes == 1);
        });
        test("tampered_hash_identity_effect_or_followup_never_authorizes", () -> {
            for (String field : List.of("payload_hash","target_user_id","effects","followup")) {
                Fake f=new Fake();
                switch(field) {
                    case "payload_hash" -> f.row.addProperty(field,"0".repeat(64));
                    case "target_user_id" -> f.row.addProperty(field,"SYNTHETIC_OTHER_USER");
                    case "effects" -> f.row.getAsJsonArray(field).get(0).getAsJsonObject().addProperty("state","revoked");
                    case "followup" -> f.row.getAsJsonObject(field).getAsJsonObject("delivery").remove(S.serverId());
                }
                expect("STORAGE_ERROR",() -> f.center().find(OP));
            }
        });
        test("wrong_environment_group_server_and_duplicate_rows_are_rejected", () -> {
            for (String field : List.of("environment_id","group_id","original_server_id")) {
                Fake f=new Fake(); f.row.addProperty(field,field.equals("group_id") ? "SYNTHETIC_OTHER_GROUP" : "22222222-2222-4222-8222-222222222222");
                if (field.equals("original_server_id")) {
                    JsonObject normalized=fixture.getAsJsonArray("canonical_cases").get(0).getAsJsonObject().getAsJsonObject("intent").deepCopy();
                    normalized.add("server_id",f.row.get(field)); f.row.addProperty("payload_hash",OfficialIntent.hash(normalized));
                }
                expect("SCOPE_MISMATCH",() -> f.center().find(OP));
            }
            Fake duplicate=new Fake(); duplicate.duplicate=true; expect("STORAGE_ERROR",() -> duplicate.center().find(OP));
        });
        test("invalid_duplicate_json_and_oversized_json_are_rejected", () -> {
            for (String raw : List.of("{invalid}","{\"delivery\":{},\"delivery\":{}}"," ".repeat(OfficialIntent.MAX_BYTES + 1))) {
                Fake f=new Fake(); f.raw.put("followup",raw); expect("STORAGE_ERROR",() -> f.center().find(OP));
            }
        });
        test("factory_inactive_never_reads_settings_or_connects", () -> {
            Fake f=new Fake(); var factory=new OfficialJdbcConnections(f.driver(),() -> { throw new AssertionError("Settings must not be read"); },() -> false);
            expect("NOT_SUPPORTED",factory::open); check(f.connects == 0);
        });
        test("factory_single_host_tls_deadlines_session_no_reconnect", () -> {
            Fake f=new Fake(); var factory=new OfficialJdbcConnections(f.driver(),OfficialCenterTests::settings,() -> true);
            check(factory.open() == f.connection); check(f.connects == 1 && f.connectedUrl.equals("jdbc:mysql://db.example.invalid:4000/synthetic_official"));
            check(f.connectedOptions.getProperty("sslMode").equals("VERIFY_IDENTITY"));
            check(f.connectedOptions.getProperty("autoReconnect").equals("false")); check(f.connectedOptions.getProperty("socketTimeout").equals("5000"));
            check(f.sql.size() == 2 && f.sql.stream().allMatch(sql -> sql.startsWith("SET SESSION ")));
        });
        test("factory_rejects_url_options_before_driver_and_closes_after_stop", () -> {
            Fake f=new Fake(); Properties config=settings(); config.setProperty("host","db.example.invalid,other.example.invalid");
            expect("DB_UNAVAILABLE",() -> new OfficialJdbcConnections(f.driver(),() -> config,() -> true).open()); check(f.connects == 0);
            Fake stopped=new Fake(); AtomicInteger checks=new AtomicInteger();
            expect("NOT_SUPPORTED",() -> new OfficialJdbcConnections(stopped.driver(),OfficialCenterTests::settings,() -> checks.incrementAndGet() < 3).open());
            check(stopped.connects == 1 && stopped.closes == 1);
        });
        test("factory_connector_failure_is_sanitized_and_never_retried", () -> {
            Fake f=new Fake(); f.failure="connect";
            expect("UNAUTHORIZED",() -> new OfficialJdbcConnections(f.driver(),OfficialCenterTests::settings,() -> true).open()); check(f.connects == 1);
        });
        System.out.println("Official center: " + passed + " tests passed");
    }
}
