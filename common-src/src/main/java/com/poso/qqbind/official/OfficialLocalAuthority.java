package com.poso.qqbind.official;

import com.google.gson.*;
import java.util.*;

/** Per-server local authority. Bindings, operation decisions, reservation
 * tombstones and game outbox are committed in the SAME OfficialModState file. */
public final class OfficialLocalAuthority implements OfficialCenterMutations.Store, OfficialTokenService.CenterInspector {
    private final OfficialScope scope;
    private final OfficialMirrorService mirror;
    public OfficialLocalAuthority(OfficialScope scope,OfficialMirrorService mirror) { this.scope=scope; this.mirror=mirror; }
    static String text(JsonObject value,String name) { return OfficialModState.string(value,name); }
    static void validate(JsonObject root,OfficialScope scope) {
        JsonObject local=root.getAsJsonObject("local_authority");
        OfficialMirrorModel.fields(local,Set.of("bindings","operations"));
        JsonObject rows=local.getAsJsonObject("bindings"), operations=local.getAsJsonObject("operations");
        if (rows.size()>10000 || operations.size()>10000) throw new OfficialException("LIMIT_EXCEEDED");
        OfficialJdbcCenter validator=new OfficialJdbcCenter(scope,() -> { throw new OfficialException("NOT_SUPPORTED"); });
        Set<String> requests=new HashSet<>(), generations=new HashSet<>();
        for (var entry : operations.entrySet()) {
            JsonObject operation=validator.validate(entry.getValue().getAsJsonObject());
            if (!entry.getKey().equals(text(operation,"operation_id")) || !requests.add(text(operation,"request_key"))) throw new OfficialException("STORAGE_ERROR");
            JsonArray targets=operation.getAsJsonObject("intent").getAsJsonArray("target_server_ids");
            if (targets.size()!=1 || !targets.get(0).getAsString().equals(scope.serverId())) throw new OfficialException("SCOPE_MISMATCH");
            if (text(operation,"kind").equals("bind") && (!text(operation,"original_server_id").equals(scope.serverId())
                    || !generations.add(text(operation,"token_generation")))) throw new OfficialException("STORAGE_ERROR");
        }
        for (var entry : rows.entrySet()) {
            JsonObject row=entry.getValue().getAsJsonObject(), effect=effect(row);
            if (!entry.getKey().equals(text(row,"game_key"))) throw new OfficialException("STORAGE_ERROR");
            JsonObject operation=operations.getAsJsonObject(text(row,"last_operation_id"));
            if (operation==null || !text(operation,"state").equals("COMMITTED")
                    || !effect.equals(OfficialMirrorModel.indexed(operation.getAsJsonArray("effects")).get(entry.getKey()))) throw new OfficialException("STORAGE_ERROR");
            JsonObject published=root.getAsJsonObject("bindings").getAsJsonObject(entry.getKey());
            if (published==null || !effect.equals(OfficialMirrorModel.project(published)) || OfficialMirrorModel.bool(published,"conflicted")) throw new OfficialException("STORAGE_ERROR");
        }
        if (rows.size()!=root.getAsJsonObject("bindings").size()) throw new OfficialException("STORAGE_ERROR");
    }
    static JsonObject effect(JsonObject row) {
        JsonObject effect=new JsonObject();
        for (String name : List.of("game_key","game_id","state","revision")) effect.add(name,row.get(name));
        effect.add("user_id",row.get("qq")); return OfficialMirrorModel.effect(effect);
    }
    public JsonArray rows() {
        JsonArray result=new JsonArray();
        for (JsonElement item : mirror.durableOnIo().getAsJsonObject("local_authority").getAsJsonObject("bindings").asMap().values()) {
            JsonObject row=item.getAsJsonObject(), value=effect(row); value.add("last_operation_id",row.get("last_operation_id")); result.add(value);
        }
        return result;
    }
    public JsonObject operation(String id) {
        OfficialScope.uuid(id);
        JsonObject row=mirror.durableOnIo().getAsJsonObject("local_authority").getAsJsonObject("operations").getAsJsonObject(id);
        return row==null ? null : row.deepCopy();
    }
    public JsonArray operations() {
        JsonArray result=new JsonArray();
        for (JsonElement row : mirror.durableOnIo().getAsJsonObject("local_authority").getAsJsonObject("operations").asMap().values()) result.add(row.deepCopy());
        return result;
    }
    @Override public OfficialTokenService.CenterOperation find(String id) {
        JsonObject row=operation(id); if (row==null) return null;
        if (!text(row,"kind").equals("bind")) throw new OfficialException("CONFLICT");
        return new OfficialTokenService.CenterOperation(scope,id,text(row,"token_generation"),text(row.getAsJsonObject("intent"),"game_id"),
            text(row,"target_user_id"),text(row,"state"),row.getAsJsonArray("effects"),row.getAsJsonObject("followup").getAsJsonObject("delivery"));
    }
    @Override public <T> T transact(OfficialCenterMutations.Work<T> work) {
        return mirror.atomicOnIo(root -> {
            JsonObject local=root.getAsJsonObject("local_authority"), rows=local.getAsJsonObject("bindings"), operations=local.getAsJsonObject("operations");
            Set<String> changed=new HashSet<>();
            T result=work.run(new OfficialCenterMutations.Transaction() {
                public JsonObject operation(String id) { JsonObject row=operations.getAsJsonObject(id); return row==null ? null : row.deepCopy(); }
                public JsonObject request(String environment,String key) { return lookup("request_key",key); }
                public JsonObject generation(String environment,String server,String generation) { return lookup("token_generation",generation); }
                private JsonObject lookup(String field,String value) {
                    for (JsonElement item : operations.asMap().values()) { JsonObject row=item.getAsJsonObject();
                        if (!row.get(field).isJsonNull() && text(row,field).equals(value)) return row.deepCopy();
                    } return null;
                }
                public Map<String,JsonObject> lockBindings(SortedSet<String> keys) {
                    Map<String,JsonObject> result=new TreeMap<>(); for (String key : keys) { JsonObject row=rows.getAsJsonObject(key); result.put(key,row==null ? null : row.deepCopy()); } return result;
                }
                public void insertOperation(JsonObject row) {
                    String id=text(row,"operation_id"); if (operations.has(id) || operations.size()>=10000) throw new OfficialException("LIMIT_EXCEEDED");
                    operations.add(id,row.deepCopy()); changed.add(id);
                }
                public void updateOperation(JsonObject before,JsonObject after) {
                    String id=text(before,"operation_id"); if (!before.equals(operations.get(id))) throw new OfficialException("CONFLICT");
                    operations.add(id,after.deepCopy()); changed.add(id);
                }
                public void applyBinding(JsonObject before,JsonObject effect,String id,String now) {
                    String key=text(effect,"game_key"); if (!Objects.equals(before,rows.get(key))) throw new OfficialException("CONFLICT");
                    JsonObject row=new JsonObject();
                    for (String name : List.of("game_key","game_id","state","revision")) row.add(name,effect.get(name));
                    row.add("qq",effect.get("user_id")); row.addProperty("last_operation_id",id); row.addProperty("updated_at",now);
                    rows.add(key,row);
                }
            });
            for (String id : changed) {
                JsonObject operation=operations.getAsJsonObject(id);
                if (text(operation,"state").equals("COMMITTED")) {
                    JsonArray effects=operation.getAsJsonArray("effects");
                    OfficialMirrorService.cache(root,id,effects,true);
                    for (JsonElement effect : effects) OfficialMirrorService.merge(root,id,effect.getAsJsonObject());
                }
                settleReservation(root,operation);
            }
            return result;
        });
    }
    private void settleReservation(JsonObject root,JsonObject operation) {
        if (!text(operation,"kind").equals("bind") || !OfficialCenterMutations.TERMINAL.contains(text(operation,"state"))) return;
        String id=text(operation,"operation_id"), state=text(operation,"state"), expected=state.equals("COMMITTED") ? "CONSUMED" : "INVALIDATED";
        JsonObject reservations=root.getAsJsonObject("reservations"), reservation=reservations.getAsJsonObject(id);
        if (reservation==null) {
            if (state.equals("COMMITTED")) throw new OfficialException("STORAGE_ERROR");
            reservation=new JsonObject(); reservation.addProperty("operation_id",id); reservation.add("token_generation",operation.get("token_generation"));
            reservation.addProperty("reservation_id",UUID.randomUUID().toString());
            JsonObject intent=operation.getAsJsonObject("intent"); reservation.add("game_key",intent.get("game_key")); reservation.add("game_id",intent.get("game_id")); reservation.add("user_id",operation.get("target_user_id"));
            reservations.add(id,reservation);
        }
        reservation.addProperty("state",expected); reservation.addProperty("center_state",state);
        JsonObject token=operation.getAsJsonObject("followup").getAsJsonObject("token_finalize");
        token.addProperty("state",expected); token.add("reservation_id",reservation.get("reservation_id"));
    }
    /** Merge only already executed outbox receipts, never submit pending binds. */
    public void acknowledgeOnIo() {
        mirror.atomicOnIo(root -> {
            JsonObject operations=root.getAsJsonObject("local_authority").getAsJsonObject("operations");
            for (JsonElement item : operations.asMap().values()) {
                JsonObject operation=item.getAsJsonObject(); if (!text(operation,"state").equals("COMMITTED")) continue;
                JsonObject target=operation.getAsJsonObject("followup").getAsJsonObject("delivery").getAsJsonObject(scope.serverId());
                if (target==null) continue;
                JsonObject details=target.getAsJsonObject("effects");
                for (var entry : details.entrySet()) {
                    JsonObject ack=entry.getValue().getAsJsonObject(), task=root.getAsJsonObject("pending_effects").getAsJsonObject(OfficialMirrorModel.taskKey(text(operation,"operation_id"),entry.getKey()));
                    ack.addProperty("mirror","APPLIED");
                    for (String part : List.of("whitelist","online")) ack.add(part,task.get(part));
                    ack.add("effective_revision",root.getAsJsonObject("bindings").getAsJsonObject(entry.getKey()).get("revision"));
                }
                for (String part : OfficialCenterMutations.PARTS) {
                    Set<String> statuses=new HashSet<>(); for (JsonElement value : details.asMap().values()) statuses.add(text(value.getAsJsonObject(),part));
                    String aggregate="APPLIED"; for (String candidate : List.of("SUPERSEDED","PENDING","FAILED","CONFLICT")) if (statuses.contains(candidate)) aggregate=candidate;
                    target.addProperty(part,aggregate);
                }
                boolean complete=OfficialCenterMutations.PARTS.stream().allMatch(part -> Set.of("APPLIED","SUPERSEDED").contains(text(target,part)));
                operation.addProperty("result_code",complete ? "OK" : "SYNC_PENDING");
                if (complete) operation.add("next_attempt_at",JsonNull.INSTANCE);
            } return null;
        });
    }
}
