package com.poso.qqbind.official;

import com.google.gson.*;
import java.util.*;

/** Authenticated service boundary for the three authorities. QQ event permission
 * and nickname checks are performed by the selected plugin before forwarding.
 * HTTP cannot claim a native game_admin identity. */
public final class OfficialBindingApi {
    private final OfficialRuntimeSettings settings;
    private final OfficialScope scope;
    private final OfficialNativeAdmin.Repository repository;
    private final OfficialTokenService tokens;
    private final Runnable readiness;
    private final LinkedHashMap<String,Page> pages=new LinkedHashMap<>();
    private record Page(String user,JsonArray rows,int limit,long expires,boolean roster) { }
    public OfficialBindingApi(OfficialRuntimeSettings settings,OfficialNativeAdmin.Repository repository,OfficialTokenService tokens,Runnable readiness) {
        this.settings=settings; this.scope=settings.scope(); this.repository=repository; this.tokens=tokens; this.readiness=readiness;
    }
    public OfficialTokenApi.Reply dispatch(String method,String path,JsonObject request) {
        try {
            if (!scope.wire().entrySet().stream().allMatch(e -> e.getValue().equals(request.get(e.getKey())))) throw new OfficialException("SCOPE_MISMATCH");
            String suffix=path.substring("/api/v2/binding/".length());
            if (method.equals("GET") && suffix.equals("capabilities")) {
                fields(request,Set.of(),Set.of()); readiness.run(); JsonObject data=base();
                data.addProperty("storage_type","qq_official"); data.addProperty("storage_mode",settings.mode()); data.addProperty("schema_version",1);
                data.addProperty("game_key_rule","ascii_lower"); data.addProperty("authority",settings.mode().equals("local") ? "local" : "center");
                for (String name : List.of("multi_account","operation_results","revision_sync","token_reservation_query")) data.addProperty(name,true);
                return reply(data,"OK",true);
            }
            if (method.equals("GET") && suffix.startsWith("operations/")) {
                fields(request,Set.of(),Set.of()); String id=OfficialScope.uuid(suffix.substring("operations/".length()));
                JsonObject operation=repository.operation(id); if (operation==null) throw new OfficialException("SCOPE_MISMATCH");
                return operation(operation);
            }
            if (method.equals("GET") && suffix.equals("operations")) {
                fields(request,Set.of(),Set.of("filter","limit"));
                String filter=request.has("filter") ? text(request,"filter") : "pending";
                int limit=request.has("limit") ? request.get("limit").getAsInt() : 201;
                if (!Set.of("pending","all").contains(filter) || limit<1 || limit>201) throw new OfficialException("INVALID_INPUT");
                List<JsonObject> sorted=new ArrayList<>();
                for (JsonElement value : repository.operations()) { JsonObject row=value.getAsJsonObject(); if (filter.equals("all") || pending(row)) sorted.add(row); }
                sorted.sort(Comparator.comparing((JsonObject row) -> text(row,"created_at")).thenComparing(row -> text(row,"operation_id")).reversed());
                JsonArray rows=new JsonArray();
                for (JsonObject row : sorted.subList(0,Math.min(limit,sorted.size()))) {
                    JsonObject summary=new JsonObject();
                    for (String name : List.of("operation_id","kind","state","result_code","created_at","updated_at")) summary.add(name,row.get(name));
                    summary.addProperty("pending",pending(row)); rows.add(summary);
                }
                JsonObject data=base(); data.add("operations",rows); return reply(data,"OK",true);
            }
            if (method.equals("GET") && suffix.equals("check")) {
                fields(request,Set.of(),Set.of("game_id","user_id","cursor","limit","view"));
                return reply(query(request),"OK",true);
            }
            if (method.equals("POST") && (suffix.equals("bind") || suffix.equals("unbind"))) {
                if (!request.has("management_action") && !bool(request,"cancel")) readiness.run();
                return mutate(suffix,request);
            }
            throw new OfficialException("NOT_SUPPORTED");
        } catch (OfficialException e) { return OfficialTokenApi.error(e.code()); }
        catch (RuntimeException e) { return OfficialTokenApi.error(method.equals("POST") ? "RESULT_UNKNOWN" : "STORAGE_ERROR"); }
    }
    private OfficialTokenApi.Reply mutate(String kind,JsonObject request) {
        boolean bind=kind.equals("bind");
        if (!bind && request.has("management_action")) {
            fields(request,Set.of("operation_id","management_action"),Set.of());
            String id=OfficialScope.uuid(text(request,"operation_id")), action=text(request,"management_action");
            if (!Set.of("cancel","retry").contains(action)) throw new OfficialException("INVALID_INPUT");
            JsonObject row=repository.operation(id); if (row==null) throw new OfficialException("SCOPE_MISMATCH");
            if (action.equals("cancel")) {
                if (OfficialCenterMutations.TERMINAL.contains(text(row,"state"))) throw new OfficialException("CONFLICT");
                OfficialCenterMutations.PolicyGuard guard=(phase,intent) -> {
                    if (phase!=OfficialCenterMutations.Action.ABORT) throw new OfficialException("UNAUTHORIZED");
                    return new OfficialCenterMutations.PolicyLease() { public String policyRevision() { return settings.revision(); } public void close() { } };
                };
                repository.mutations(guard).abort(id,OfficialNativeAdmin.normalized(row));
            } else {
                if (!OfficialCenterMutations.TERMINAL.contains(text(row,"state"))) throw new OfficialException("NOT_SUPPORTED");
                if (text(row,"state").equals("COMMITTED")) repository.committed(row);
                if (text(row,"kind").equals("bind")) {
                    tokens.finalizeToken(id,row.get("reservation_id").isJsonNull() ? null : text(row,"reservation_id"),
                        text(row,"state").equals("COMMITTED") ? "COMMITTED" : "ABORTED",text(row,"token_generation"));
                }
            }
            return operation(repository.operation(id));
        }
        fields(request,bind ? Set.of("operation_id","request_key","user_id","game_id","token_generation","policy_revision","nickname_basis","actor","authorization")
            : Set.of("operation_id","request_key","kind","target_user_id","targets","actor","authorization"),
            bind ? Set.of("token_code","resume","cancel","target_server_ids") : Set.of("cancel","target_server_ids"));
        String id=OfficialScope.uuid(text(request,"operation_id")), requestKey=text(request,"request_key");
        JsonObject actor=request.getAsJsonObject("actor"), authorization=request.getAsJsonObject("authorization");
        OfficialMirrorModel.fields(actor,Set.of("kind","id")); OfficialMirrorModel.fields(authorization,Set.of("scope","policy_revision"));
        if (!text(actor,"kind").equals("qq_member")) throw new OfficialException("UNAUTHORIZED");
        String user=OfficialScope.identifier(text(request,bind ? "user_id" : "target_user_id")), actorId=OfficialScope.identifier(text(actor,"id"));
        String policy=text(authorization,"policy_revision"); OfficialScope.revision(policy);
        boolean cancel=bool(request,"cancel"), resume=bool(request,"resume");
        JsonObject existing=repository.operation(id);
        if (cancel) {
            if (existing==null) throw new OfficialException("SCOPE_MISMATCH");
            boolean original=text(existing,"actor_kind").equals("qq_member") && text(existing,"actor_id").equals(actorId);
            if (!(original || Set.of("group","environment").contains(text(authorization,"scope")))) throw new OfficialException("UNAUTHORIZED");
            if (!text(existing,"request_key").equals(requestKey) || text(existing,"state").equals("COMMITTED")) throw new OfficialException("CONFLICT");
            OfficialCenterMutations.PolicyGuard cancelGuard=(action,intent) -> {
                if (action!=OfficialCenterMutations.Action.ABORT) throw new OfficialException("UNAUTHORIZED");
                return new OfficialCenterMutations.PolicyLease() { public String policyRevision() { return policy; } public void close() { } };
            };
            return operation(repository.mutations(cancelGuard).abort(id,OfficialNativeAdmin.normalized(existing)));
        }
        if (bind && (!text(authorization,"scope").equals("self") || !actorId.equals(user) || !policy.equals(text(request,"policy_revision")))) throw new OfficialException("UNAUTHORIZED");
        if (!bind && text(authorization,"scope").equals("self") && !actorId.equals(user)) throw new OfficialException("UNAUTHORIZED");
        JsonObject normalized=new JsonObject(), body=new JsonObject();
        normalized.addProperty("environment_id",scope.environmentId()); normalized.addProperty("group_id",scope.groupId()); normalized.addProperty("actor_kind","qq_member");
        normalized.addProperty("actor_id",actorId); normalized.addProperty("user_id",user); normalized.addProperty("policy_revision",policy); normalized.addProperty("kind",bind ? "bind" : text(request,"kind"));
        normalized.add("server_id",bind ? new JsonPrimitive(scope.serverId()) : JsonNull.INSTANCE);
        normalized.add("token_generation",bind ? request.get("token_generation") : JsonNull.INSTANCE);
        normalized.add("nickname_basis",bind ? request.get("nickname_basis") : JsonNull.INSTANCE);
        body.add("authorization",authorization.deepCopy()); JsonArray servers=new JsonArray(); servers.add(scope.serverId());
        if (request.has("target_server_ids")) servers=request.getAsJsonArray("target_server_ids").deepCopy();
        if (settings.mode().equals("local") && (servers.size()!=1 || !servers.get(0).getAsString().equals(scope.serverId()))) throw new OfficialException("SCOPE_MISMATCH");
        body.add("target_server_ids",servers);
        if (bind) { body.add("game_id",request.get("game_id")); body.addProperty("game_key",OfficialScope.key(text(request,"game_id"))); }
        else body.add("targets",request.get("targets").deepCopy());
        normalized.add("intent",body); OfficialIntent.validate(normalized);
        OfficialCenterMutations.PolicyGuard guard=(action,intent) -> {
            if (!OfficialIntent.hash(intent).equals(OfficialIntent.hash(normalized))) throw new OfficialException("CONFLICT");
            return new OfficialCenterMutations.PolicyLease() { public String policyRevision() { return policy; } public void close() { } };
        };
        OfficialCenterMutations mutations=repository.mutations(guard);
        if (resume && existing==null) throw new OfficialException("SCOPE_MISMATCH");
        if (existing!=null && (!text(existing,"payload_hash").equals(OfficialIntent.hash(normalized)) || !text(existing,"request_key").equals(requestKey))) throw new OfficialException("CONFLICT");
        if (existing!=null && OfficialCenterMutations.TERMINAL.contains(text(existing,"state"))) return operation(existing);
        if (bind && existing==null && !request.has("token_code")) throw new OfficialException("TOKEN_INVALID");
        JsonObject operation=existing==null ? mutations.prepare(id,requestKey,normalized) : existing; id=text(operation,"operation_id");
        if (bind) {
            if (text(operation,"state").equals("PREPARED")) {
                boolean claimed=mutations.claimReservation(id,normalized);
                JsonObject receipt;
                if (claimed && !resume) {
                    receipt=tokens.reserve(id,user,text(body,"game_key"),text(request,"token_generation"),text(request,"token_code"));
                } else {
                    JsonObject status=tokens.operation(id); receipt=status.getAsJsonObject("reservation");
                    if (receipt==null || !text(receipt,"state").equals("RESERVED")) {
                        if (receipt!=null && text(receipt,"state").equals("ABSENT") && resume) return operation(mutations.abort(id,normalized));
                        return operation(operation);
                    }
                }
                operation=mutations.recordReservation(id,normalized,text(receipt,"reservation_id"),text(receipt,"token_generation"));
            }
        }
        operation=mutations.commit(id,normalized);
        if (text(operation,"state").equals("COMMITTED")) try { repository.committed(operation); } catch (RuntimeException ignored) { /* Confirmed commit retains its outbox. */ }
        if (bind && OfficialCenterMutations.TERMINAL.contains(text(operation,"state"))) {
            try { tokens.finalizeToken(id,operation.get("reservation_id").isJsonNull() ? null : text(operation,"reservation_id"),
                text(operation,"state").equals("COMMITTED") ? "COMMITTED" : "ABORTED",text(operation,"token_generation")); }
            catch (OfficialException ignored) { /* Durable terminal/outbox remains queryable. */ }
        }
        return operation(repository.operation(id));
    }
    private OfficialTokenApi.Reply operation(JsonObject operation) {
        JsonObject data=base();
        for (String name : List.of("operation_id","state","kind","effects")) data.add(name,operation.get(name).deepCopy());
        data.add("delivery",operation.getAsJsonObject("followup").get("delivery").deepCopy()); data.add("operation",operation.deepCopy());
        if (text(operation,"kind").equals("bind")) {
            JsonObject token=tokens.operation(text(operation,"operation_id"));
            data.add("reservation",token.get("reservation")); data.add("center_available",token.get("center_available"));
        } else data.addProperty("center_available",true);
        String state=text(operation,"state"), code=text(operation,"result_code");
        return reply(data,Set.of("PREPARED","RESERVED").contains(state) ? state : code,state.equals("COMMITTED"));
    }
    private JsonObject query(JsonObject request) {
        boolean roster=request.has("view") && text(request,"view").equals("active_roster");
        if (request.has("view") && (!roster || request.has("game_id") || request.has("user_id"))
                || !roster && request.has("game_id")==request.has("user_id")) throw new OfficialException("INVALID_INPUT");
        JsonObject result=base();
        if (request.has("game_id")) {
            if (request.has("limit") || request.has("cursor")) throw new OfficialException("INVALID_INPUT");
            String key=OfficialScope.key(text(request,"game_id")); result.addProperty("game_id",text(request,"game_id")); result.addProperty("bound",false); result.add("revision",JsonNull.INSTANCE);
            for (JsonElement item : repository.rows()) { JsonObject row=item.getAsJsonObject(); if (!text(row,"game_key").equals(key)) continue;
                result.add("revision",row.get("revision")); result.addProperty("bound",text(row,"state").equals("active")); result.add("binding",row.deepCopy());
            } return result;
        }
        String user=roster ? "@active-roster" : OfficialScope.identifier(text(request,"user_id")); int limit=request.has("limit") ? request.get("limit").getAsInt() : 100;
        if (limit<1 || limit>100) throw new OfficialException("INVALID_INPUT");
        synchronized (pages) {
            long now=System.nanoTime(); pages.entrySet().removeIf(e -> now-e.getValue().expires()>=0); String pageId; int offset=0; Page page;
            if (!request.has("cursor")) {
                TreeMap<String,JsonObject> sorted=new TreeMap<>(); for (JsonElement item : repository.rows()) { JsonObject row=item.getAsJsonObject();
                    if (text(row,"state").equals("active") && (roster || text(row,"user_id").equals(user))) sorted.put(text(row,"game_key"),row); }
                JsonArray rows=new JsonArray(); sorted.values().forEach(rows::add);
                if (pages.size()>=32) pages.remove(pages.keySet().iterator().next());
                pageId=UUID.randomUUID().toString(); page=new Page(user,rows,limit,now+600_000_000_000L,roster); pages.put(pageId,page);
            } else {
                String cursor=text(request,"cursor"); if (!cursor.matches("[a-f0-9-]{36}:[0-9]{1,5}")) throw new OfficialException("INVALID_INPUT");
                String[] values=cursor.split(":"); pageId=OfficialScope.uuid(values[0]); offset=Integer.parseInt(values[1]); page=pages.get(pageId);
                if (page==null || page.roster()!=roster || !page.user().equals(user) || page.limit()!=limit || offset<1 || offset%limit!=0 || offset>=page.rows().size()) throw new OfficialException("CONFLICT");
            }
            JsonArray rows=new JsonArray(), games=new JsonArray(); for (int i=offset;i<Math.min(offset+limit,page.rows().size());i++) {
                JsonObject row=page.rows().get(i).getAsJsonObject(); rows.add(row.deepCopy()); games.add(row.get("game_id")); }
            result.add("bindings",rows); result.add("game_ids",games); result.add("next_cursor",offset+limit<page.rows().size() ? new JsonPrimitive(pageId+":"+(offset+limit)) : JsonNull.INSTANCE); return result;
        }
    }
    private JsonObject base() { JsonObject data=new JsonObject(); data.addProperty("protocol_version",2); data.add("scope",scope.wire()); return data; }
    private static boolean pending(JsonObject row) {
        if (!OfficialCenterMutations.TERMINAL.contains(text(row,"state"))) return true;
        JsonObject followup=row.getAsJsonObject("followup"); JsonElement token=followup.get("token_finalize");
        if (!token.isJsonNull() && text(token.getAsJsonObject(),"state").equals("PENDING")) return true;
        for (JsonElement target : followup.getAsJsonObject("delivery").asMap().values())
            for (String part : OfficialCenterMutations.PARTS) if (!Set.of("APPLIED","SUPERSEDED").contains(text(target.getAsJsonObject(),part))) return true;
        return false;
    }
    private void fields(JsonObject value,Set<String> required,Set<String> optional) {
        Set<String> keys=new HashSet<>(scope.wire().keySet()); keys.addAll(required); Set<String> allowed=new HashSet<>(keys); allowed.addAll(optional);
        if (!value.keySet().containsAll(keys) || !allowed.containsAll(value.keySet())) throw new OfficialException("INVALID_INPUT");
    }
    private static String text(JsonObject value,String name) { return OfficialModState.string(value,name); }
    private static boolean bool(JsonObject value,String name) {
        if (!value.has(name)) return false; if (!value.get(name).isJsonPrimitive() || !value.get(name).getAsJsonPrimitive().isBoolean()) throw new OfficialException("INVALID_INPUT"); return value.get(name).getAsBoolean();
    }
    private static OfficialTokenApi.Reply reply(JsonObject data,String code,boolean success) {
        JsonObject body=new JsonObject(); body.addProperty("success",success); body.addProperty("code",code); body.addProperty("message",code); body.add("data",data);
        return new OfficialTokenApi.Reply(Set.of("PREPARED","RESERVED","RESULT_UNKNOWN").contains(code) ? 202 : 200,body);
    }
}
