package com.poso.qqbind.official;

import com.google.gson.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static com.poso.qqbind.official.OfficialCenterTests.*;
import static com.poso.qqbind.official.OfficialMutationTests.*;

/** Explicit fake JDBC transaction journal. Never opens a driver or database.
 * This tests statement boundaries/parameters/recovery, NOT SQL engine semantics.
 */
public final class OfficialJdbcMutationTests {
    record Call(String sql,List<Object> values) { }
    static class Db {
        JsonObject operations=new JsonObject(),bindings=new JsonObject(),ops,rows;
        final OfficialCenterTests.Fake metadata=new OfficialCenterTests.Fake();
        final List<Call> calls=new ArrayList<>();
        boolean readOnly,autoCommit,loseCommitAck,failCommitBefore,failClose,zeroUpdate;
        int isolation=Connection.TRANSACTION_REPEATABLE_READ,opens,commits,rollbacks,closes;
        String failOn; int errno; Runnable onCommit=() -> {};
        Connection open() {
            opens++; ops=operations.deepCopy(); rows=bindings.deepCopy();
            return proxy(Connection.class,(p,m,a) -> switch(m.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "isReadOnly" -> readOnly;
                case "getTransactionIsolation" -> isolation;
                case "setAutoCommit" -> { autoCommit=(Boolean)a[0]; yield null; }
                case "setReadOnly" -> { readOnly=(Boolean)a[0]; yield null; }
                case "setTransactionIsolation" -> { isolation=(Integer)a[0]; yield null; }
                case "createStatement" -> statement(null);
                case "prepareStatement" -> statement((String)a[0]);
                case "commit" -> {
                    commits++; onCommit.run();
                    if (failCommitBefore) throw new SQLException("SYNTHETIC_PRIVATE_BEFORE_ACK","synthetic",0);
                    operations=ops.deepCopy(); bindings=rows.deepCopy();
                    if (loseCommitAck) { loseCommitAck=false; throw new SQLException("SYNTHETIC_PRIVATE_ACK_LOST","synthetic",0); }
                    yield null;
                }
                case "rollback" -> { rollbacks++; ops=operations.deepCopy(); rows=bindings.deepCopy(); yield null; }
                case "close" -> { closes++; if (failClose) throw new SQLException("SYNTHETIC_PRIVATE_CLOSE"); yield null; }
                default -> unused(m);
            });
        }
        Object statement(String prepared) {
            Map<Integer,Object> values=new TreeMap<>();
            InvocationHandler handler=(p,m,a) -> {
                switch(m.getName()) {
                    case "setQueryTimeout": check(a[0].equals(5)); return null;
                    case "setMaxRows": check((Integer)a[0] > 0); return null;
                    case "setString": case "setLong": values.put((Integer)a[0],a[1]); return null;
                    case "setNull": values.put((Integer)a[0],null); return null;
                    case "close": return null;
                    case "execute": case "executeUpdate": case "executeQuery":
                        String sql=prepared == null ? (String)a[0] : prepared;
                        check(values.isEmpty() || values.keySet().equals(new TreeSet<>(java.util.stream.IntStream.rangeClosed(1,values.size()).boxed().toList())));
                        List<Object> args=new ArrayList<>(values.values()); calls.add(new Call(sql,args));
                        if (failOn != null && sql.contains(failOn)) throw new SQLException("SYNTHETIC_PRIVATE_SQL","synthetic",errno);
                        if (m.getName().equals("execute")) { check(sql.startsWith("SET SESSION ")); return false; }
                        if (m.getName().equals("executeUpdate")) return write(sql,args);
                        return metadata.result(query(sql,args));
                    default: return unused(m);
                }
            };
            return prepared == null ? proxy(Statement.class,handler) : proxy(PreparedStatement.class,handler);
        }
        JsonArray query(String sql,List<Object> args) {
            JsonArray result=new JsonArray();
            if (sql.equals(OfficialCenterSchema.SESSION_SQL)) { result.add(metadata.metadata.get("session")); return result; }
            if (sql.equals(OfficialCenterSchema.TABLE_SQL)) return metadata.metadata.getAsJsonArray("tables");
            if (sql.equals(OfficialCenterSchema.COLUMN_SQL)) return metadata.metadata.getAsJsonArray("columns");
            if (sql.equals(OfficialCenterSchema.INDEX_SQL)) return metadata.metadata.getAsJsonArray("indexes");
            if (sql.startsWith("EXPLAIN ") || sql.endsWith(" WHERE 1=0")) return result;
            if (sql.equals(OfficialJdbcTransactions.OP_SELECT) || sql.equals(OfficialJdbcCenter.LOOKUP)) {
                JsonObject row=ops.getAsJsonObject((String)args.get(0));
                if (row != null && (args.size() == 1 || str(row,"environment_id").equals(args.get(1)) && str(row,"group_id").equals(args.get(2)))) result.add(row); return result;
            }
            if (sql.equals(OfficialJdbcTransactions.REQUEST_SELECT) || sql.equals(OfficialJdbcTransactions.GENERATION_SELECT)) {
                for (JsonElement item : ops.asMap().values()) {
                    JsonObject row=item.getAsJsonObject(); boolean match=str(row,"environment_id").equals(args.get(0));
                    match &= args.size() == 2 ? str(row,"request_key").equals(args.get(1))
                        : !row.get("original_server_id").isJsonNull() && str(row,"original_server_id").equals(args.get(1)) && str(row,"token_generation").equals(args.get(2));
                    if (match) { JsonObject found=new JsonObject(); found.add("operation_id",row.get("operation_id")); result.add(found); }
                }
                return result;
            }
            if (sql.equals(OfficialJdbcTransactions.BIND_SELECT)) {
                check(args.get(0).equals(S.environmentId()) && args.get(1).equals(S.groupId())); JsonObject row=rows.getAsJsonObject((String)args.get(2));
                if (row != null) result.add(row); return result;
            }
            throw new AssertionError("Unexpected synthetic SQL");
        }
        JsonElement value(Object value,boolean json) {
            return value == null ? JsonNull.INSTANCE : json ? JsonParser.parseString((String)value) : new JsonPrimitive(value.toString());
        }
        int write(String sql,List<Object> args) throws SQLException {
            if (zeroUpdate) { zeroUpdate=false; return 0; }
            if (sql.equals(OfficialJdbcTransactions.OP_INSERT)) {
                check(args.size() == 22); JsonObject row=new JsonObject(); String[] fields=OfficialJdbcTransactions.OP_FIELDS.split(",");
                for (int n=0;n<fields.length;n++) row.add(fields[n],value(args.get(n),Set.of("intent","nickname_basis","effects","followup").contains(fields[n])));
                if (ops.has(str(row,"operation_id"))) throw new SQLException("SYNTHETIC_PRIVATE_DUPLICATE","synthetic",1062);
                ops.add(str(row,"operation_id"),row); return 1;
            }
            if (sql.equals(OfficialJdbcTransactions.OP_UPDATE)) {
                check(args.size() == 11); JsonObject row=ops.getAsJsonObject((String)args.get(7));
                if (row == null || !str(row,"state").equals(args.get(10)) || !str(row,"environment_id").equals(args.get(8)) || !str(row,"group_id").equals(args.get(9))) return 0;
                String[] fields={"reservation_id","state","result_code","effects","followup","next_attempt_at","updated_at"};
                for (int n=0;n<fields.length;n++) row.add(fields[n],value(args.get(n),n == 3 || n == 4)); return 1;
            }
            if (sql.equals(OfficialJdbcTransactions.BIND_INSERT)) {
                check(args.size() == 10 && args.get(0).equals(S.environmentId()) && args.get(1).equals(S.groupId()));
                String key=(String)args.get(2); if (rows.has(key)) throw new SQLException("SYNTHETIC_PRIVATE_UNIQUE","synthetic",1062);
                JsonObject row=new JsonObject(); String[] fields={"game_key","game_id","qq","state","revision","last_operation_id","bind_time","updated_at"};
                row.addProperty("id",Integer.toString(rows.size()+1)); for(int n=0;n<fields.length;n++) row.add(fields[n],value(args.get(n+2),false)); rows.add(key,row); return 1;
            }
            check(sql.equals(OfficialJdbcTransactions.REBIND) || sql.equals(OfficialJdbcTransactions.REVOKE)); boolean active=sql.equals(OfficialJdbcTransactions.REBIND);
            check(args.size() == (active ? 11 : 8)); int id=active ? 6 : 3;
            for (JsonElement item : rows.asMap().values()) {
                JsonObject row=item.getAsJsonObject();
                if (!str(row,"id").equals(args.get(id)) || !str(row,"qq").equals(args.get(id+3))
                        || !str(row,"revision").equals(args.get(id+4).toString()) || !str(row,"state").equals(active ? "revoked" : "active")) continue;
                check(args.get(id+1).equals(S.environmentId()) && args.get(id+2).equals(S.groupId()));
                if (active) { row.add("game_id",value(args.get(0),false)); row.add("qq",value(args.get(1),false)); row.add("bind_time",value(args.get(4),false)); }
                row.addProperty("state",active ? "active" : "revoked"); row.add("revision",value(args.get(active ? 2 : 0),false));
                row.add("last_operation_id",value(args.get(active ? 3 : 1),false)); row.add("updated_at",value(args.get(active ? 5 : 2),false)); return 1;
            }
            return 0;
        }
        OfficialCenterMutations service(OfficialMutationTests.Guard guard) { return new OfficialCenterMutations(S,new OfficialJdbcTransactions(S,this::open),guard,CLOCK); }
        Driver driver() { return proxy(Driver.class,(p,m,a) -> { check(m.getName().equals("connect")); return open(); }); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Explicit synthetic fixture path required");
        fixture=OfficialModState.parse(Files.readString(Path.of(args[0]))).getAsJsonObject(); check(fixture.get("synthetic").getAsBoolean()); passed=0;
        test("jdbc_mutations_match_python_lifecycle_and_persist_fixed_outbox",() -> {
            Db db=new Db(); Guard guard=new Guard(); JsonObject i=normalized(operation("prepared")); var service=db.service(guard);
            check(service.prepare(OP,"a".repeat(64),i).equals(operation("prepared"))); check(service.claimReservation(OP,i));
            check(service.recordReservation(OP,i,RESERVATION,GENERATION).equals(operation("reserved")));
            check(service.commit(OP,i).equals(operation("committed"))); check(db.bindings.size() == 1);
            JsonObject u=normalized(operation("unbind_prepared")); service.prepare(OP_B,"b".repeat(64),u);
            check(service.commit(OP_B,u).equals(operation("unbind_committed"))); check(str(db.bindings.getAsJsonObject("player_a"),"state").equals("revoked"));
            check(db.opens == db.commits && db.opens == db.closes);
        });
        test("jdbc_unknown_commit_is_not_replayed_and_original_query_confirms_success",() -> {
            Db db=new Db(); db.operations.add(OP,operation("reserved")); db.loseCommitAck=true; JsonObject i=normalized(operation("prepared"));
            expect("RESULT_UNKNOWN",() -> db.service(new Guard()).commit(OP,i)); check(db.opens == 1 && db.commits == 1 && db.bindings.size() == 1);
            int writes=(int)db.calls.stream().filter(c -> c.sql.startsWith("INSERT ")).count();
            JsonObject original=new OfficialJdbcCenter(S,db::open).readOperation(OP); check(str(original,"state").equals("COMMITTED"));
            check(db.calls.stream().filter(c -> c.sql.startsWith("INSERT ")).count() == writes);
        });
        test("jdbc_before_ack_failure_is_unknown_until_original_operation_is_queried",() -> {
            Db db=new Db(); db.operations.add(OP,operation("reserved")); db.failCommitBefore=true;
            expect("RESULT_UNKNOWN",() -> db.service(new Guard()).commit(OP,normalized(operation("prepared"))));
            check(db.bindings.size() == 0 && str(db.operations.getAsJsonObject(OP),"state").equals("RESERVED")); check(db.commits == 1 && db.opens == 1);
        });
        test("jdbc_statement_failure_rolls_back_binding_and_operation_together",() -> {
            Db db=new Db(); db.operations.add(OP,operation("reserved")); db.failOn="UPDATE bindings_qqofficial_ops"; db.errno=0;
            expect("DB_UNAVAILABLE",() -> db.service(new Guard()).commit(OP,normalized(operation("prepared"))));
            check(db.bindings.size() == 0 && str(db.operations.getAsJsonObject(OP),"state").equals("RESERVED")); check(db.commits == 0 && db.rollbacks == 1 && db.closes == 1);
        });
        test("jdbc_schema_or_privilege_preflight_prevents_business_queries",() -> {
            for (String reason : List.of("schema","privilege","unsupported")) {
                Db db=new Db();
                if (reason.equals("schema")) db.metadata.metadata.getAsJsonArray("columns").remove(0);
                else { db.failOn="EXPLAIN INSERT"; db.errno=reason.equals("privilege") ? 1142 : 1235; }
                expect(reason.equals("schema") ? "SCHEMA_MISMATCH" : reason.equals("privilege") ? "UNAUTHORIZED" : "NOT_SUPPORTED",
                    () -> db.service(new Guard()).prepare(OP,"a".repeat(64),normalized(operation("prepared"))));
                check(db.calls.stream().noneMatch(c -> c.sql.equals(OfficialJdbcTransactions.OP_SELECT))); check(db.commits == 0);
            }
        });
        test("jdbc_readonly_autocommit_or_wrong_isolation_is_never_promoted",() -> {
            for (String reason : List.of("readonly","autocommit","isolation")) {
                Db db=new Db(); db.readOnly=reason.equals("readonly"); db.autoCommit=reason.equals("autocommit");
                if (reason.equals("isolation")) db.isolation=Connection.TRANSACTION_READ_COMMITTED;
                expect("NOT_SUPPORTED",() -> db.service(new Guard()).prepare(OP,"a".repeat(64),normalized(operation("prepared"))));
                check(db.calls.isEmpty() && db.commits == 0);
            }
        });
        test("jdbc_explicit_write_factory_preserves_default_readonly_boundary",() -> {
            Db db=new Db(); var read=new OfficialJdbcConnections(db.driver(),OfficialCenterTests::settings,() -> true);
            Connection connection=read.open(); check(connection.isReadOnly()); connection.close();
            var write=new OfficialJdbcConnections(db.driver(),OfficialCenterTests::settings,() -> true,OfficialJdbcConnections.Access.READ_WRITE);
            connection=write.open(); check(!connection.isReadOnly() && !connection.getAutoCommit()); connection.close();
        });
        test("jdbc_uniqueness_deadlock_and_zero_row_cas_return_conflict_without_retry",() -> {
            for (int errno : List.of(1062,1213,1205,9007)) {
                Db db=new Db(); db.failOn="INSERT INTO bindings_qqofficial_ops"; db.errno=errno;
                expect("CONFLICT",() -> db.service(new Guard()).prepare(OP,"a".repeat(64),normalized(operation("prepared")))); check(db.opens == 1 && db.operations.size() == 0);
            }
            Db db=new Db(); db.operations.add(OP,operation("reserved")); db.zeroUpdate=true;
            expect("CONFLICT",() -> db.service(new Guard()).commit(OP,normalized(operation("prepared")))); check(db.bindings.size() == 0 && db.commits == 0);
        });
        test("jdbc_locks_are_sorted_and_predicates_preserve_owner_version_and_scope",() -> {
            Db db=new Db(); Model seed=new Model(); seed.seed("player_a","SYNTHETIC_USER_A","active","1"); seed.seed("player_b","SYNTHETIC_USER_A","active","1"); db.bindings=seed.bindings.deepCopy();
            JsonObject u=unbind("SYNTHETIC_USER_A","player_b","player_a"); var service=db.service(new Guard()); service.prepare(OP,"a".repeat(64),u); service.commit(OP,u);
            List<String> keys=db.calls.stream().filter(c -> c.sql.equals(OfficialJdbcTransactions.BIND_SELECT)).map(c -> c.values.get(2).toString()).toList(); check(keys.equals(List.of("player_a","player_b")));
            check(db.calls.stream().filter(c -> c.sql.equals(OfficialJdbcTransactions.REVOKE)).allMatch(c -> c.values.size() == 8 && c.values.get(5).equals(S.groupId()) && c.values.get(6).equals("SYNTHETIC_USER_A") && c.values.get(7).equals(1L)));
            check(db.calls.stream().filter(c -> c.sql.startsWith("SELECT ") && c.sql.contains(" WHERE operation_id=")).allMatch(c -> c.sql.endsWith(" FOR UPDATE")));
        });
        test("jdbc_rebind_requires_revoked_old_row_and_retains_revision",() -> {
            Db db=new Db(); Model seed=new Model(); seed.seed("player_a","SYNTHETIC_USER_B","revoked","2"); db.bindings=seed.bindings.deepCopy(); db.operations.add(OP,operation("reserved"));
            db.service(new Guard()).commit(OP,normalized(operation("prepared"))); check(str(db.bindings.getAsJsonObject("player_a"),"revision").equals("3"));
            check(db.calls.stream().anyMatch(c -> c.sql.equals(OfficialJdbcTransactions.REBIND) && c.values.get(8).equals(S.groupId()) && c.values.get(9).equals("SYNTHETIC_USER_B") && c.values.get(10).equals(2L)));
        });
        test("jdbc_parameters_do_not_interpolate_identity_or_json_into_sql",() -> {
            Db db=new Db(); JsonObject i=intent("Player_A","SYNTHETIC_'_USER",GENERATION); db.service(new Guard()).prepare(OP,"a".repeat(64),i);
            check(db.calls.stream().noneMatch(c -> c.sql.contains("SYNTHETIC_'_USER")));
            check(db.calls.stream().anyMatch(c -> c.sql.equals(OfficialJdbcTransactions.OP_INSERT) && c.values.contains("SYNTHETIC_'_USER")));
        });
        test("jdbc_corrupt_center_row_or_binding_never_gets_overwritten",() -> {
            Db db=new Db(); JsonObject row=operation("reserved"); row.addProperty("payload_hash","0".repeat(64)); db.operations.add(OP,row);
            expect("STORAGE_ERROR",() -> db.service(new Guard()).commit(OP,normalized(operation("prepared")))); check(db.calls.stream().noneMatch(c -> c.sql.startsWith("UPDATE ")));
            Db broken=new Db(); broken.operations.add(OP,operation("reserved")); Model seed=new Model(); seed.seed("player_a","SYNTHETIC_USER_A","revoked","1"); broken.bindings=seed.bindings.deepCopy();
            broken.bindings.getAsJsonObject("player_a").addProperty("game_id","Other_Player"); expect("STORAGE_ERROR",() -> broken.service(new Guard()).commit(OP,normalized(operation("prepared"))));
            check(broken.commits == 0);
        });
        test("jdbc_guard_lease_lives_through_commit_and_close_error_cannot_mask_success",() -> {
            Db db=new Db(); Guard guard=new Guard(); db.operations.add(OP,operation("reserved")); db.onCommit=() -> check(guard.held); db.failClose=true;
            terminal(db.service(guard).commit(OP,normalized(operation("prepared"))),"COMMITTED","SYNC_PENDING"); check(!guard.held && db.closes == 1);
        });
        System.out.println("Official JDBC mutations: " + passed + " tests passed (fake JDBC, not MySQL/TiDB)");
    }
}
