package com.poso.qqbind.official;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.poso.qqbind.official.OfficialCenterTests.*;

/** Synthetic transactional model + Python oracle. Not a JDBC SQL/row-lock test. */
public final class OfficialMutationTests {
    static final String OP_B="77777777-7777-4777-8777-777777777777", OP_C="88888888-8888-4888-8888-888888888888";
    static final String GENERATION="44444444-4444-4444-8444-444444444444", RESERVATION="99999999-9999-4999-8999-999999999999";
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"),ZoneOffset.UTC);
    static String str(JsonObject value,String field) { return OfficialModState.string(value,field); }
    static String uuid() { return UUID.randomUUID().toString(); }
    static JsonObject normalized(JsonObject row) {
        JsonObject result=new JsonObject();
        for (String field : List.of("environment_id","group_id","actor_kind","actor_id","token_generation","policy_revision","kind","intent","nickname_basis")) result.add(field,row.get(field).deepCopy());
        result.add("server_id",row.get("original_server_id").deepCopy()); result.add("user_id",row.get("target_user_id").deepCopy()); return result;
    }
    static JsonObject intent(String game,String user,String generation) {
        JsonObject value=fixture.getAsJsonArray("canonical_cases").get(0).getAsJsonObject().getAsJsonObject("intent").deepCopy();
        value.addProperty("actor_id",user); value.addProperty("user_id",user); value.addProperty("token_generation",generation);
        value.getAsJsonObject("intent").addProperty("game_id",game); value.getAsJsonObject("intent").addProperty("game_key",OfficialScope.key(game)); return value;
    }
    static JsonObject unbind(String user,String... games) {
        JsonObject value=normalized(operation("unbind_prepared")); value.addProperty("actor_id",user); value.addProperty("user_id",user);
        value.addProperty("kind",games.length == 1 ? "unbind_game" : "unbind_user");
        JsonArray targets=new JsonArray();
        for (String game : new TreeSet<>(List.of(games))) { JsonObject t=new JsonObject(); t.addProperty("game_key",game); t.addProperty("expected_revision","1"); targets.add(t); }
        value.getAsJsonObject("intent").add("targets",targets); return value;
    }
    static class Guard implements OfficialCenterMutations.PolicyGuard {
        String revision="1"; boolean allowed=true,held; int opens,closes; boolean closeFailure;
        @Override public OfficialCenterMutations.PolicyLease acquire(OfficialCenterMutations.Action action,JsonObject normalized) {
            if (!allowed) throw new OfficialException("UNAUTHORIZED"); check(!held); held=true; opens++; String captured=revision;
            return new OfficialCenterMutations.PolicyLease() {
                public String policyRevision() { return captured; }
                public void close() { held=false; closes++; if (closeFailure) throw new IllegalStateException("SYNTHETIC_PRIVATE_CLOSE"); }
            };
        }
    }
    static class Model implements OfficialCenterMutations.Store {
        JsonObject operations=new JsonObject(),bindings=new JsonObject(); int transactions,writes,failWrite;
        boolean beforeCommitFailure,afterCommitUnknown; Runnable onCommit=() -> {};
        final List<String> lockOrder=new ArrayList<>();
        final OfficialJdbcCenter validator=new OfficialJdbcCenter(S,() -> { throw new AssertionError("No real connection"); });
        @Override public synchronized <T> T transact(OfficialCenterMutations.Work<T> work) {
            transactions++; JsonObject ops=operations.deepCopy(), rows=bindings.deepCopy();
            T result=work.run(new OfficialCenterMutations.Transaction() {
                void write() { writes++; if (failWrite > 0 && writes == failWrite) throw new OfficialException("DB_UNAVAILABLE"); }
                JsonObject copy(JsonObject value) { return value == null ? null : value.deepCopy(); }
                public JsonObject operation(String id) { JsonObject row=ops.getAsJsonObject(id); return row == null ? null : validator.validate(row); }
                public JsonObject request(String environment,String key) {
                    for (JsonElement v : ops.asMap().values()) { JsonObject row=v.getAsJsonObject(); if (str(row,"environment_id").equals(environment) && str(row,"request_key").equals(key)) return copy(row); } return null;
                }
                public JsonObject generation(String environment,String server,String generation) {
                    for (JsonElement v : ops.asMap().values()) { JsonObject row=v.getAsJsonObject();
                        if (str(row,"environment_id").equals(environment) && !row.get("original_server_id").isJsonNull() && str(row,"original_server_id").equals(server) && str(row,"token_generation").equals(generation)) return copy(row);
                    } return null;
                }
                public Map<String,JsonObject> lockBindings(SortedSet<String> keys) {
                    Map<String,JsonObject> result=new TreeMap<>(); for (String key : keys) { lockOrder.add(key); result.put(key,copy(rows.getAsJsonObject(key))); } return result;
                }
                public void insertOperation(JsonObject row) {
                    validator.validate(row); if (ops.has(str(row,"operation_id")) || request(str(row,"environment_id"),str(row,"request_key")) != null) throw new OfficialException("CONFLICT");
                    write(); ops.add(str(row,"operation_id"),row.deepCopy());
                }
                public void updateOperation(JsonObject before,JsonObject after) {
                    check(before.equals(ops.get(str(before,"operation_id")))); validator.validate(after); write(); ops.add(str(before,"operation_id"),after.deepCopy());
                }
                public void applyBinding(JsonObject before,JsonObject effect,String operation,String now) {
                    String key=str(effect,"game_key"); check(Objects.equals(before,rows.get(key))); write(); JsonObject row=new JsonObject();
                    row.addProperty("id",before == null ? Integer.toString(rows.size()+1) : str(before,"id"));
                    for (String field : List.of("game_key","game_id","state","revision")) row.add(field,effect.get(field).deepCopy());
                    row.add("qq",effect.get("user_id").deepCopy()); row.addProperty("last_operation_id",operation);
                    row.addProperty("bind_time",before == null || str(effect,"state").equals("active") ? now : str(before,"bind_time")); row.addProperty("updated_at",now); rows.add(key,row);
                }
            });
            onCommit.run(); if (beforeCommitFailure) throw new OfficialException("DB_UNAVAILABLE");
            operations=ops; bindings=rows;
            if (afterCommitUnknown) { afterCommitUnknown=false; throw new OfficialException("RESULT_UNKNOWN"); }
            return result;
        }
        JsonObject op(String id) { return operations.getAsJsonObject(id).deepCopy(); }
        JsonObject binding(String game) { JsonObject row=bindings.getAsJsonObject(game); return row == null ? null : row.deepCopy(); }
        void seed(String key,String user,String state,String revision) {
            JsonObject row=new JsonObject(); row.addProperty("id",Integer.toString(bindings.size()+1)); row.addProperty("game_key",key); row.addProperty("game_id",key);
            row.addProperty("qq",user); row.addProperty("state",state); row.addProperty("revision",revision); row.addProperty("last_operation_id",OP);
            row.addProperty("bind_time","2026-11-18 00:00:00.000000"); row.addProperty("updated_at","2026-11-18 00:00:00.000000"); bindings.add(key,row);
        }
    }
    static class Case {
        final Model db=new Model(); final Guard guard=new Guard();
        OfficialCenterMutations service() { return new OfficialCenterMutations(S,db,guard,CLOCK); }
        JsonObject prepare(String id,JsonObject intent) { return service().prepare(id,OfficialIntent.hash(intent),intent); }
        JsonObject reserved(String id,JsonObject intent) {
            prepare(id,intent); service().claimReservation(id,intent); return service().recordReservation(id,intent,RESERVATION,str(intent,"token_generation"));
        }
        JsonObject bind(String id,JsonObject intent) { reserved(id,intent); return service().commit(id,intent); }
    }
    static void terminal(JsonObject row,String state,String code) { check(str(row,"state").equals(state) && str(row,"result_code").equals(code)); }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Explicit synthetic fixture path required");
        fixture=OfficialModState.parse(Files.readString(Path.of(args[0]))).getAsJsonObject(); check(fixture.get("synthetic").getAsBoolean()); passed=0;
        test("java_transitions_match_seven_python_persisted_snapshots",() -> {
            Case c=new Case(); JsonObject bind=normalized(operation("prepared")), unbind=normalized(operation("unbind_prepared"));
            check(c.service().prepare(OP,"a".repeat(64),bind).equals(operation("prepared")));
            check(c.service().claimReservation(OP,bind)); check(c.db.op(OP).equals(operation("reservation_pending")));
            check(c.service().recordReservation(OP,bind,RESERVATION,GENERATION).equals(operation("reserved")));
            check(c.service().commit(OP,bind).equals(operation("committed")));
            check(c.service().prepare(OP_B,"b".repeat(64),unbind).equals(operation("unbind_prepared")));
            check(c.service().commit(OP_B,unbind).equals(operation("unbind_committed")));
            JsonObject aborted=normalized(operation("aborted")); c.service().prepare(OP_C,"c".repeat(64),aborted);
            check(c.service().abort(OP_C,aborted).equals(operation("aborted")));
        });
        test("prepare_id_request_and_generation_reuse_original_operation",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION);
            c.service().prepare(OP,"a".repeat(64),i); check(str(c.service().prepare(OP_B,"a".repeat(64),i),"operation_id").equals(OP));
            check(str(c.service().prepare(OP_B,"b".repeat(64),i),"operation_id").equals(OP)); check(c.db.operations.size() == 1);
            JsonObject different=intent("Player_B","SYNTHETIC_USER_A",GENERATION);
            expect("CONFLICT",() -> c.service().prepare(OP,"a".repeat(64),different));
            expect("CONFLICT",() -> c.service().prepare(OP_B,"a".repeat(64),different));
        });
        test("durable_claim_once_across_service_restart_before_external_reserve",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION); c.prepare(OP,i);
            check(c.service().claimReservation(OP,i)); check(!c.service().claimReservation(OP,i)); check(c.db.operations.size() == 1);
            check(c.db.op(OP).getAsJsonObject("followup").has("token_reservation"));
        });
        test("commit_without_reservation_is_rejected_without_binding_or_outbox",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION); c.prepare(OP,i);
            expect("CONFLICT",() -> c.service().commit(OP,i)); check(c.db.bindings.size() == 0); terminal(c.db.op(OP),"PREPARED","PREPARED");
        });
        test("reservation_receipt_is_idempotent_and_cannot_change_id_or_generation",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION); JsonObject first=c.reserved(OP,i); int writes=c.db.writes;
            check(c.service().recordReservation(OP,i,RESERVATION,GENERATION).equals(first)); check(c.db.writes == writes);
            expect("CONFLICT",() -> c.service().recordReservation(OP,i,uuid(),GENERATION));
            expect("CONFLICT",() -> c.service().recordReservation(OP,i,RESERVATION,uuid())); check(c.db.op(OP).equals(first));
        });
        test("one_user_multiple_accounts_other_owner_cannot_overwrite",() -> {
            Case c=new Case(); c.bind(OP,intent("Player_A","SYNTHETIC_USER_A",GENERATION));
            c.bind(OP_B,intent("Player_B","SYNTHETIC_USER_A",uuid())); check(c.db.bindings.size() == 2);
            terminal(c.bind(OP_C,intent("Player_A","SYNTHETIC_USER_B",uuid())),"REJECTED","CONFLICT");
            check(str(c.db.binding("player_a"),"qq").equals("SYNTHETIC_USER_A"));
        });
        test("binding_own_active_account_preserves_existing_revision_and_finalizes_abort",() -> {
            Case c=new Case(); c.bind(OP,intent("Player_A","SYNTHETIC_USER_A",GENERATION));
            JsonObject result=c.bind(OP_B,intent("Player_A","SYNTHETIC_USER_A",uuid())); terminal(result,"REJECTED","ALREADY_BOUND");
            check(str(c.db.binding("player_a"),"revision").equals("1")); check(str(result.getAsJsonObject("followup").getAsJsonObject("token_finalize"),"decision").equals("ABORTED"));
        });
        test("revoke_one_preserves_other_and_rebind_uses_higher_revision",() -> {
            Case c=new Case(); c.bind(OP,intent("Player_A","SYNTHETIC_USER_A",GENERATION)); c.bind(OP_B,intent("Player_B","SYNTHETIC_USER_A",uuid()));
            JsonObject u=unbind("SYNTHETIC_USER_A","player_a"); c.prepare(OP_C,u); c.service().commit(OP_C,u);
            check(str(c.db.binding("player_b"),"state").equals("active")); check(str(c.db.binding("player_a"),"revision").equals("2"));
            c.bind(uuid(),intent("PLAYER_A","SYNTHETIC_USER_B",uuid())); check(str(c.db.binding("player_a"),"revision").equals("3"));
            check(str(c.db.binding("player_a"),"qq").equals("SYNTHETIC_USER_B"));
        });
        test("nickname_basis_revoked_changed_or_foreign_rejects_before_new_binding",() -> {
            for (String reason : List.of("revoked","revision","owner")) {
                Case c=new Case(); c.db.seed("player_a","SYNTHETIC_USER_A","active","1"); JsonObject i=intent("Player_Z","SYNTHETIC_USER_A",GENERATION), basis=new JsonObject();
                basis.addProperty("game_key","player_a"); basis.addProperty("expected_revision","1"); i.add("nickname_basis",basis); c.reserved(OP_B,i);
                JsonObject row=c.db.bindings.getAsJsonObject("player_a"); row.addProperty(reason.equals("revision") ? "revision" : reason.equals("owner") ? "qq" : "state",reason.equals("revision") ? "2" : reason.equals("owner") ? "SYNTHETIC_USER_B" : "revoked");
                terminal(c.service().commit(OP_B,i),"REJECTED","CONFLICT"); check(c.db.binding("player_z") == null);
                check(c.db.lockOrder.equals(List.of("player_a","player_z")));
            }
        });
        test("policy_revision_change_rejects_pending_bind_and_unbind",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION); c.reserved(OP,i); c.guard.revision="2";
            terminal(c.service().commit(OP,i),"REJECTED","CONFLICT"); check(c.db.bindings.size() == 0);
            c=new Case(); c.db.seed("player_a","SYNTHETIC_USER_A","active","1"); JsonObject u=unbind("SYNTHETIC_USER_A","player_a"); c.prepare(OP,u); c.guard.revision="2";
            terminal(c.service().commit(OP,u),"REJECTED","CONFLICT"); check(str(c.db.binding("player_a"),"state").equals("active"));
        });
        test("fixed_batch_checks_every_target_before_first_mutation",() -> {
            Case c=new Case(); c.db.seed("player_a","SYNTHETIC_USER_A","active","1"); c.db.seed("player_b","SYNTHETIC_USER_A","active","2");
            JsonObject u=unbind("SYNTHETIC_USER_A","player_a","player_b"); c.prepare(OP,u); JsonObject before=c.db.bindings.deepCopy();
            terminal(c.service().commit(OP,u),"REJECTED","CONFLICT"); check(c.db.bindings.equals(before));
        });
        test("batch_does_not_expand_to_later_account_and_payload_cannot_change",() -> {
            Case c=new Case(); c.db.seed("player_a","SYNTHETIC_USER_A","active","1"); JsonObject u=unbind("SYNTHETIC_USER_A","player_a"); c.prepare(OP,u);
            c.db.seed("player_b","SYNTHETIC_USER_A","active","1"); JsonObject expanded=unbind("SYNTHETIC_USER_A","player_a","player_b");
            expect("CONFLICT",() -> c.service().commit(OP,expanded)); c.service().commit(OP,u); check(str(c.db.binding("player_b"),"state").equals("active"));
        });
        test("two_table_failure_after_binding_write_rolls_back_both",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION); c.reserved(OP,i); JsonObject before=c.db.op(OP); c.db.failWrite=c.db.writes+2;
            expect("DB_UNAVAILABLE",() -> c.service().commit(OP,i)); check(c.db.bindings.size() == 0 && c.db.op(OP).equals(before));
            c.db.failWrite=0; c.service().commit(OP,i); check(c.db.bindings.size() == 1);
        });
        test("lost_commit_ack_recovers_original_success_without_reapplying_binding",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION); c.reserved(OP,i); c.db.afterCommitUnknown=true;
            expect("RESULT_UNKNOWN",() -> c.service().commit(OP,i)); terminal(c.db.op(OP),"COMMITTED","SYNC_PENDING"); int writes=c.db.writes;
            check(c.service().commit(OP,i).equals(c.db.op(OP))); check(c.db.writes == writes && str(c.db.binding("player_a"),"revision").equals("1"));
        });
        test("precommit_failure_keeps_reserved_operation_for_explicit_recovery",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION); c.reserved(OP,i); c.db.beforeCommitFailure=true;
            expect("DB_UNAVAILABLE",() -> c.service().commit(OP,i)); terminal(c.db.op(OP),"RESERVED","RESERVED"); check(c.db.bindings.size() == 0);
            c.db.beforeCommitFailure=false; terminal(c.service().commit(OP,i),"COMMITTED","SYNC_PENDING");
        });
        test("aborted_generation_cannot_be_reused_or_receive_late_reservation",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION); c.prepare(OP,i); c.service().claimReservation(OP,i); c.service().abort(OP,i);
            expect("CONFLICT",() -> c.service().prepare(OP_B,"b".repeat(64),i)); expect("CONFLICT",() -> c.service().recordReservation(OP,i,RESERVATION,GENERATION));
            terminal(c.db.op(OP),"ABORTED","OK"); check(c.db.bindings.size() == 0);
        });
        test("abort_and_commit_race_has_one_terminal_business_result",() -> {
            for (int n=0;n<8;n++) {
                Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION); c.reserved(OP,i);
                // Independent guards model each authenticated request; the DB model serializes transactions.
                var commit=new OfficialCenterMutations(S,c.db,new Guard(),CLOCK); var abort=new OfficialCenterMutations(S,c.db,new Guard(),CLOCK);
                ExecutorService pool=Executors.newFixedThreadPool(2);
                try {
                    CountDownLatch start=new CountDownLatch(1);
                    Future<JsonObject> a=pool.submit(() -> { start.await(); return commit.commit(OP,i); });
                    Future<JsonObject> b=pool.submit(() -> { start.await(); return abort.abort(OP,i); }); start.countDown();
                    JsonObject first=a.get(5,TimeUnit.SECONDS), second=b.get(5,TimeUnit.SECONDS); check(str(first,"state").equals(str(second,"state")));
                    check(str(c.db.op(OP),"state").equals("COMMITTED") ? c.db.binding("player_a") != null : c.db.binding("player_a") == null);
                } finally { pool.shutdownNow(); }
            }
        });
        test("revision_overflow_rejects_bind_and_unbind_without_wraparound",() -> {
            Case c=new Case(); c.db.seed("player_a","SYNTHETIC_USER_A","revoked",Long.toString(Long.MAX_VALUE));
            terminal(c.bind(OP_B,intent("Player_A","SYNTHETIC_USER_B",GENERATION)),"REJECTED","LIMIT_EXCEEDED");
            c=new Case(); c.db.seed("player_a","SYNTHETIC_USER_A","active",Long.toString(Long.MAX_VALUE)); JsonObject u=unbind("SYNTHETIC_USER_A","player_a");
            u.getAsJsonObject("intent").getAsJsonArray("targets").get(0).getAsJsonObject().addProperty("expected_revision",Long.toString(Long.MAX_VALUE)); c.prepare(OP_B,u);
            terminal(c.service().commit(OP_B,u),"REJECTED","LIMIT_EXCEEDED");
        });
        test("missing_guard_denied_actor_or_foreign_scope_never_opens_store",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION);
            expect("NOT_SUPPORTED",() -> new OfficialCenterMutations(S,c.db,null,CLOCK).prepare(OP,"a".repeat(64),i));
            c.guard.allowed=false; expect("UNAUTHORIZED",() -> c.prepare(OP,i)); check(c.db.transactions == 0);
            i.addProperty("group_id","SYNTHETIC_OTHER_GROUP"); expect("SCOPE_MISMATCH",() -> c.prepare(OP,i)); check(c.db.transactions == 0);
        });
        test("permission_and_store_exceptions_are_sanitized_and_release_policy_lease",() -> {
            Case c=new Case(); JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION);
            OfficialCenterMutations.PolicyGuard broken=(action,value) -> { throw new IllegalArgumentException("SYNTHETIC_PRIVATE_POLICY"); };
            expect("UNAUTHORIZED",() -> new OfficialCenterMutations(S,c.db,broken,CLOCK).prepare(OP,"a".repeat(64),i)); check(c.db.transactions == 0);
            c.db.onCommit=() -> { throw new IllegalStateException("SYNTHETIC_PRIVATE_STORAGE"); };
            expect("STORAGE_ERROR",() -> c.prepare(OP,i)); check(!c.guard.held && c.guard.opens == c.guard.closes);
        });
        test("policy_lease_is_held_through_commit_and_cleanup_cannot_mask_success",() -> {
            Case c=new Case(); c.db.onCommit=() -> check(c.guard.held); c.guard.closeFailure=true;
            JsonObject i=intent("Player_A","SYNTHETIC_USER_A",GENERATION); c.bind(OP,i); check(!c.guard.held && c.guard.opens == c.guard.closes);
            JsonObject result=c.service().commit(OP,i); result.getAsJsonArray("effects").get(0).getAsJsonObject().addProperty("revision","999");
            check(str(c.db.op(OP).getAsJsonArray("effects").get(0).getAsJsonObject(),"revision").equals("1"));
        });
        test("openid_case_is_not_folded_and_no_raw_code_is_persisted",() -> {
            Case c=new Case(); c.bind(OP,intent("Player_A","SYNTHETIC_USER_A",GENERATION));
            terminal(c.bind(OP_B,intent("Player_A","synthetic_user_a",uuid())),"REJECTED","CONFLICT");
            check(!c.db.operations.toString().contains("token_code"));
        });
        test("outbox_capacity_is_reserved_before_prepared_or_claim",() -> {
            Case c=new Case(); JsonObject u=unbind("SYNTHETIC_USER_A","player_a"); u.addProperty("kind","unbind_user");
            JsonArray targets=new JsonArray(), servers=new JsonArray();
            for (int n=0;n<500;n++) { JsonObject t=new JsonObject(); t.addProperty("game_key",String.format(Locale.ROOT,"player_%03d",n)); t.addProperty("expected_revision","1"); targets.add(t); }
            TreeSet<String> sorted=new TreeSet<>(); for(int n=0;n<100;n++) sorted.add(uuid()); sorted.forEach(servers::add);
            u.getAsJsonObject("intent").add("targets",targets); u.getAsJsonObject("intent").add("target_server_ids",servers);
            expect("LIMIT_EXCEEDED",() -> c.prepare(OP,u)); check(c.db.transactions == 0 && c.db.operations.size() == 0);
        });
        System.out.println("Official mutations: " + passed + " tests passed (model/fixtures, not real SQL)");
    }
}
