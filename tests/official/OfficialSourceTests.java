package com.poso.qqbind.official;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static com.poso.qqbind.official.OfficialCenterTests.*;

/** Synthetic credentials, fresh files and fake JDBC only. No real SDK/HTTP/DB. */
public final class OfficialSourceTests {
    static final String TOKEN="SYNTHETIC_NOT_A_REAL_CENTER_TOKEN", USER="SYNTHETIC_USER_A";
    static final String UNBIND="77777777-7777-4777-8777-777777777777";
    static class Provider implements OfficialMirrorAuthority.LeaseProvider {
        OfficialScope source=S; String revision="1",authority="center",token=TOKEN;
        boolean active=true,publish=true,forbidToken;
        int acquired,released,held,tokenReads;
        @Override public OfficialMirrorAuthority.Lease acquire(OfficialScope scope) {
            check(scope.equals(S)); acquired++; held++;
            return new OfficialMirrorAuthority.Lease() {
                public OfficialScope scope() { return source; }
                public String authority() { return authority; }
                public String configurationRevision() { return revision; }
                public boolean active() { return active; }
                public boolean mayPublishCommitted() { return publish; }
                public String bearerToken() { tokenReads++; if (forbidToken) throw new AssertionError("Snapshot must never request credentials"); return token; }
                public void close() { released++; held--; }
            };
        }
    }
    static JsonArray effects(String name) { return operation(name).getAsJsonArray("effects").deepCopy(); }
    static class Case implements AutoCloseable {
        final Provider provider=new Provider(); final AtomicLong nanos=new AtomicLong();
        final Path directory; final OfficialModState state; final OfficialMirrorAuthority source;
        Case() throws Exception { this(phase -> {}); }
        Case(OfficialModState.WriteHook hook) throws Exception {
            directory=Files.createTempDirectory("official-source-synthetic-"); state=new OfficialModState(directory,S,true,hook);
            source=new OfficialMirrorAuthority(S,state,provider,() -> true,nanos::get);
        }
        OfficialMirrorAuthority.Permit permit() { return source.authorize(S,"POST",OfficialMirrorAuthority.SYNC_PATH,"Bearer " + provider.token); }
        JsonObject sync(String operation,JsonArray effects) { return source.sync(permit(),operation,effects); }
        public void close() { source.close(); state.close(); }
    }
    static class SnapshotDb extends OfficialJdbcMutationTests.Db {
        Runnable afterRows=() -> {},beforeOperation=() -> {};
        SnapshotDb() {
            readOnly=true;
            for (JsonElement row : fixture.getAsJsonObject("mirror_snapshot").getAsJsonArray("operations")) operations.add(row.getAsJsonObject().get("operation_id").getAsString(),row.deepCopy());
            for (JsonElement value : fixture.getAsJsonObject("mirror_snapshot").getAsJsonArray("rows")) {
                JsonObject row=value.getAsJsonObject().deepCopy(); row.add("qq",row.remove("user_id")); bindings.add(row.get("game_key").getAsString(),row);
            }
        }
        @Override JsonArray query(String sql,List<Object> args) {
            if (sql.equals(OfficialJdbcSnapshot.SELECT)) {
                check(args.equals(List.of(S.environmentId(),S.groupId()))); JsonArray result=new JsonArray();
                for (String key : new TreeSet<>(rows.keySet())) {
                    JsonObject row=rows.getAsJsonObject(key).deepCopy();
                    if (!row.has("environment_id")) row.addProperty("environment_id",S.environmentId());
                    if (!row.has("group_id")) row.addProperty("group_id",S.groupId()); result.add(row);
                }
                afterRows.run(); return result;
            }
            if (sql.equals(OfficialJdbcCenter.LOOKUP)) beforeOperation.run();
            return super.query(sql,args);
        }
    }
    static JsonObject request(String operation,JsonArray effects) { JsonObject request=S.wire(); request.addProperty("operation_id",operation); request.add("effects",effects); return request; }
    static OfficialTokenApi api(Case c,boolean allowed) {
        OfficialTokenService tokens=new OfficialTokenService(S,c.state,id -> { throw new AssertionError("Sync must not query token center"); });
        return OfficialTokenApi.withAuthority(tokens,(scope,path) -> allowed,c.source);
    }
    static void unchanged(Case c,JsonObject before) { check(c.state.snapshot().equals(before)); check(c.provider.held == 0 && c.provider.acquired == c.provider.released); }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Explicit synthetic fixture path required");
        fixture=OfficialModState.parse(Files.readString(Path.of(args[0]))).getAsJsonObject(); check(fixture.get("synthetic").getAsBoolean()); passed=0;
        test("shared_snapshot_rows_and_committed_references_use_one_readonly_transaction",() -> {
            try (Case c=new Case()) {
                SnapshotDb db=new SnapshotDb(); c.provider.forbidToken=true;
                var snapshot=c.source.snapshots(db::open).load(); check(snapshot.rows().equals(fixture.getAsJsonObject("mirror_snapshot").get("rows")));
                check(db.opens == 1 && db.rollbacks == 1 && db.closes == 1 && db.commits == 0);
                check(db.calls.stream().allMatch(call -> call.sql().startsWith("SELECT ")));
                check(db.calls.stream().filter(call -> call.sql().equals(OfficialJdbcCenter.LOOKUP)).count() == 2);
                check(c.provider.tokenReads == 0 && c.provider.held == 0);
            }
        });
        test("verified_snapshot_merges_tombstone_and_one_to_many_active_index",() -> {
            try (Case c=new Case()) {
                c.sync(OP,effects("committed")); c.source.merge(c.source.snapshots(new SnapshotDb()::open).load());
                check(c.source.mirror().activeGames(USER).equals(Set.of("player_b")));
                JsonObject a=c.state.snapshot().getAsJsonObject("bindings").getAsJsonObject("player_a");
                check(a.get("state").getAsString().equals("revoked") && a.get("revision").getAsString().equals("2"));
            }
        });
        test("raw_json_sync_and_snapshot_cannot_bypass_source_confirmation",() -> {
            try (Case c=new Case()) {
                JsonObject before=c.state.snapshot();
                expect("UNAUTHORIZED",() -> c.source.mirror().sync(S,OP,effects("committed")));
                expect("UNAUTHORIZED",() -> c.source.mirror().mergeSnapshot(S,fixture.getAsJsonObject("mirror_snapshot").getAsJsonArray("rows")));
                unchanged(c,before); check(c.provider.acquired == 0);
            }
        });
        test("boolean_api_auth_flag_cannot_authorize_production_sync",() -> {
            try (Case c=new Case()) {
                OfficialTokenApi api=api(c,true); JsonObject before=c.state.snapshot();
                var response=api.dispatch("POST",OfficialMirrorAuthority.SYNC_PATH,request(OP,effects("committed")),true);
                check(response.status() == 401 && response.body().get("code").getAsString().equals("UNAUTHORIZED")); unchanged(c,before);
                response=api.dispatchSync("POST",OfficialMirrorAuthority.SYNC_PATH,request(OP,effects("committed")),"Bearer " + TOKEN);
                check(response.status() == 200 && response.body().get("success").getAsBoolean());
                check(response.body().getAsJsonObject("data").getAsJsonArray("results").get(0).getAsJsonObject().get("whitelist").getAsString().equals("PENDING"));
                check(api.dispatch("GET","/api/v2/binding/capabilities",S.wire(),true).body().get("code").getAsString().equals("NOT_SUPPORTED"));
            }
        });
        test("wrong_bearer_or_missing_explicit_publisher_permission_never_mutates",() -> {
            try (Case c=new Case()) {
                JsonObject before=c.state.snapshot();
                expect("UNAUTHORIZED",() -> c.source.authorize(S,"POST",OfficialMirrorAuthority.SYNC_PATH,"Bearer SYNTHETIC_WRONG_TOKEN"));
                c.provider.publish=false; int reads=c.provider.tokenReads;
                expect("UNAUTHORIZED",c::permit); check(c.provider.tokenReads == reads); unchanged(c,before);
                c.provider.forbidToken=true; c.source.merge(c.source.snapshots(new SnapshotDb()::open).load()); check(c.source.mirror().activeGames(USER).equals(Set.of("player_b")));
            }
        });
        test("foreign_scope_method_path_and_malformed_header_reject_before_settings",() -> {
            try (Case c=new Case()) {
                expect("SCOPE_MISMATCH",() -> c.source.authorize(new OfficialScope(S.environmentId(),"SYNTHETIC_OTHER_GROUP",S.serverId()),"POST",OfficialMirrorAuthority.SYNC_PATH,"Bearer " + TOKEN));
                expect("NOT_SUPPORTED",() -> c.source.authorize(S,"GET",OfficialMirrorAuthority.SYNC_PATH,"Bearer " + TOKEN));
                expect("NOT_SUPPORTED",() -> c.source.authorize(S,"POST","/api/sync_binding","Bearer " + TOKEN));
                expect("UNAUTHORIZED",() -> c.source.authorize(S,"POST",OfficialMirrorAuthority.SYNC_PATH,"Bearer bad\r\nheader"));
                check(c.provider.acquired == 0);
            }
        });
        test("weak_placeholder_or_disabled_source_cannot_issue_a_permit",() -> {
            try (Case c=new Case()) {
                for (String token : List.of("short","change-me-to-a-random-token")) { c.provider.token=token; expect("UNAUTHORIZED",c::permit); }
                c.provider.token=TOKEN; c.provider.active=false; expect("NOT_SUPPORTED",c::permit);
                c.provider.active=true; c.provider.authority="local"; expect("SCOPE_MISMATCH",c::permit);
                check(c.provider.held == 0);
            }
        });
        test("permit_is_single_use_owner_bound_and_expires",() -> {
            try (Case c=new Case(); Case other=new Case()) {
                var permit=c.permit(); expect("UNAUTHORIZED",() -> other.source.sync(permit,OP,effects("committed")));
                c.source.sync(permit,OP,effects("committed")); expect("UNAUTHORIZED",() -> c.source.sync(permit,OP,effects("committed")));
                var expired=c.permit(); c.nanos.addAndGet(OfficialMirrorAuthority.PERMIT_NANOS);
                expect("UNAUTHORIZED",() -> c.source.sync(expired,OP,effects("committed")));
                check(!permit.toString().contains(TOKEN) && !permit.toString().contains(USER));
            }
        });
        test("credential_rotation_revision_invalidates_prior_permit",() -> {
            try (Case c=new Case()) {
                var permit=c.permit(); JsonObject before=c.state.snapshot(); c.provider.revision="2";
                expect("UNAUTHORIZED",() -> c.source.sync(permit,OP,effects("committed"))); unchanged(c,before);
            }
        });
        test("configuration_lease_spans_atomic_mirror_persistence",() -> {
            AtomicReference<Case> current=new AtomicReference<>();
            try (Case c=new Case(phase -> { Case value=current.get(); if (value != null) check(value.provider.held > 0); })) {
                current.set(c); c.sync(OP,effects("committed")); check(c.provider.held == 0);
                c.source.merge(c.source.snapshots(new SnapshotDb()::open).load()); check(c.provider.held == 0);
            }
        });
        test("unknown_persistence_consumes_permit_and_releases_source_lease",() -> {
            AtomicBoolean fail=new AtomicBoolean();
            try (Case c=new Case(phase -> { if (fail.get() && phase.equals("after_replace")) throw new IOException("SYNTHETIC_PRIVATE_DISK"); })) {
                var permit=c.permit(); fail.set(true);
                expect("RESULT_UNKNOWN",() -> c.source.sync(permit,OP,effects("committed")));
                expect("UNAUTHORIZED",() -> c.source.sync(permit,OP,effects("committed"))); check(c.provider.held == 0);
            }
        });
        test("database_offline_does_not_block_authenticated_committed_downlink",() -> {
            try (Case c=new Case()) {
                AtomicInteger calls=new AtomicInteger(); var reader=c.source.snapshots(() -> { calls.incrementAndGet(); throw new java.sql.SQLException("SYNTHETIC_PRIVATE_OFFLINE"); });
                expect("DB_UNAVAILABLE",reader::load); int count=calls.get();
                c.sync(OP,effects("committed")); check(calls.get() == count && c.source.mirror().activeGames(USER).equals(Set.of("player_a")));
            }
        });
        test("snapshot_rejects_writable_autocommit_or_wrong_isolation_without_promoting",() -> {
            for (String reason : List.of("writable","autocommit","isolation")) {
                try (Case c=new Case()) {
                    SnapshotDb db=new SnapshotDb(); db.readOnly=!reason.equals("writable"); db.autoCommit=reason.equals("autocommit");
                    if (reason.equals("isolation")) db.isolation=java.sql.Connection.TRANSACTION_READ_COMMITTED;
                    expect("NOT_SUPPORTED",() -> c.source.snapshots(db::open).load()); check(db.calls.isEmpty() && db.closes == 1 && db.commits == 0);
                }
            }
        });
        test("schema_missing_operation_uncommitted_or_tampered_result_never_publishes_partial",() -> {
            for (String reason : List.of("schema","missing","uncommitted","effect","hash","scope")) {
                try (Case c=new Case()) {
                    SnapshotDb db=new SnapshotDb();
                    if (reason.equals("schema")) db.metadata.metadata.getAsJsonArray("tables").remove(0);
                    else if (reason.equals("missing")) db.operations.remove(UNBIND);
                    else if (reason.equals("uncommitted")) db.operations.add(UNBIND,operation("unbind_prepared"));
                    else if (reason.equals("effect")) db.bindings.getAsJsonObject("player_a").addProperty("qq","SYNTHETIC_OTHER_USER");
                    else if (reason.equals("hash")) db.operations.getAsJsonObject(UNBIND).addProperty("payload_hash","0".repeat(64));
                    else db.bindings.getAsJsonObject("player_a").addProperty("group_id","synthetic_group_a");
                    JsonObject before=c.state.snapshot(); expect(reason.equals("schema") ? "SCHEMA_MISMATCH" : "STORAGE_ERROR",() -> c.source.snapshots(db::open).load()); unchanged(c,before);
                    check(db.closes == 1 && db.commits == 0);
                }
            }
        });
        test("snapshot_reader_never_reconnects_between_rows_and_operation_reads",() -> {
            try (Case c=new Case()) {
                SnapshotDb db=new SnapshotDb(); db.afterRows=() -> { db.operations=new JsonObject(); db.bindings=new JsonObject(); };
                var snapshot=c.source.snapshots(db::open).load(); check(snapshot.rows().size() == 2 && db.opens == 1);
                c.source.merge(snapshot); check(c.source.mirror().activeGames(USER).equals(Set.of("player_b")));
            }
        });
        test("new_incremental_during_snapshot_load_survives_later_old_snapshot",() -> {
            try (Case c=new Case()) {
                SnapshotDb db=new SnapshotDb(); db.bindings=new JsonObject(); JsonObject row=effects("committed").get(0).getAsJsonObject(); row.add("qq",row.remove("user_id")); row.addProperty("last_operation_id",OP);
                db.bindings.add("player_a",row); db.operations=new JsonObject(); db.operations.add(OP,operation("committed"));
                db.beforeOperation=() -> c.sync(UNBIND,effects("unbind_committed"));
                var old=c.source.snapshots(db::open).load(); c.source.merge(old);
                JsonObject a=c.state.snapshot().getAsJsonObject("bindings").getAsJsonObject("player_a");
                check(a.get("state").getAsString().equals("revoked") && a.get("revision").getAsString().equals("2"));
            }
        });
        test("empty_snapshot_does_not_delete_published_incremental_rows",() -> {
            try (Case c=new Case()) {
                c.sync(OP,effects("committed")); SnapshotDb db=new SnapshotDb(); db.bindings=new JsonObject(); db.operations=new JsonObject();
                c.source.merge(c.source.snapshots(db::open).load()); check(c.source.mirror().activeGames(USER).equals(Set.of("player_a")));
            }
        });
        test("snapshot_cannot_cross_owner_revision_expiry_or_single_use_boundary",() -> {
            try (Case c=new Case(); Case other=new Case()) {
                var snapshot=c.source.snapshots(new SnapshotDb()::open).load(); expect("UNAUTHORIZED",() -> other.source.merge(snapshot));
                c.source.merge(snapshot); expect("UNAUTHORIZED",() -> c.source.merge(snapshot));
                var stale=c.source.snapshots(new SnapshotDb()::open).load(); c.provider.revision="2"; expect("UNAUTHORIZED",() -> c.source.merge(stale));
                SnapshotDb switched=new SnapshotDb(); switched.afterRows=() -> c.provider.revision="3";
                expect("UNAUTHORIZED",() -> c.source.snapshots(switched::open).load()); check(c.provider.held == 0);
                var expired=c.source.snapshots(new SnapshotDb()::open).load(); c.nanos.addAndGet(OfficialMirrorAuthority.SNAPSHOT_NANOS); expect("UNAUTHORIZED",() -> c.source.merge(expired));
            }
        });
        test("snapshot_rows_are_defensive_and_proof_strings_contain_no_records",() -> {
            try (Case c=new Case()) {
                var snapshot=c.source.snapshots(new SnapshotDb()::open).load(); snapshot.rows().get(0).getAsJsonObject().addProperty("revision","999");
                check(!snapshot.toString().contains(USER) && !snapshot.toString().contains(TOKEN)); c.source.merge(snapshot);
                check(c.state.snapshot().getAsJsonObject("bindings").getAsJsonObject("player_a").get("revision").getAsString().equals("2"));
            }
        });
        test("snapshot_deadline_and_row_capacity_fail_without_partial_publication",() -> {
            try (Case c=new Case()) {
                SnapshotDb db=new SnapshotDb(); db.afterRows=() -> c.nanos.addAndGet(OfficialJdbcSnapshot.READ_NANOS);
                JsonObject before=c.state.snapshot(); expect("DB_UNAVAILABLE",() -> c.source.snapshots(db::open).load()); unchanged(c,before);
            }
            try (Case c=new Case()) {
                SnapshotDb db=new SnapshotDb(); db.bindings=new JsonObject();
                for (int n=0;n<=OfficialJdbcSnapshot.MAX_ROWS;n++) {
                    String key=String.format(Locale.ROOT,"p%05d",n); JsonObject row=effects("committed").get(0).getAsJsonObject();
                    row.addProperty("game_key",key); row.addProperty("game_id",key); row.add("qq",row.remove("user_id")); row.addProperty("last_operation_id",OP); db.bindings.add(key,row);
                }
                JsonObject before=c.state.snapshot(); expect("LIMIT_EXCEEDED",() -> c.source.snapshots(db::open).load()); unchanged(c,before);
            }
        });
        test("closed_source_or_missing_provider_does_not_open_snapshot_connection",() -> {
            try (Case c=new Case()) {
                var reader=c.source.snapshots(() -> { throw new AssertionError("Closed source must not connect"); }); c.source.close(); expect("NOT_SUPPORTED",reader::load);
            }
            Path dir=Files.createTempDirectory("official-source-synthetic-");
            try (OfficialModState state=new OfficialModState(dir,S,true); OfficialMirrorAuthority source=new OfficialMirrorAuthority(S,state,null,() -> true)) {
                expect("NOT_SUPPORTED",() -> source.snapshots(() -> { throw new AssertionError("Missing provider must not connect"); }).load());
            }
        });
        test("raw_api_denial_or_extra_state_claim_cannot_bypass_exact_sync_contract",() -> {
            try (Case c=new Case()) {
                JsonObject before=c.state.snapshot(); var denied=api(c,false).dispatchSync("POST",OfficialMirrorAuthority.SYNC_PATH,request(OP,effects("committed")),"Bearer " + TOKEN);
                check(denied.status() == 401); JsonObject extra=request(OP,effects("committed")); extra.addProperty("state","COMMITTED");
                check(api(c,true).dispatchSync("POST",OfficialMirrorAuthority.SYNC_PATH,extra,"Bearer " + TOKEN).body().get("code").getAsString().equals("INVALID_INPUT")); unchanged(c,before);
            }
        });
        test("trusted_old_incremental_after_new_snapshot_is_ignored_with_new_revision_ack",() -> {
            try (Case c=new Case()) {
                c.source.merge(c.source.snapshots(new SnapshotDb()::open).load());
                JsonObject reply=c.sync(OP,effects("committed")); JsonObject row=reply.getAsJsonArray("results").get(0).getAsJsonObject();
                check(row.get("result").getAsString().equals("ignored") && row.get("effective_revision").getAsString().equals("2"));
                check(row.get("whitelist").getAsString().equals("SUPERSEDED"));
            }
        });
        System.out.println("Official sources: " + passed + " tests passed (synthetic auth/fake JDBC only)");
    }
}
