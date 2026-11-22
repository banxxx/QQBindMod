package com.poso.qqbind.official;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.*;

/** Native operator requests. Permission is refreshed on each game tick; I/O
 * only sees the short-lived grant. No binding work runs on the game thread. */
public final class OfficialNativeAdmin {
    public interface Repository {
        JsonArray rows();
        JsonObject operation(String id);
        OfficialCenterMutations mutations(OfficialCenterMutations.PolicyGuard guard);
        void committed(JsonObject operation);
        default JsonArray operations() { throw new OfficialException("NOT_SUPPORTED"); }
    }
    private final OfficialScope scope;
    private final String revision;
    private final Repository repository;
    private final ConcurrentLinkedQueue<Request> pending = new ConcurrentLinkedQueue<>();
    private static final class Request {
        final String action, argument, actor, id = UUID.randomUUID().toString();
        final BooleanSupplier permission;
        final Consumer<String> reply;
        final long expires = System.nanoTime() + 120_000_000_000L;
        volatile boolean allowed = true;
        volatile long refreshed=System.nanoTime();
        volatile String result;
        Request(String action,String argument,String actor,BooleanSupplier permission,Consumer<String> reply) {
            this.action=action; this.argument=argument; this.actor=actor; this.permission=permission; this.reply=reply;
        }
        void check() { long now=System.nanoTime(); if (!allowed || now-expires >= 0 || now-refreshed>1_000_000_000L) throw new OfficialException("UNAUTHORIZED"); }
    }
    public OfficialNativeAdmin(OfficialScope scope,String revision,Repository repository) {
        this.scope=scope; this.revision=revision; this.repository=repository;
    }
    public boolean enqueue(String action,String argument,String actor,BooleanSupplier permission,Consumer<String> reply,Consumer<Runnable> submit) {
        if (pending.size() >= 16 || !Set.of("unbind","operation","cancel","retry","list").contains(action) || !permission.getAsBoolean()) return false;
        OfficialScope.identifier(actor);
        if (action.equals("unbind")) OfficialScope.key(argument);
        if (Set.of("operation","cancel","retry").contains(action)) OfficialScope.uuid(argument);
        Request request=new Request(action,argument,actor,permission,reply); pending.add(request);
        try { submit.accept(() -> run(request)); return true; }
        catch (RuntimeException e) { pending.remove(request); request.allowed=false; return false; }
    }
    /** All permission/source callbacks and delivery happen on the game thread. */
    public void tick() {
        for (Request request : pending) {
            try { request.allowed &= request.permission.getAsBoolean() && System.nanoTime()-request.expires < 0; request.refreshed=System.nanoTime(); }
            catch (RuntimeException e) { request.allowed=false; }
            if (!request.allowed) { pending.remove(request); continue; }
            if (request.result != null) {
                pending.remove(request);
                if (request.allowed) request.reply.accept(request.result);
            }
        }
    }
    private void run(Request request) {
        try {
            request.check();
            if (request.action.equals("list")) {
                JsonArray rows=repository.rows(); StringBuilder result=new StringBuilder("当前范围有效绑定："); int count=0;
                for (JsonElement item : rows) { JsonObject row=item.getAsJsonObject();
                    if (text(row,"state").equals("active")) { if (++count <= 50) result.append("\n").append(text(row,"game_id")); }
                }
                request.result=result.append("\n共 ").append(count).append(" 个；最多显示50个，不显示官方用户标识。").toString(); return;
            }
            OfficialCenterMutations.PolicyGuard guard=(action,intent) -> {
                request.check();
                if (!text(intent,"actor_kind").equals("game_admin") || !text(intent,"actor_id").equals(request.actor)
                        || text(intent,"kind").equals("bind")) throw new OfficialException("UNAUTHORIZED");
                return new OfficialCenterMutations.PolicyLease() { public String policyRevision() { request.check(); return revision; } public void close() { } };
            };
            if (request.action.equals("unbind")) {
                JsonObject row=null; String key=OfficialScope.key(request.argument);
                for (JsonElement item : repository.rows()) if (text(item.getAsJsonObject(),"game_key").equals(key)) row=item.getAsJsonObject();
                if (row == null || !text(row,"state").equals("active")) throw new OfficialException("SCOPE_MISMATCH");
                JsonObject intent=new JsonObject(), body=new JsonObject(), authorization=new JsonObject(), target=new JsonObject();
                intent.addProperty("environment_id",scope.environmentId()); intent.addProperty("group_id",scope.groupId());
                intent.addProperty("actor_kind","game_admin"); intent.addProperty("actor_id",request.actor); intent.addProperty("user_id",text(row,"user_id"));
                intent.addProperty("policy_revision",revision); intent.addProperty("kind","unbind_game");
                for (String field : List.of("server_id","token_generation","nickname_basis")) intent.add(field,JsonNull.INSTANCE);
                authorization.addProperty("scope","group"); authorization.addProperty("policy_revision",revision); body.add("authorization",authorization);
                JsonArray targets=new JsonArray(), servers=new JsonArray();
                SortedSet<String> serverIds=new TreeSet<>(); serverIds.add(scope.serverId());
                JsonObject previous=repository.operation(text(row,"last_operation_id"));
                if (previous==null || !text(previous,"state").equals("COMMITTED")) throw new OfficialException("STORAGE_ERROR");
                for (JsonElement server : previous.getAsJsonObject("intent").getAsJsonArray("target_server_ids")) serverIds.add(server.getAsString());
                serverIds.forEach(servers::add); body.add("target_server_ids",servers);
                target.addProperty("game_key",key); target.add("expected_revision",row.get("revision")); targets.add(target); body.add("targets",targets); intent.add("intent",body);
                OfficialCenterMutations mutations=repository.mutations(guard);
                mutations.prepare(request.id,OfficialIntent.hash(new JsonPrimitive(request.id)),intent); request.check();
                JsonObject operation=mutations.commit(request.id,intent);
                if (text(operation,"state").equals("COMMITTED")) try { repository.committed(operation); } catch (RuntimeException ignored) { /* Keep the confirmed commit. */ }
                request.result=describe(operation);
            } else {
                JsonObject operation=repository.operation(request.argument);
                if (operation == null) throw new OfficialException("SCOPE_MISMATCH");
                if (request.action.equals("cancel")) {
                    // A native administrator may abort, never submit another user's bind.
                    OfficialCenterMutations.PolicyGuard cancelGuard=(action,intent) -> {
                        request.check(); if (action != OfficialCenterMutations.Action.ABORT) throw new OfficialException("UNAUTHORIZED");
                        return new OfficialCenterMutations.PolicyLease() { public String policyRevision() { request.check(); return revision; } public void close() { } };
                    };
                    operation=repository.mutations(cancelGuard).abort(request.argument,normalized(operation));
                }
                if (request.action.equals("retry")) {
                    if (!text(operation,"actor_kind").equals("game_admin") || !text(operation,"actor_id").equals(request.actor)
                            || text(operation,"kind").equals("bind")) throw new OfficialException("UNAUTHORIZED");
                    operation=repository.mutations(guard).commit(request.argument,normalized(operation));
                    if (text(operation,"state").equals("COMMITTED")) try { repository.committed(operation); } catch (RuntimeException ignored) { }
                }
                request.result=describe(operation);
            }
        } catch (OfficialException e) { request.result="官方操作："+e.code()+"；查询编号："+(request.action.equals("unbind") ? request.id : request.argument)+"。未知结果不要重复解绑。"; }
        catch (RuntimeException e) { request.result="官方操作结果待确认；查询编号："+request.id; }
    }
    static JsonObject normalized(JsonObject row) {
        JsonObject result=new JsonObject();
        for (String name : List.of("environment_id","group_id","actor_kind","actor_id","token_generation","policy_revision","kind","intent","nickname_basis")) result.add(name,row.get(name).deepCopy());
        result.add("user_id",row.get("target_user_id")); result.add("server_id",row.get("original_server_id")); return result;
    }
    static String describe(JsonObject operation) {
        return "操作编号："+text(operation,"operation_id")+"；状态："+text(operation,"state")+"；结果："+text(operation,"result_code")+"。提交成功与白名单/在线同步完成分别确认。";
    }
    private static String text(JsonObject value,String name) { return OfficialModState.string(value,name); }
    public void close() { for (Request request : pending) request.allowed=false; pending.clear(); }
}
