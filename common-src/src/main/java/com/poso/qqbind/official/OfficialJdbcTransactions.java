package com.poso.qqbind.official;

import com.google.gson.*;
import java.io.IOException;
import java.math.BigInteger;
import java.sql.*;
import java.util.*;

/** One-attempt JDBC transaction store for OfficialCenterMutations.
 * No DDL, credentials, old pool, retry, token code, HTTP or game side effects.
 */
public final class OfficialJdbcTransactions implements OfficialCenterMutations.Store {
    static final String OP_FIELDS=OfficialJdbcCenter.FIELDS + ",next_attempt_at,created_at,updated_at";
    static final String OP_SELECT="SELECT " + OP_FIELDS + " FROM bindings_qqofficial_ops WHERE operation_id=? FOR UPDATE";
    static final String REQUEST_SELECT="SELECT operation_id FROM bindings_qqofficial_ops WHERE environment_id=? AND request_key=? FOR UPDATE";
    static final String GENERATION_SELECT="SELECT operation_id FROM bindings_qqofficial_ops WHERE environment_id=? AND original_server_id=? AND token_generation=? FOR UPDATE";
    static final String BIND_FIELDS="id,game_key,game_id,qq,state,revision,last_operation_id,bind_time,updated_at";
    static final String BIND_SELECT="SELECT " + BIND_FIELDS + " FROM bindings_qqofficial WHERE environment_id=? AND group_id=? AND game_key=? FOR UPDATE";
    static final String OP_INSERT="INSERT INTO bindings_qqofficial_ops (" + OP_FIELDS + ") VALUES (" + String.join(",",Collections.nCopies(22,"?")) + ")";
    static final String OP_UPDATE="UPDATE bindings_qqofficial_ops SET reservation_id=?,state=?,result_code=?,effects=?,followup=?,next_attempt_at=?,updated_at=? WHERE operation_id=? AND environment_id=? AND group_id=? AND state=?";
    static final String BIND_INSERT="INSERT INTO bindings_qqofficial (environment_id,group_id,game_key,game_id,qq,state,revision,last_operation_id,bind_time,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)";
    static final String REBIND="UPDATE bindings_qqofficial SET game_id=?,qq=?,state='active',revision=?,last_operation_id=?,bind_time=?,updated_at=? WHERE id=? AND environment_id=? AND group_id=? AND qq=? AND state='revoked' AND revision=?";
    static final String REVOKE="UPDATE bindings_qqofficial SET state='revoked',revision=?,last_operation_id=?,updated_at=? WHERE id=? AND environment_id=? AND group_id=? AND qq=? AND state='active' AND revision=?";
    private final OfficialScope scope;
    private final OfficialJdbcCenter.Connections connections;
    private final OfficialJdbcCenter validator;
    public OfficialJdbcTransactions(OfficialScope scope,OfficialJdbcCenter.Connections connections) {
        this.scope=Objects.requireNonNull(scope); this.connections=Objects.requireNonNull(connections); this.validator=new OfficialJdbcCenter(scope,connections);
    }
    static OfficialException sqlError(SQLException e) {
        return new OfficialException(switch(e.getErrorCode()) {
            case 1062,1205,1213,9007 -> "CONFLICT";
            case 1044,1045,1142,1143 -> "UNAUTHORIZED";
            case 1054,1146 -> "SCHEMA_MISMATCH";
            case 1064,1235 -> "NOT_SUPPORTED";
            default -> "DB_UNAVAILABLE";
        });
    }
    @Override public <T> T transact(OfficialCenterMutations.Work<T> work) {
        Objects.requireNonNull(work); Connection connection=null; boolean committing=false;
        try {
            connection=connections.open();
            // Never turn a read-only connection into a writer or use autocommit.
            if (connection == null || connection.isReadOnly() || connection.getAutoCommit()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) throw new OfficialException("NOT_SUPPORTED");
            OfficialCenterSchema.inspect(connection,true);
            T result=work.run(new Tx(connection)); committing=true; connection.commit(); return result;
        } catch (OfficialException e) { if (committing) throw new OfficialException("RESULT_UNKNOWN"); throw e; }
        catch (SQLException e) { if (committing) throw new OfficialException("RESULT_UNKNOWN"); throw sqlError(e); }
        catch (Exception e) { throw new OfficialException(committing ? "RESULT_UNKNOWN" : "STORAGE_ERROR"); }
        finally {
            if (connection != null) try { connection.rollback(); } catch (Exception ignored) { }
            OfficialJdbcConnections.close(connection);
        }
    }
    private static String text(JsonObject value,String name) { return OfficialModState.string(value,name); }
    private static void bind(PreparedStatement statement,Object... values) throws SQLException {
        statement.setQueryTimeout(5);
        for (int i=0;i<values.length;i++) {
            Object value=values[i];
            if (value == null) statement.setNull(i+1,Types.VARCHAR);
            else if (value instanceof Long number) statement.setLong(i+1,number);
            else statement.setString(i+1,(String)value);
        }
    }
    private static String nullable(JsonObject value,String field) { return OfficialCenterMutations.absent(value,field) ? null : text(value,field); }
    private static String encoded(JsonObject value,String field) { return OfficialCenterMutations.absent(value,field) ? null : OfficialCenterMutations.json(value.get(field)); }
    private final class Tx implements OfficialCenterMutations.Transaction {
        private final Connection connection;
        private final Set<String> lockedOperations=new HashSet<>(), lockedBindings=new HashSet<>();
        Tx(Connection connection) { this.connection=connection; }
        private JsonObject row(String sql,String fields,Object... values) {
            try (PreparedStatement statement=connection.prepareStatement(sql)) {
                bind(statement,values); statement.setMaxRows(2);
                try (ResultSet result=statement.executeQuery()) {
                    if (!result.next()) return null;
                    JsonObject row=OfficialJdbcCenter.readRow(result,fields);
                    if (result.next()) throw new OfficialException("STORAGE_ERROR"); return row;
                }
            } catch (SQLException e) { throw sqlError(e); }
            catch (IOException e) { throw new OfficialException("STORAGE_ERROR"); }
        }
        private void write(String sql,Object... values) {
            try (PreparedStatement statement=connection.prepareStatement(sql)) {
                bind(statement,values);
                if (statement.executeUpdate() != 1) throw new OfficialException("CONFLICT");
            } catch (SQLException e) { throw sqlError(e); }
        }
        @Override public JsonObject operation(String id) {
            OfficialScope.uuid(id); JsonObject row=row(OP_SELECT,OP_FIELDS,id); lockedOperations.add(id);
            if (row == null) return null;
            if (!text(row,"operation_id").equals(id)) throw new OfficialException("STORAGE_ERROR"); return validator.validate(row);
        }
        private JsonObject find(String sql,Object... values) {
            JsonObject row=row(sql,"operation_id",values); return row == null ? null : operation(OfficialScope.uuid(text(row,"operation_id")));
        }
        @Override public JsonObject request(String environment,String requestKey) {
            if (!scope.environmentId().equals(environment)) throw new OfficialException("SCOPE_MISMATCH"); return find(REQUEST_SELECT,environment,requestKey);
        }
        @Override public JsonObject generation(String environment,String server,String generation) {
            if (!scope.environmentId().equals(environment)) throw new OfficialException("SCOPE_MISMATCH"); return find(GENERATION_SELECT,environment,server,generation);
        }
        @Override public Map<String,JsonObject> lockBindings(SortedSet<String> keys) {
            Map<String,JsonObject> result=new TreeMap<>();
            for (String key : new TreeSet<>(keys)) {
                if (!OfficialScope.key(key).equals(key)) throw new OfficialException("INVALID_INPUT");
                JsonObject row=row(BIND_SELECT,BIND_FIELDS,scope.environmentId(),scope.groupId(),key);
                if (row != null) validateBinding(row,key); result.put(key,row); lockedBindings.add(key);
            }
            return result;
        }
        private void validateBinding(JsonObject row,String key) {
            try {
                String id=text(row,"id");
                if (!id.matches("[1-9][0-9]{0,19}") || new BigInteger(id).compareTo(new BigInteger("18446744073709551615")) > 0
                        || !text(row,"game_key").equals(key) || !OfficialScope.key(text(row,"game_id")).equals(key)
                        || !Set.of("active","revoked").contains(text(row,"state"))) throw new IllegalArgumentException();
                OfficialScope.identifier(text(row,"qq")); OfficialScope.revision(text(row,"revision")); OfficialScope.uuid(text(row,"last_operation_id"));
            } catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
        }
        @Override public void insertOperation(JsonObject row) {
            validator.validate(row); String id=text(row,"operation_id");
            if (!lockedOperations.contains(id)) throw new OfficialException("CONFLICT");
            List<Object> values=new ArrayList<>();
            for (String field : OP_FIELDS.split(",")) values.add(Set.of("intent","nickname_basis","effects","followup").contains(field) ? encoded(row,field)
                : field.equals("policy_revision") ? OfficialScope.revision(text(row,field)) : nullable(row,field));
            write(OP_INSERT,values.toArray());
        }
        @Override public void updateOperation(JsonObject before,JsonObject next) {
            validator.validate(before); validator.validate(next); String id=text(before,"operation_id");
            if (!lockedOperations.contains(id)) throw new OfficialException("CONFLICT");
            Set<String> mutable=Set.of("reservation_id","state","result_code","effects","followup","next_attempt_at","updated_at");
            for (String field : OP_FIELDS.split(",")) if (!mutable.contains(field) && !Objects.equals(before.get(field),next.get(field))) throw new OfficialException("CONFLICT");
            write(OP_UPDATE,nullable(next,"reservation_id"),text(next,"state"),nullable(next,"result_code"),encoded(next,"effects"),encoded(next,"followup"),
                nullable(next,"next_attempt_at"),text(next,"updated_at"),id,scope.environmentId(),scope.groupId(),text(before,"state"));
        }
        @Override public void applyBinding(JsonObject before,JsonObject effect,String operation,String now) {
            effect=OfficialMirrorModel.effect(effect); String key=text(effect,"game_key"); OfficialScope.uuid(operation);
            if (!lockedBindings.contains(key) || !lockedOperations.contains(operation)) throw new OfficialException("CONFLICT");
            long revision=OfficialScope.revision(text(effect,"revision"));
            if (before == null) {
                if (!text(effect,"state").equals("active") || revision != 1) throw new OfficialException("CONFLICT");
                write(BIND_INSERT,scope.environmentId(),scope.groupId(),key,text(effect,"game_id"),text(effect,"user_id"),"active",revision,operation,now,now);
            } else {
                validateBinding(before,key); long previous=OfficialScope.revision(text(before,"revision"));
                if (previous == Long.MAX_VALUE || revision != previous+1) throw new OfficialException("CONFLICT");
                if (text(effect,"state").equals("active")) {
                    if (!text(before,"state").equals("revoked")) throw new OfficialException("CONFLICT");
                    write(REBIND,text(effect,"game_id"),text(effect,"user_id"),revision,operation,now,now,text(before,"id"),scope.environmentId(),scope.groupId(),text(before,"qq"),previous);
                } else {
                    if (!text(before,"state").equals("active") || !text(before,"qq").equals(text(effect,"user_id"))) throw new OfficialException("CONFLICT");
                    write(REVOKE,revision,operation,now,text(before,"id"),scope.environmentId(),scope.groupId(),text(before,"qq"),previous);
                }
            }
        }
    }
}
