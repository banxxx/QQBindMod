package com.poso.qqbind.official;

import com.google.gson.*;
import java.util.Set;

/** V2 protocol boundary used by the dedicated official HTTP adapter. */
public final class OfficialTokenApi {
    public interface Authorizer { boolean allow(OfficialScope scope, String path); }
    public record Reply(int status, JsonObject body) { }
    private static final Set<String> SCOPE = Set.of("protocol_version", "environment_id", "group_id", "server_id");
    private static final String PREFIX = "/api/v2/binding/";
    private final OfficialTokenService service;
    private final Authorizer authorizer;
    private final OfficialMirrorService mirror;
    private final OfficialMirrorAuthority mirrorAuthority;

    public OfficialTokenApi(OfficialTokenService service, Authorizer authorizer) {
        this(service, authorizer, null);
    }
    public OfficialTokenApi(OfficialTokenService service, Authorizer authorizer, OfficialMirrorService mirror) {
        this(service,authorizer,mirror,null);
    }
    private OfficialTokenApi(OfficialTokenService service, Authorizer authorizer, OfficialMirrorService mirror,OfficialMirrorAuthority mirrorAuthority) {
        this.service = service;
        this.authorizer = authorizer;
        this.mirror = mirror;
        this.mirrorAuthority = mirrorAuthority;
    }

    public static OfficialTokenApi withAuthority(OfficialTokenService service,Authorizer authorizer,OfficialMirrorAuthority authority) {
        if (authority == null) throw new IllegalArgumentException("Explicit mirror authority required");
        return new OfficialTokenApi(service,authorizer,authority.mirror(),authority);
    }
    /** Existing /sync wire shape; a boolean auth flag cannot enter this path. */
    public Reply dispatchSync(String method,String path,JsonObject request,String authorization) {
        try {
            if (mirrorAuthority == null) throw new OfficialException("NOT_SUPPORTED");
            if (request == null) throw new OfficialException("INVALID_INPUT");
            if (!request.has("protocol_version") || !request.get("protocol_version").isJsonPrimitive()
                    || !request.get("protocol_version").getAsJsonPrimitive().isNumber() || !request.get("protocol_version").getAsString().equals("2")) throw new OfficialException("NOT_SUPPORTED");
            OfficialScope scope=new OfficialScope(text(request,"environment_id"),text(request,"group_id"),text(request,"server_id"));
            if (!scope.equals(service.scope())) throw new OfficialException("SCOPE_MISMATCH");
            fields(request,Set.of("operation_id","effects"),Set.of());
            var permit=mirrorAuthority.authorize(scope,method,path,authorization);
            if (authorizer == null || !authorizer.allow(scope,path)) throw new OfficialException("UNAUTHORIZED");
            return success(mirrorAuthority.sync(permit,text(request,"operation_id"),request.getAsJsonArray("effects")));
        } catch (OfficialException e) { return error(e.code()); }
        catch (Exception e) { return error("STORAGE_ERROR"); }
    }

    public Reply dispatch(String method, String path, JsonObject request, boolean authenticated) {
        if (!authenticated) return error("UNAUTHORIZED");
        try {
            if (request == null || method == null || path == null) throw new OfficialException("INVALID_INPUT");
            if (!request.has("protocol_version") || !request.get("protocol_version").isJsonPrimitive()
                    || !request.get("protocol_version").getAsJsonPrimitive().isNumber()
                    || !request.get("protocol_version").getAsString().equals("2")) throw new OfficialException("NOT_SUPPORTED");
            OfficialScope scope = new OfficialScope(text(request, "environment_id"), text(request, "group_id"), text(request, "server_id"));
            if (!scope.equals(service.scope())) throw new OfficialException("SCOPE_MISMATCH");
            if (authorizer == null || !authorizer.allow(scope, path)) throw new OfficialException("UNAUTHORIZED");
            if (method.equals("POST") && path.equals(PREFIX + "sync")) {
                if (mirrorAuthority != null) throw new OfficialException("UNAUTHORIZED");
                fields(request, Set.of("operation_id", "effects"), Set.of());
                if (mirror == null) throw new OfficialException("NOT_SUPPORTED");
                return success(mirror.sync(scope, text(request, "operation_id"), request.getAsJsonArray("effects")));
            }
            if (method.equals("POST") && path.equals(PREFIX + "token/validate")) {
                fields(request, Set.of("token_code"), Set.of());
                return success(service.validate(text(request, "token_code")));
            }
            if (method.equals("POST") && path.equals(PREFIX + "token/reserve")) {
                fields(request, Set.of("token_code", "operation_id", "user_id", "game_key", "token_generation"), Set.of());
                return success(service.reserve(text(request, "operation_id"), text(request, "user_id"),
                    text(request, "game_key"), text(request, "token_generation"), text(request, "token_code")));
            }
            if (method.equals("POST") && path.equals(PREFIX + "token/finalize")) {
                fields(request, Set.of("operation_id", "reservation_id", "decision"), Set.of("token_generation"));
                String reservation = request.get("reservation_id").isJsonNull() ? null : text(request, "reservation_id");
                String generation = request.has("token_generation") ? text(request, "token_generation") : null;
                return success(service.finalizeToken(text(request, "operation_id"), reservation, text(request, "decision"), generation));
            }
            if (method.equals("GET") && path.startsWith(PREFIX + "operations/")) {
                fields(request, Set.of(), Set.of());
                String id = path.substring((PREFIX + "operations/").length());
                JsonObject data = service.operation(id);
                if (!data.get("center_available").getAsBoolean()) return reply(202, false, "RESULT_UNKNOWN", data);
                String state = text(data, "state");
                if (state.equals("PREPARED") || state.equals("RESERVED")) return reply(202, false, state, data);
                return reply(200, state.equals("COMMITTED"), state.equals("COMMITTED") ? "SYNC_PENDING" : "OK", data);
            }
            // Capabilities still need policy, game effects and recovery integration.
            throw new OfficialException("NOT_SUPPORTED");
        } catch (OfficialException e) { return error(e.code()); }
        catch (Exception e) { return error("STORAGE_ERROR"); }
    }

    private static void fields(JsonObject value, Set<String> required, Set<String> optional) {
        if (!value.keySet().containsAll(SCOPE) || !value.keySet().containsAll(required)
                || value.keySet().stream().anyMatch(k -> !SCOPE.contains(k) && !required.contains(k) && !optional.contains(k))) {
            throw new OfficialException("INVALID_INPUT");
        }
    }
    private static String text(JsonObject value, String key) { return OfficialModState.string(value, key); }
    private static Reply success(JsonObject data) { return reply(200, true, "OK", data); }
    static Reply error(String code) {
        int status = switch (code) {
            case "UNAUTHORIZED" -> 401;
            case "SCOPE_MISMATCH" -> 404;
            case "CONFLICT" -> 409;
            case "RESULT_UNKNOWN" -> 202;
            case "DB_UNAVAILABLE", "STORAGE_ERROR", "SCHEMA_MISMATCH" -> 503;
            default -> 400;
        };
        JsonObject data = new JsonObject();
        data.addProperty("protocol_version", 2);
        return reply(status, false, code, data);
    }
    private static Reply reply(int status, boolean success, String code, JsonObject data) {
        JsonObject body = new JsonObject();
        body.addProperty("success", success);
        body.addProperty("code", code);
        body.addProperty("message", code); // Stable and safe; never append input or exception text.
        body.add("data", data);
        return new Reply(status, body);
    }
}
