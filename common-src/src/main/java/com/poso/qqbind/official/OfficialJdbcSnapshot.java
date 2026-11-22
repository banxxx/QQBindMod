package com.poso.qqbind.official;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One bounded RR read transaction for rows AND their committed operations.
 * No lock/IO on the game thread, partial snapshot publication, DELETE or retry.
 */
public final class OfficialJdbcSnapshot {
    static final int MAX_ROWS=10000, MAX_BYTES=8*1024*1024;
    static final long READ_NANOS=30_000_000_000L;
    static final String ROW_FIELDS="environment_id,group_id,game_key,game_id,qq,state,revision,last_operation_id";
    static final String SELECT="SELECT " + ROW_FIELDS + " FROM bindings_qqofficial WHERE environment_id=? AND group_id=? ORDER BY game_key LIMIT 10001";
    /** Data can be inspected by the host, but never minted from incoming JSON. */
    public static final class Snapshot {
        private final OfficialMirrorAuthority owner;
        private final String revision;
        private final JsonArray rows;
        private final long issued;
        private final AtomicBoolean used=new AtomicBoolean();
        private Snapshot(OfficialMirrorAuthority owner,String revision,JsonArray rows,long issued) {
            this.owner=owner; this.revision=revision; this.rows=rows.deepCopy(); this.issued=issued;
        }
        public JsonArray rows() { return rows.deepCopy(); }
        String revision() { return revision; }
        JsonArray claim(OfficialMirrorAuthority expected,long now) {
            if (owner != expected || !used.compareAndSet(false,true) || OfficialMirrorAuthority.expired(issued,now,OfficialMirrorAuthority.SNAPSHOT_NANOS)) throw new OfficialException("UNAUTHORIZED");
            return rows.deepCopy();
        }
        @Override public String toString() { return "OfficialCenterSnapshot[opaque]"; }
    }
    private final OfficialMirrorAuthority authority;
    private final OfficialJdbcCenter.Connections connections;
    OfficialJdbcSnapshot(OfficialMirrorAuthority authority,OfficialJdbcCenter.Connections connections) {
        this.authority=Objects.requireNonNull(authority); this.connections=Objects.requireNonNull(connections);
    }
    private static String text(JsonObject value,String name) { return OfficialModState.string(value,name); }
    private JsonObject row(JsonObject raw,OfficialScope scope) {
        try {
            if (!text(raw,"environment_id").equals(scope.environmentId()) || !text(raw,"group_id").equals(scope.groupId())) throw new IllegalArgumentException();
            JsonObject effect=new JsonObject();
            for (String field : List.of("game_key","game_id","state","revision")) effect.add(field,raw.get(field)); effect.add("user_id",raw.get("qq"));
            effect=OfficialMirrorModel.effect(effect); effect.addProperty("last_operation_id",OfficialScope.uuid(text(raw,"last_operation_id"))); return effect;
        } catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
    }
    private void check(OfficialMirrorAuthority.Lease lease,String revision,long started) {
        authority.check(lease,revision,false);
        if (OfficialMirrorAuthority.expired(started,authority.now(),READ_NANOS)) throw new OfficialException("DB_UNAVAILABLE");
    }
    public Snapshot load() {
        OfficialMirrorAuthority.Lease lease=authority.acquire(); Connection connection=null;
        try {
            String revision=lease.configurationRevision(); long started=authority.now(); OfficialScope scope=authority.scope();
            check(lease,revision,started); connection=connections.open(); check(lease,revision,started);
            if (connection == null || !connection.isReadOnly() || connection.getAutoCommit()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) throw new OfficialException("NOT_SUPPORTED");
            OfficialCenterSchema.inspect(connection); check(lease,revision,started);
            JsonArray rows=new JsonArray(); Map<String,Map<String,JsonObject>> references=new TreeMap<>(); int bytes=2;
            try (PreparedStatement statement=connection.prepareStatement(SELECT)) {
                statement.setQueryTimeout(5); statement.setMaxRows(MAX_ROWS+1); statement.setString(1,scope.environmentId()); statement.setString(2,scope.groupId());
                try (ResultSet result=statement.executeQuery()) {
                    String previous="";
                    while (result.next()) {
                        check(lease,revision,started); if (rows.size() == MAX_ROWS) throw new OfficialException("LIMIT_EXCEEDED");
                        JsonObject row=row(OfficialJdbcCenter.readRow(result,ROW_FIELDS),scope), effect=OfficialMirrorModel.project(row);
                        String key=text(effect,"game_key"), operation=text(row,"last_operation_id");
                        if (key.compareTo(previous) <= 0) throw new OfficialException("STORAGE_ERROR"); previous=key;
                        bytes+=OfficialCenterMutations.JSON.toJson(row).getBytes(StandardCharsets.UTF_8).length+1;
                        if (bytes > MAX_BYTES) throw new OfficialException("LIMIT_EXCEEDED"); rows.add(row);
                        references.computeIfAbsent(operation,k -> new TreeMap<>()).put(key,effect);
                    }
                }
            }
            OfficialJdbcCenter validator=new OfficialJdbcCenter(scope,connections);
            // The SAME connection/transaction is essential; readOperation() would
            // open a new connection and could join incompatible snapshot versions.
            for (var reference : references.entrySet()) {
                check(lease,revision,started);
                try (PreparedStatement statement=connection.prepareStatement(OfficialJdbcCenter.LOOKUP)) {
                    statement.setQueryTimeout(5); statement.setMaxRows(2); statement.setString(1,reference.getKey());
                    statement.setString(2,scope.environmentId()); statement.setString(3,scope.groupId());
                    try (ResultSet result=statement.executeQuery()) {
                        if (!result.next()) throw new OfficialException("STORAGE_ERROR");
                        JsonObject operation=validator.validate(OfficialJdbcCenter.readRow(result,OfficialJdbcCenter.FIELDS));
                        if (result.next() || !text(operation,"operation_id").equals(reference.getKey()) || !text(operation,"state").equals("COMMITTED")) throw new OfficialException("STORAGE_ERROR");
                        JsonObject fixed=OfficialMirrorModel.indexed(operation.getAsJsonArray("effects"));
                        for (var effect : reference.getValue().entrySet()) if (!effect.getValue().equals(fixed.get(effect.getKey()))) throw new OfficialException("STORAGE_ERROR");
                    }
                }
            }
            check(lease,revision,started); return new Snapshot(authority,revision,rows,authority.now());
        } catch (OfficialException e) { throw e; }
        catch (SQLException e) { throw OfficialJdbcTransactions.sqlError(e); }
        catch (IOException e) { throw new OfficialException("STORAGE_ERROR"); }
        catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
        finally {
            if (connection != null) try { connection.rollback(); } catch (Exception ignored) { }
            OfficialJdbcConnections.close(connection); OfficialMirrorAuthority.release(lease);
        }
    }
}
