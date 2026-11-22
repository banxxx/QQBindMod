package com.poso.qqbind.official;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Center-authority transitions. No config, network, token-code or game access.
 * The store atomically commits BOTH tables and the fixed outbox. The policy
 * lease must keep actual authorization/configuration stable through commit.
 */
public final class OfficialCenterMutations {
    public enum Action { PREPARE, CLAIM_RESERVATION, RECORD_RESERVATION, COMMIT, ABORT }
    public interface PolicyLease extends AutoCloseable {
        String policyRevision();
        @Override void close();
    }
    public interface PolicyGuard { PolicyLease acquire(Action action, JsonObject normalizedIntent); }
    public interface Work<T> { T run(Transaction transaction); }
    public interface Store { <T> T transact(Work<T> work); }
    public interface Transaction {
        JsonObject operation(String operationId);
        JsonObject request(String environmentId, String requestKey);
        JsonObject generation(String environmentId, String serverId, String generation);
        Map<String,JsonObject> lockBindings(SortedSet<String> gameKeys);
        void insertOperation(JsonObject row);
        void updateOperation(JsonObject before, JsonObject after);
        void applyBinding(JsonObject before, JsonObject effect, String operationId, String now);
    }
    static final Set<String> TERMINAL = Set.of("COMMITTED","REJECTED","ABORTED");
    static final List<String> PARTS = List.of("mirror","whitelist","online");
    static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS",Locale.ROOT).withZone(ZoneOffset.UTC);
    private final OfficialScope scope;
    private final Store store;
    private final PolicyGuard guard;
    private final Clock clock;

    public OfficialCenterMutations(OfficialScope scope, Store store, PolicyGuard guard) {
        this(scope,store,guard,Clock.systemUTC());
    }
    OfficialCenterMutations(OfficialScope scope, Store store, PolicyGuard guard, Clock clock) {
        this.scope=Objects.requireNonNull(scope); this.store=Objects.requireNonNull(store);
        this.guard=guard; this.clock=Objects.requireNonNull(clock);
    }
    static String text(JsonObject value,String name) { return OfficialModState.string(value,name); }
    static boolean absent(JsonObject value,String name) { return value.has(name) && value.get(name).isJsonNull(); }
    static String json(JsonElement value) {
        String result=JSON.toJson(value);
        if (result.getBytes(StandardCharsets.UTF_8).length > OfficialIntent.MAX_BYTES) throw new OfficialException("LIMIT_EXCEEDED");
        return result;
    }
    private String now() { return TIME.format(clock.instant()); }
    private JsonObject normalized(JsonObject supplied) {
        JsonObject intent=OfficialIntent.validate(supplied);
        if (!text(intent,"environment_id").equals(scope.environmentId()) || !text(intent,"group_id").equals(scope.groupId())
                || text(intent,"kind").equals("bind") && !text(intent,"server_id").equals(scope.serverId())) throw new OfficialException("SCOPE_MISMATCH");
        return intent;
    }
    private interface Guarded<T> { T run(Transaction tx,String policyRevision); }
    private <T> T guarded(Action action,JsonObject intent,Guarded<T> work) {
        if (guard == null) throw new OfficialException("NOT_SUPPORTED");
        PolicyLease lease=null;
        try {
            try { lease=guard.acquire(action,intent.deepCopy()); }
            catch (OfficialException e) { throw e; }
            catch (Exception e) { throw new OfficialException("UNAUTHORIZED"); }
            if (lease == null) throw new OfficialException("UNAUTHORIZED");
            String revision=lease.policyRevision(); OfficialScope.revision(revision);
            PolicyLease current=lease;
            return store.transact(tx -> {
                T result=work.run(tx,revision);
                if (!revision.equals(current.policyRevision())) throw new OfficialException("UNAUTHORIZED");
                return result;
            });
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
        finally { if (lease != null) try { lease.close(); } catch (Exception ignored) { } }
    }
    private JsonObject operation(Transaction tx,String id,JsonObject intent) {
        JsonObject row=tx.operation(id);
        if (row == null) throw new OfficialException("SCOPE_MISMATCH");
        if (!text(row,"environment_id").equals(scope.environmentId()) || !text(row,"group_id").equals(scope.groupId())) throw new OfficialException("SCOPE_MISMATCH");
        if (!text(row,"payload_hash").equals(OfficialIntent.hash(intent))) throw new OfficialException("CONFLICT");
        return row;
    }
    static List<String> keys(JsonObject intent) {
        JsonObject body=intent.getAsJsonObject("intent"); List<String> keys=new ArrayList<>();
        if (text(intent,"kind").equals("bind")) keys.add(text(body,"game_key"));
        else for (JsonElement value : body.getAsJsonArray("targets")) keys.add(text(value.getAsJsonObject(),"game_key"));
        return keys;
    }
    static JsonObject delivery(JsonArray servers,JsonArray effects) {
        JsonObject result=new JsonObject();
        for (JsonElement server : servers) {
            JsonObject target=new JsonObject(), rows=new JsonObject();
            for (String part : PARTS) target.addProperty(part,"PENDING");
            for (JsonElement value : effects) {
                JsonObject effect=value.getAsJsonObject(), item=new JsonObject();
                item.add("requested_revision",effect.get("revision")); item.add("effective_revision",JsonNull.INSTANCE);
                for (String part : PARTS) item.addProperty(part,"PENDING");
                rows.add(text(effect,"game_key"),item);
            }
            target.add("effects",rows); result.add(server.getAsString(),target);
        }
        return result;
    }
    private static JsonObject followup() {
        JsonObject result=new JsonObject(); result.add("token_finalize",JsonNull.INSTANCE); result.add("delivery",new JsonObject()); return result;
    }
    private static JsonObject effect(String key,String game,String user,String state,long revision) {
        JsonObject effect=new JsonObject(); effect.addProperty("game_key",key); effect.addProperty("game_id",game);
        effect.addProperty("user_id",user); effect.addProperty("state",state); effect.addProperty("revision",Long.toString(revision)); return effect;
    }
    private static void capacity(JsonObject intent) {
        JsonArray effects=new JsonArray();
        for (String key : keys(intent)) effects.add(effect(key,"X".repeat(16),text(intent,"user_id"),"revoked",Long.MAX_VALUE));
        JsonObject delivery=delivery(intent.getAsJsonObject("intent").getAsJsonArray("target_server_ids"),effects);
        for (JsonElement value : delivery.asMap().values()) {
            JsonObject target=value.getAsJsonObject(); for (String part : PARTS) target.addProperty(part,"SUPERSEDED");
            for (JsonElement item : target.getAsJsonObject("effects").asMap().values()) {
                JsonObject row=item.getAsJsonObject(); row.addProperty("effective_revision",Long.toString(Long.MAX_VALUE));
                for (String part : PARTS) row.addProperty(part,"SUPERSEDED");
            }
        }
        JsonObject worst=new JsonObject(), pending=new JsonObject(), token=new JsonObject();
        token.addProperty("reservation_id","0".repeat(36)); token.addProperty("token_generation","0".repeat(36));
        token.addProperty("state","INVALIDATED"); token.addProperty("decision","COMMITTED");
        pending.add("delivery",delivery); pending.add("token_finalize",token); pending.addProperty("attempts",Long.toString(Long.MAX_VALUE));
        worst.add("effects",effects); worst.add("followup",pending); worst.add("nickname_basis",intent.get("nickname_basis")); json(worst);
    }

    public JsonObject prepare(String operationId,String requestKey,JsonObject supplied) {
        OfficialScope.uuid(operationId);
        if (requestKey == null || !requestKey.matches("[a-f0-9]{64}")) throw new OfficialException("INVALID_INPUT");
        JsonObject intent=normalized(supplied); capacity(intent); String digest=OfficialIntent.hash(intent);
        return guarded(Action.PREPARE,intent,(tx,policy) -> {
            if (!policy.equals(text(intent,"policy_revision"))) throw new OfficialException("CONFLICT");
            JsonObject same=tx.operation(operationId);
            if (same != null) {
                if (!text(same,"payload_hash").equals(digest) || !text(same,"request_key").equals(requestKey)) throw new OfficialException("CONFLICT");
                return same.deepCopy();
            }
            same=tx.request(scope.environmentId(),requestKey);
            if (same != null) {
                if (!text(same,"payload_hash").equals(digest)) throw new OfficialException("CONFLICT"); return same.deepCopy();
            }
            if (!absent(intent,"token_generation")) {
                same=tx.generation(scope.environmentId(),text(intent,"server_id"),text(intent,"token_generation"));
                if (same != null) {
                    if (!text(same,"payload_hash").equals(digest) || Set.of("ABORTED","REJECTED").contains(text(same,"state"))) throw new OfficialException("CONFLICT");
                    return same.deepCopy();
                }
            }
            JsonObject row=new JsonObject(); row.addProperty("operation_id",operationId); row.addProperty("request_key",requestKey); row.addProperty("payload_hash",digest);
            for (String name : List.of("environment_id","group_id","actor_kind","actor_id","token_generation","policy_revision","kind","intent","nickname_basis")) row.add(name,intent.get(name).deepCopy());
            row.add("target_user_id",intent.get("user_id").deepCopy()); row.add("original_server_id",intent.get("server_id").deepCopy()); row.add("reservation_id",JsonNull.INSTANCE);
            row.addProperty("state","PREPARED"); row.addProperty("result_code","PREPARED"); row.add("effects",new JsonArray()); row.add("followup",followup());
            String now=now(); row.add("next_attempt_at",JsonNull.INSTANCE); row.addProperty("created_at",now); row.addProperty("updated_at",now);
            tx.insertOperation(row.deepCopy()); return row.deepCopy();
        });
    }

    public boolean claimReservation(String operationId,JsonObject supplied) {
        OfficialScope.uuid(operationId); JsonObject intent=normalized(supplied);
        if (!text(intent,"kind").equals("bind")) throw new OfficialException("CONFLICT");
        return guarded(Action.CLAIM_RESERVATION,intent,(tx,policy) -> {
            JsonObject before=operation(tx,operationId,intent);
            if (!text(before,"state").equals("PREPARED") || before.getAsJsonObject("followup").has("token_reservation")) return false;
            if (!policy.equals(text(intent,"policy_revision"))) throw new OfficialException("CONFLICT");
            JsonObject next=before.deepCopy(), pending=new JsonObject(); pending.addProperty("state","PENDING");
            pending.add("token_generation",next.get("token_generation")); pending.add("reservation_id",JsonNull.INSTANCE);
            next.getAsJsonObject("followup").add("token_reservation",pending); String now=now();
            next.addProperty("updated_at",now); next.addProperty("next_attempt_at",now); tx.updateOperation(before,next); return true;
        });
    }

    public JsonObject recordReservation(String operationId,JsonObject supplied,String reservationId,String generation) {
        OfficialScope.uuid(operationId); OfficialScope.uuid(reservationId); OfficialScope.uuid(generation); JsonObject intent=normalized(supplied);
        if (!text(intent,"kind").equals("bind") || !generation.equals(text(intent,"token_generation"))) throw new OfficialException("CONFLICT");
        return guarded(Action.RECORD_RESERVATION,intent,(tx,policy) -> {
            JsonObject before=operation(tx,operationId,intent); String state=text(before,"state");
            if (Set.of("RESERVED","COMMITTED").contains(state)) {
                if (!text(before,"reservation_id").equals(reservationId)) throw new OfficialException("CONFLICT"); return before.deepCopy();
            }
            if (!state.equals("PREPARED")) throw new OfficialException("CONFLICT");
            JsonObject next=before.deepCopy(); next.addProperty("reservation_id",reservationId); next.addProperty("state","RESERVED");
            next.addProperty("result_code","RESERVED"); next.add("followup",followup()); String now=now();
            next.addProperty("updated_at",now); next.addProperty("next_attempt_at",now); tx.updateOperation(before,next); return next.deepCopy();
        });
    }

    private JsonObject terminal(Transaction tx,JsonObject before,String state,String code,String now) {
        JsonObject next=before.deepCopy(), followup=next.getAsJsonObject("followup"); followup.remove("token_reservation");
        if (!absent(before,"token_generation")) followup.add("token_finalize",token(before,"ABORTED"));
        next.addProperty("state",state); next.addProperty("result_code",code); next.addProperty("updated_at",now);
        next.add("next_attempt_at",absent(followup,"token_finalize") ? JsonNull.INSTANCE : new JsonPrimitive(now));
        tx.updateOperation(before,next); return next.deepCopy();
    }
    private static JsonObject token(JsonObject row,String decision) {
        JsonObject token=new JsonObject(); token.addProperty("state","PENDING"); token.addProperty("decision",decision);
        token.add("reservation_id",row.get("reservation_id").deepCopy()); token.add("token_generation",row.get("token_generation").deepCopy()); return token;
    }
    public JsonObject abort(String operationId,JsonObject supplied) {
        OfficialScope.uuid(operationId); JsonObject intent=normalized(supplied);
        return guarded(Action.ABORT,intent,(tx,policy) -> {
            JsonObject before=operation(tx,operationId,intent); if (TERMINAL.contains(text(before,"state"))) return before.deepCopy();
            return terminal(tx,before,"ABORTED","OK",now());
        });
    }

    public JsonObject commit(String operationId,JsonObject supplied) {
        OfficialScope.uuid(operationId); JsonObject intent=normalized(supplied);
        return guarded(Action.COMMIT,intent,(tx,policy) -> {
            JsonObject before=operation(tx,operationId,intent); String state=text(before,"state");
            if (TERMINAL.contains(state)) return before.deepCopy();
            boolean bind=text(intent,"kind").equals("bind");
            if (bind ? !state.equals("RESERVED") || absent(before,"reservation_id") : !state.equals("PREPARED")) throw new OfficialException("CONFLICT");
            String now=now(); if (!policy.equals(text(before,"policy_revision"))) return terminal(tx,before,"REJECTED","CONFLICT",now);
            SortedSet<String> lockKeys=new TreeSet<>(keys(intent));
            if (!absent(intent,"nickname_basis")) lockKeys.add(text(intent.getAsJsonObject("nickname_basis"),"game_key"));
            Map<String,JsonObject> locked=tx.lockBindings(Collections.unmodifiableSortedSet(lockKeys));
            if (!locked.keySet().equals(lockKeys)) throw new OfficialException("STORAGE_ERROR");
            JsonArray effects=new JsonArray(); String user=text(intent,"user_id"); JsonObject body=intent.getAsJsonObject("intent");
            if (bind) {
                if (!absent(intent,"nickname_basis")) {
                    JsonObject basis=intent.getAsJsonObject("nickname_basis"), row=locked.get(text(basis,"game_key"));
                    if (row == null || !text(row,"state").equals("active") || !text(row,"qq").equals(user)
                            || !text(row,"revision").equals(text(basis,"expected_revision"))) return terminal(tx,before,"REJECTED","CONFLICT",now);
                }
                String key=text(body,"game_key"); JsonObject row=locked.get(key);
                if (row != null && text(row,"state").equals("active")) return terminal(tx,before,"REJECTED",text(row,"qq").equals(user) ? "ALREADY_BOUND" : "CONFLICT",now);
                long revision=row == null ? 0 : OfficialScope.revision(text(row,"revision"));
                if (revision == Long.MAX_VALUE) return terminal(tx,before,"REJECTED","LIMIT_EXCEEDED",now);
                effects.add(effect(key,text(body,"game_id"),user,"active",revision+1));
            } else {
                for (JsonElement value : body.getAsJsonArray("targets")) {
                    JsonObject target=value.getAsJsonObject(), row=locked.get(text(target,"game_key"));
                    if (row == null || !text(row,"state").equals("active") || !text(row,"qq").equals(user)
                            || !text(row,"revision").equals(text(target,"expected_revision"))) return terminal(tx,before,"REJECTED","CONFLICT",now);
                    long revision=OfficialScope.revision(text(row,"revision"));
                    if (revision == Long.MAX_VALUE) return terminal(tx,before,"REJECTED","LIMIT_EXCEEDED",now);
                    effects.add(effect(text(row,"game_key"),text(row,"game_id"),user,"revoked",revision+1));
                }
            }
            JsonObject next=before.deepCopy(), followup=followup();
            if (bind) followup.add("token_finalize",token(before,"COMMITTED"));
            followup.add("delivery",delivery(body.getAsJsonArray("target_server_ids"),effects));
            JsonObject bounded=new JsonObject(); bounded.add("effects",effects); bounded.add("followup",followup); bounded.add("nickname_basis",next.get("nickname_basis")); json(bounded);
            for (JsonElement value : effects) { JsonObject effect=value.getAsJsonObject(); tx.applyBinding(locked.get(text(effect,"game_key")),effect.deepCopy(),operationId,now); }
            next.addProperty("state","COMMITTED"); next.add("effects",effects); next.add("followup",followup); next.addProperty("updated_at",now);
            boolean pending=bind || followup.getAsJsonObject("delivery").size() > 0;
            next.addProperty("result_code",pending ? "SYNC_PENDING" : "OK"); next.add("next_attempt_at",pending ? new JsonPrimitive(now) : JsonNull.INSTANCE);
            tx.updateOperation(before,next); return next.deepCopy();
        });
    }
}
