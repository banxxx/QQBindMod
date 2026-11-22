package com.poso.qqbind.official;

import com.google.gson.*;
import java.sql.*;
import java.util.*;

/** Metadata and zero-row SELECT preflight, no grants/user data/config reads. */
public final class OfficialCenterSchema {
    private OfficialCenterSchema() { }
    static final String NAMES = "('bindings_qqofficial','bindings_qqofficial_ops')";
    static final String TABLE_SQL = "SELECT TABLE_NAME AS table_name,TABLE_TYPE AS table_type,ENGINE AS engine,TABLE_COLLATION AS table_collation FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN " + NAMES;
    static final String COLUMN_SQL = "SELECT TABLE_NAME AS table_name,COLUMN_NAME AS column_name,DATA_TYPE AS data_type,COLUMN_TYPE AS column_type,IS_NULLABLE AS is_nullable,CHARACTER_MAXIMUM_LENGTH AS char_length,COLLATION_NAME AS collation_name,DATETIME_PRECISION AS datetime_precision,EXTRA AS extra FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN " + NAMES;
    static final String INDEX_SQL = "SELECT TABLE_NAME AS table_name,INDEX_NAME AS index_name,NON_UNIQUE AS non_unique,SEQ_IN_INDEX AS seq_in_index,COLUMN_NAME AS column_name,SUB_PART AS sub_part FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN " + NAMES;
    static final String SESSION_SQL = "SELECT @@SESSION.sql_mode AS sql_mode,@@SESSION.time_zone AS time_zone,@@SESSION.character_set_connection AS character_set_connection";

    private static JsonObject col(String type, boolean nullable, Integer length, String collation, boolean auto) {
        JsonObject v = new JsonObject(); v.addProperty("data_type", type); v.addProperty("unsigned", type.equals("bigint"));
        v.addProperty("nullable", nullable); v.addProperty("char_length", length); v.addProperty("collation_name", collation);
        v.add("datetime_precision", type.equals("datetime") ? new JsonPrimitive(6) : JsonNull.INSTANCE);
        v.addProperty("auto_increment", auto); return v;
    }
    private static void add(JsonObject cols, String names, String type, boolean nullable, Integer length, String collation) {
        for (String name : names.split(",")) cols.add(name, col(type, nullable, length, collation, name.equals("id")));
    }
    private static JsonArray index(boolean unique, String names) {
        JsonArray v = new JsonArray(), parts = new JsonArray(); v.add(unique);
        for (String name : names.split(",")) parts.add(name); v.add(parts); return v;
    }
    public static JsonObject spec() {
        JsonObject all = new JsonObject(), binding = new JsonObject(), operation = new JsonObject();
        JsonObject b = new JsonObject(), o = new JsonObject(), bi = new JsonObject(), oi = new JsonObject();
        add(b,"id,revision","bigint",false,null,null); add(b,"environment_id,last_operation_id","char",false,36,"ascii_bin");
        add(b,"group_id,game_id,qq","varchar",false,64,"utf8mb4_bin"); add(b,"game_key,state","varchar",false,16,"ascii_bin");
        add(b,"bind_time,updated_at","datetime",false,null,null);
        add(o,"operation_id,environment_id","char",false,36,"ascii_bin"); add(o,"group_id,actor_id,target_user_id","varchar",false,64,"utf8mb4_bin");
        add(o,"request_key,payload_hash","char",false,64,"ascii_bin"); add(o,"kind,actor_kind,state","varchar",false,16,"ascii_bin");
        add(o,"original_server_id,token_generation,reservation_id","char",true,36,"ascii_bin"); add(o,"policy_revision","bigint",false,null,null);
        add(o,"intent,effects,followup","json",false,null,null); add(o,"nickname_basis","json",true,null,null);
        add(o,"result_code","varchar",true,32,"ascii_bin"); add(o,"next_attempt_at","datetime",true,null,null);
        add(o,"created_at,updated_at","datetime",false,null,null);
        bi.add("PRIMARY",index(true,"id")); bi.add("uq_official_game",index(true,"environment_id,group_id,game_key"));
        bi.add("ix_official_user",index(false,"environment_id,group_id,qq,state"));
        oi.add("PRIMARY",index(true,"operation_id")); oi.add("uq_official_request",index(true,"environment_id,request_key"));
        oi.add("uq_official_token",index(true,"environment_id,original_server_id,token_generation"));
        oi.add("ix_official_pending",index(false,"environment_id,state,next_attempt_at")); oi.add("ix_official_actor",index(false,"environment_id,group_id,actor_id,created_at"));
        binding.add("columns",b); binding.add("indexes",bi); operation.add("columns",o); operation.add("indexes",oi);
        all.add("bindings_qqofficial",binding); all.add("bindings_qqofficial_ops",operation); return all;
    }

    private static String text(JsonObject row, String field) { return OfficialModState.string(row,field); }
    private static long integer(JsonObject row, String field) {
        JsonElement value = row.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[0-9]+")) throw new IllegalArgumentException();
        return value.getAsLong();
    }
    public static void validate(JsonArray tables, JsonArray columns, JsonArray indexes, JsonObject session) {
        try {
            Set<String> modes = Set.of(text(session,"sql_mode").toUpperCase(Locale.ROOT).split(","));
            if (Collections.disjoint(modes,Set.of("STRICT_ALL_TABLES","STRICT_TRANS_TABLES")) || !text(session,"time_zone").equals("+00:00")
                    || !text(session,"character_set_connection").equals("utf8mb4")) throw new IllegalArgumentException();
            JsonObject spec = spec(), seen = new JsonObject(), grouped = new JsonObject(); Set<String> found = new HashSet<>();
            if (tables.size() != 2) throw new IllegalArgumentException();
            for (JsonElement v : tables) {
                JsonObject row = v.getAsJsonObject(); String table = text(row,"table_name");
                if (!spec.has(table) || !found.add(table) || !text(row,"table_type").equals("BASE TABLE")
                        || !text(row,"engine").equalsIgnoreCase("InnoDB") || !text(row,"table_collation").equals("utf8mb4_bin")) throw new IllegalArgumentException();
                seen.add(table,new JsonObject()); grouped.add(table,new JsonObject());
            }
            for (JsonElement v : columns) {
                JsonObject row = v.getAsJsonObject(); JsonObject cols = seen.getAsJsonObject(text(row,"table_name")); String name = text(row,"column_name");
                String extra = text(row,"extra").toLowerCase(Locale.ROOT).trim(), nullable = text(row,"is_nullable");
                if (cols == null || cols.has(name) || !Set.of("","auto_increment").contains(extra) || !Set.of("YES","NO").contains(nullable)) throw new IllegalArgumentException();
                JsonObject actual = new JsonObject(); actual.addProperty("data_type",text(row,"data_type").toLowerCase(Locale.ROOT));
                actual.addProperty("unsigned",Arrays.asList(text(row,"column_type").toLowerCase(Locale.ROOT).split("[^a-z]+")).contains("unsigned"));
                actual.addProperty("nullable",nullable.equals("YES")); actual.add("char_length",row.get("char_length"));
                actual.add("collation_name",row.get("collation_name")); actual.add("datetime_precision",row.get("datetime_precision"));
                actual.addProperty("auto_increment",extra.equals("auto_increment")); cols.add(name,actual);
            }
            for (String table : spec.keySet()) if (!seen.get(table).equals(spec.getAsJsonObject(table).get("columns"))) throw new IllegalArgumentException();
            for (JsonElement v : indexes) {
                JsonObject row = v.getAsJsonObject(), names = grouped.getAsJsonObject(text(row,"table_name"));
                String name = text(row,"index_name"), column = text(row,"column_name"); long nonUnique = integer(row,"non_unique"), seq = integer(row,"seq_in_index");
                if (names == null || !row.get("sub_part").isJsonNull() || nonUnique > 1 || seq < 1
                        || !seen.getAsJsonObject(text(row,"table_name")).has(column)) throw new IllegalArgumentException();
                if (!names.has(name)) { JsonObject d = new JsonObject(); d.addProperty("unique",nonUnique == 0); d.add("parts",new JsonObject()); names.add(name,d); }
                JsonObject d = names.getAsJsonObject(name), parts = d.getAsJsonObject("parts"); String position = Long.toString(seq);
                if (d.get("unique").getAsBoolean() != (nonUnique == 0) || parts.has(position)) throw new IllegalArgumentException(); parts.addProperty(position,column);
            }
            for (String table : spec.keySet()) {
                JsonObject names = grouped.getAsJsonObject(table), expected = spec.getAsJsonObject(table).getAsJsonObject("indexes");
                for (String name : names.keySet()) {
                    JsonObject d = names.getAsJsonObject(name), parts = d.getAsJsonObject("parts"); JsonArray actual = new JsonArray(), keys = new JsonArray();
                    actual.add(d.get("unique"));
                    for (int i = 1; i <= parts.size(); i++) { if (!parts.has(Integer.toString(i))) throw new IllegalArgumentException(); keys.add(parts.get(Integer.toString(i))); }
                    actual.add(keys);
                    if (expected.has(name) ? !expected.get(name).equals(actual) : d.get("unique").getAsBoolean()) throw new IllegalArgumentException();
                }
                if (!names.keySet().containsAll(expected.keySet())) throw new IllegalArgumentException();
            }
        } catch (Exception e) { throw new OfficialException("SCHEMA_MISMATCH"); }
    }

    static JsonArray rows(Connection connection, String sql, int limit) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(5); statement.setMaxRows(limit + 1);
            try (ResultSet result = statement.executeQuery(sql)) {
                JsonArray rows = new JsonArray(); ResultSetMetaData meta = result.getMetaData();
                while (result.next()) {
                    if (rows.size() == limit) throw new OfficialException("SCHEMA_MISMATCH"); JsonObject row = new JsonObject();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Object value = result.getObject(i); String name = meta.getColumnLabel(i);
                        if (value == null) row.add(name,JsonNull.INSTANCE);
                        else if (value instanceof Number number) row.addProperty(name,number);
                        else if (value instanceof String text) row.addProperty(name,text);
                        else throw new OfficialException("SCHEMA_MISMATCH");
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }
    public static void inspect(Connection connection) throws SQLException {
        inspect(connection,false);
    }
    public static void inspect(Connection connection,boolean writeAccess) throws SQLException {
        JsonArray session = rows(connection,SESSION_SQL,1);
        if (session.size() != 1) throw new OfficialException("SCHEMA_MISMATCH");
        validate(rows(connection,TABLE_SQL,2),rows(connection,COLUMN_SQL,40),rows(connection,INDEX_SQL,200),session.get(0).getAsJsonObject());
        for (var entry : spec().entrySet()) {
            String columns = String.join(",",entry.getValue().getAsJsonObject().getAsJsonObject("columns").keySet());
            rows(connection,"SELECT " + columns + " FROM " + entry.getKey() + " WHERE 1=0",0);
            if (writeAccess) {
                JsonObject definition=entry.getValue().getAsJsonObject().getAsJsonObject("columns"); List<String> samples=new ArrayList<>(), updates=new ArrayList<>();
                for (var field : definition.entrySet()) {
                    JsonObject spec=field.getValue().getAsJsonObject(); String type=text(spec,"data_type");
                    samples.add(spec.get("nullable").getAsBoolean() ? "NULL" : switch(type) {
                        case "bigint" -> "1";
                        case "datetime" -> "'2026-11-18 00:00:00.000000'";
                        case "json" -> field.getKey().equals("effects") ? "'[]'" : "'{}'";
                        default -> "'SYNTHETIC'";
                    });
                    updates.add(field.getKey() + "=" + field.getKey());
                }
                // Plans only: never ANALYZE, functions, subqueries or executable probe DML.
                rows(connection,"EXPLAIN INSERT INTO " + entry.getKey() + " (" + columns + ") VALUES (" + String.join(",",samples) + ")",100);
                rows(connection,"EXPLAIN UPDATE " + entry.getKey() + " SET " + String.join(",",updates) + " WHERE 1=0",100);
            }
        }
    }
}
