package com.poso.qqbind.official;

import com.google.gson.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/** Read-only trusted center inspection. Never commits/replays a binding change.
 * Every lookup checks schema/session and uses a new explicitly supplied connection.
 * A missing row is distinct from DB_UNAVAILABLE, bad scope and STORAGE_ERROR.
 */
public final class OfficialJdbcCenter implements OfficialTokenService.CenterInspector {
    public interface Connections { Connection open() throws SQLException; }
    private final OfficialScope scope;
    private final Connections connections;
    static final String FIELDS = "operation_id,environment_id,group_id,request_key,payload_hash,kind,actor_kind,actor_id,target_user_id,original_server_id,token_generation,reservation_id,policy_revision,intent,nickname_basis,state,result_code,effects,followup";
    static final String LOOKUP = "SELECT " + FIELDS + " FROM bindings_qqofficial_ops WHERE operation_id=? AND environment_id=? AND group_id=?";
    public OfficialJdbcCenter(OfficialScope scope, Connections connections) {
        this.scope = Objects.requireNonNull(scope); this.connections = Objects.requireNonNull(connections);
    }

    public JsonObject readOperation(String operationId) {
        OfficialScope.uuid(operationId); Connection connection = null;
        try {
            connection = connections.open(); OfficialCenterSchema.inspect(connection);
            try (PreparedStatement statement = connection.prepareStatement(LOOKUP)) {
                statement.setQueryTimeout(5); statement.setMaxRows(2);
                statement.setString(1,operationId); statement.setString(2,scope.environmentId()); statement.setString(3,scope.groupId());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return null;
                    JsonObject row = readRow(result,FIELDS);
                    if (result.next() || !operationId.equals(text(row,"operation_id"))) throw new OfficialException("STORAGE_ERROR");
                    return validate(row);
                }
            }
        } catch (OfficialException e) { throw e; }
        catch (SQLException e) {
            throw new OfficialException(switch (e.getErrorCode()) {
                case 1044,1045,1142,1143 -> "UNAUTHORIZED";
                case 1054,1146 -> "SCHEMA_MISMATCH";
                default -> "DB_UNAVAILABLE";
            });
        } catch (IOException e) { throw new OfficialException("STORAGE_ERROR"); }
        catch (Exception e) { throw new OfficialException("DB_UNAVAILABLE"); }
        finally {
            if (connection != null) try { connection.rollback(); } catch (Exception ignored) { }
            OfficialJdbcConnections.close(connection);
        }
    }
    static JsonObject readRow(ResultSet result,String fields) throws SQLException,IOException {
        JsonObject row=new JsonObject();
        for (String name : fields.split(",")) {
            if (Set.of("intent","nickname_basis","effects","followup").contains(name)) {
                String raw=bounded(result.getCharacterStream(name));
                row.add(name,raw == null ? JsonNull.INSTANCE : OfficialModState.parse(raw));
            } else {
                String value=result.getString(name);
                if (value != null && value.length() > 128) throw new OfficialException("STORAGE_ERROR"); row.addProperty(name,value);
            }
        }
        return row;
    }
    private static String bounded(Reader reader) throws IOException {
        if (reader == null) return null;
        try (reader) {
            StringBuilder out = new StringBuilder(); char[] chunk = new char[4096]; int length;
            while ((length = reader.read(chunk)) >= 0) {
                if (out.length() + length > OfficialIntent.MAX_BYTES) throw new OfficialException("STORAGE_ERROR"); out.append(chunk,0,length);
            }
            if (out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > OfficialIntent.MAX_BYTES) throw new OfficialException("STORAGE_ERROR");
            return out.toString();
        }
    }
    private static String text(JsonObject value, String name) { return OfficialModState.string(value,name); }
    private static boolean absent(JsonObject value, String name) { return value.has(name) && value.get(name).isJsonNull(); }
    private static void fields(JsonObject value, Set<String> keys) { if (value == null || !value.keySet().equals(keys)) throw new IllegalArgumentException(); }
    private static String aggregate(JsonObject effects, String part) {
        Set<String> states = new HashSet<>(); for (var value : effects.asMap().values()) states.add(text(value.getAsJsonObject(),part));
        for (String state : List.of("CONFLICT","FAILED","PENDING","SUPERSEDED")) if (states.contains(state)) return state; return "APPLIED";
    }
    JsonObject validate(JsonObject row) {
        try {
            if (!text(row,"environment_id").equals(scope.environmentId()) || !text(row,"group_id").equals(scope.groupId())) throw new OfficialException("SCOPE_MISMATCH");
            OfficialScope.uuid(text(row,"operation_id"));
            JsonObject normalized = new JsonObject();
            for (String name : List.of("environment_id","group_id","actor_kind","actor_id","token_generation","policy_revision","kind","intent","nickname_basis")) normalized.add(name,row.get(name));
            normalized.add("server_id",row.get("original_server_id")); normalized.add("user_id",row.get("target_user_id"));
            OfficialIntent.validate(normalized);
            if (!text(row,"request_key").matches("[a-f0-9]{64}") || !text(row,"payload_hash").equals(OfficialIntent.hash(normalized))) throw new IllegalArgumentException();
            String state = text(row,"state"), kind = text(row,"kind"); boolean bind = kind.equals("bind"), committed = state.equals("COMMITTED");
            if (!Set.of("PREPARED","RESERVED","COMMITTED","REJECTED","ABORTED").contains(state) || !bind && state.equals("RESERVED")) throw new IllegalArgumentException();
            if (!absent(row,"result_code") && !text(row,"result_code").matches("[A-Z_]{1,32}")) throw new IllegalArgumentException();
            if (!absent(row,"reservation_id")) OfficialScope.uuid(text(row,"reservation_id"));
            if (bind && Set.of("RESERVED","COMMITTED").contains(state) && absent(row,"reservation_id") || !bind && !absent(row,"reservation_id")) throw new IllegalArgumentException();
            JsonObject intent = row.getAsJsonObject("intent"), followup = row.getAsJsonObject("followup"); JsonArray effects = row.getAsJsonArray("effects");
            Set<String> allowed = Set.of("token_finalize","token_reservation","delivery","attempts");
            if (!allowed.containsAll(followup.keySet()) || !followup.has("token_finalize") || !followup.has("delivery")) throw new IllegalArgumentException();
            if (followup.has("attempts")) OfficialScope.revision(text(followup,"attempts"));
            List<String> keys = new ArrayList<>();
            if (bind) keys.add(text(intent,"game_key")); else for (JsonElement v : intent.getAsJsonArray("targets")) keys.add(text(v.getAsJsonObject(),"game_key"));
            if (committed) {
                if (effects.size() != keys.size()) throw new IllegalArgumentException();
                for (int i = 0; i < effects.size(); i++) {
                    JsonObject effect = OfficialMirrorModel.effect(effects.get(i).getAsJsonObject());
                    if (!text(effect,"game_key").equals(keys.get(i)) || !text(effect,"user_id").equals(text(row,"target_user_id"))
                            || !text(effect,"state").equals(bind ? "active" : "revoked")) throw new IllegalArgumentException();
                }
                Set<String> servers = new HashSet<>(); for (JsonElement v : intent.getAsJsonArray("target_server_ids")) servers.add(v.getAsString());
                JsonObject delivery = followup.getAsJsonObject("delivery"); if (!delivery.keySet().equals(servers)) throw new IllegalArgumentException();
                for (String server : servers) {
                    JsonObject target = delivery.getAsJsonObject(server); fields(target,Set.of("mirror","whitelist","online","effects"));
                    JsonObject items = target.getAsJsonObject("effects"); if (!items.keySet().equals(new HashSet<>(keys))) throw new IllegalArgumentException();
                    for (JsonElement v : effects) {
                        JsonObject effect = v.getAsJsonObject(), item = items.getAsJsonObject(text(effect,"game_key"));
                        fields(item,Set.of("mirror","whitelist","online","requested_revision","effective_revision"));
                        if (!text(item,"requested_revision").equals(text(effect,"revision"))) throw new IllegalArgumentException();
                        if (!absent(item,"effective_revision")) OfficialScope.revision(text(item,"effective_revision"));
                        for (String part : List.of("mirror","whitelist","online")) if (!OfficialMirrorModel.STATUS.contains(text(item,part))) throw new IllegalArgumentException();
                    }
                    for (String part : List.of("mirror","whitelist","online")) if (!text(target,part).equals(aggregate(items,part))) throw new IllegalArgumentException();
                }
            } else if (!effects.isEmpty() || followup.getAsJsonObject("delivery").size() != 0) throw new IllegalArgumentException();
            if (followup.has("token_reservation")) {
                JsonObject pending = followup.getAsJsonObject("token_reservation"); fields(pending,Set.of("state","token_generation","reservation_id"));
                if (!bind || !state.equals("PREPARED") || !text(pending,"state").equals("PENDING")
                        || !text(pending,"token_generation").equals(text(row,"token_generation"))) throw new IllegalArgumentException();
                if (!absent(pending,"reservation_id")) OfficialScope.uuid(text(pending,"reservation_id"));
            }
            if (bind && Set.of("COMMITTED","REJECTED","ABORTED").contains(state)) {
                JsonObject token = followup.getAsJsonObject("token_finalize"); fields(token,Set.of("state","decision","reservation_id","token_generation"));
                String decision = committed ? "COMMITTED" : "ABORTED", done = committed ? "CONSUMED" : "INVALIDATED";
                if (!text(token,"decision").equals(decision) || !Set.of("PENDING",done).contains(text(token,"state"))
                        || !text(token,"token_generation").equals(text(row,"token_generation"))
                        || !absent(row,"reservation_id") && !row.get("reservation_id").equals(token.get("reservation_id"))) throw new IllegalArgumentException();
                if (!absent(token,"reservation_id")) OfficialScope.uuid(text(token,"reservation_id"));
                else if (!text(token,"state").equals("PENDING")) throw new IllegalArgumentException();
            } else if (!absent(followup,"token_finalize")) throw new IllegalArgumentException();
            return row.deepCopy();
        } catch (OfficialException e) {
            if (e.code().equals("SCOPE_MISMATCH")) throw e; throw new OfficialException("STORAGE_ERROR");
        } catch (Exception e) { throw new OfficialException("STORAGE_ERROR"); }
    }

    @Override public OfficialTokenService.CenterOperation find(String operationId) {
        JsonObject row = readOperation(operationId); if (row == null) return null;
        if (!text(row,"kind").equals("bind")) throw new OfficialException("CONFLICT");
        if (!text(row,"original_server_id").equals(scope.serverId())) throw new OfficialException("SCOPE_MISMATCH");
        return new OfficialTokenService.CenterOperation(scope,operationId,text(row,"token_generation"),
            text(row.getAsJsonObject("intent"),"game_id"),text(row,"target_user_id"),text(row,"state"),
            row.getAsJsonArray("effects"),row.getAsJsonObject("followup").getAsJsonObject("delivery"));
    }
}
