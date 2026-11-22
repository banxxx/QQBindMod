package com.poso.qqbind.official;

import com.google.gson.*;
import java.util.*;

/** Versioned mirror/task schemas. No I/O or framework state. */
final class OfficialMirrorModel {
    static final Set<String> EFFECT = Set.of("game_key", "game_id", "user_id", "state", "revision");
    static final Set<String> STATUS = Set.of("PENDING", "APPLIED", "SUPERSEDED", "FAILED", "CONFLICT");
    static final int MAX_BINDINGS = 10000, MAX_OPERATIONS = 10000, MAX_TASKS = 50000;

    static String text(JsonObject value, String field) { return OfficialModState.string(value, field); }
    static boolean bool(JsonObject value, String field) {
        JsonElement item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isBoolean()) {
            throw new OfficialException("INVALID_INPUT");
        }
        return item.getAsBoolean();
    }
    static void fields(JsonObject value, Set<String> fields) {
        if (value == null || !value.keySet().equals(fields)) throw new OfficialException("INVALID_INPUT");
    }
    static Set<String> plus(Set<String> fields, String... others) {
        Set<String> result = new HashSet<>(fields); Collections.addAll(result, others); return result;
    }
    static JsonObject effect(JsonObject value) {
        fields(value, EFFECT);
        if (!OfficialScope.key(text(value, "game_id")).equals(text(value, "game_key"))) throw new OfficialException("INVALID_INPUT");
        OfficialScope.identifier(text(value, "user_id"));
        if (!Set.of("active", "revoked").contains(text(value, "state"))) throw new OfficialException("INVALID_INPUT");
        OfficialScope.revision(text(value, "revision"));
        return value.deepCopy();
    }
    static JsonObject project(JsonObject value) {
        JsonObject result = new JsonObject();
        for (String name : EFFECT) result.add(name, value.get(name).deepCopy());
        return result;
    }
    static JsonArray effects(JsonArray values, int limit) {
        if (values == null || values.size() < 1 || values.size() > limit) throw new OfficialException("LIMIT_EXCEEDED");
        TreeMap<String, JsonObject> sorted = new TreeMap<>();
        for (JsonElement value : values) {
            JsonObject effect = effect(value.getAsJsonObject());
            if (sorted.put(text(effect, "game_key"), effect) != null) throw new OfficialException("INVALID_INPUT");
        }
        JsonArray result = new JsonArray(); sorted.values().forEach(result::add); return result;
    }
    static JsonObject indexed(JsonArray values) {
        JsonObject result = new JsonObject();
        for (JsonElement value : values) {
            JsonObject effect = value.getAsJsonObject(); result.add(text(effect, "game_key"), effect.deepCopy());
        }
        return result;
    }
    static String taskKey(String operationId, String gameKey) { return operationId + ":" + gameKey; }
    static long version(JsonObject value) { return OfficialScope.revision(text(value, "revision")); }

    static void validate(JsonObject root) {
        try {
            JsonObject bindings = root.getAsJsonObject("bindings"), operations = root.getAsJsonObject("operations"), tasks = root.getAsJsonObject("pending_effects");
            if (bindings.size() > MAX_BINDINGS || operations.size() > MAX_OPERATIONS || tasks.size() > MAX_TASKS) {
                throw new OfficialException("LIMIT_EXCEEDED");
            }
            for (var entry : operations.entrySet()) {
                OfficialScope.uuid(entry.getKey()); JsonObject op = entry.getValue().getAsJsonObject();
                fields(op, Set.of("complete", "effects")); bool(op, "complete");
                JsonArray normalized = effects(op.getAsJsonArray("effects"), 500);
                if (!normalized.equals(op.get("effects"))) throw new OfficialException("INVALID_INPUT");
                for (JsonElement effect : normalized) {
                    String key = text(effect.getAsJsonObject(), "game_key");
                    if (!tasks.has(taskKey(entry.getKey(), key))) throw new OfficialException("INVALID_INPUT");
                }
            }
            for (var entry : bindings.entrySet()) {
                JsonObject row = entry.getValue().getAsJsonObject();
                fields(row, plus(EFFECT, "last_operation_id", "conflicted"));
                effect(project(row)); bool(row, "conflicted");
                if (!entry.getKey().equals(text(row, "game_key"))) throw new OfficialException("INVALID_INPUT");
                String operation = OfficialScope.uuid(text(row, "last_operation_id"));
                JsonObject op = operations.getAsJsonObject(operation);
                if (op == null || !project(row).equals(indexed(op.getAsJsonArray("effects")).get(entry.getKey()))) {
                    throw new OfficialException("INVALID_INPUT");
                }
            }
            for (var entry : tasks.entrySet()) {
                JsonObject task = entry.getValue().getAsJsonObject();
                fields(task, plus(EFFECT, "operation_id", "whitelist", "online")); effect(project(task));
                String operation = OfficialScope.uuid(text(task, "operation_id")), key = text(task, "game_key");
                if (!entry.getKey().equals(taskKey(operation, key))) throw new OfficialException("INVALID_INPUT");
                JsonObject op = operations.getAsJsonObject(operation), binding = bindings.getAsJsonObject(key);
                if (op == null || binding == null || !project(task).equals(indexed(op.getAsJsonArray("effects")).get(key))) {
                    throw new OfficialException("INVALID_INPUT");
                }
                for (String part : List.of("whitelist", "online")) {
                    if (!STATUS.contains(text(task, part))) throw new OfficialException("INVALID_INPUT");
                }
                if (version(task) > version(binding) && !bool(binding, "conflicted")) throw new OfficialException("INVALID_INPUT");
                if (version(task) == version(binding) && !project(task).equals(project(binding)) && !bool(binding, "conflicted")) {
                    throw new OfficialException("INVALID_INPUT");
                }
            }
        } catch (OfficialException e) {
            if (e.code().equals("LIMIT_EXCEEDED")) throw e;
            throw new OfficialException("STORAGE_ERROR");
        } catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
    }
}
