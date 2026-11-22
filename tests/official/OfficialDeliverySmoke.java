package com.poso.qqbind.official;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Small end-of-delivery smoke. All paths are NEW temporary directories,
 * identities/codes/tokens are synthetic; no sockets, MC, SQL or config lookup. */
public final class OfficialDeliverySmoke {
    private static final OfficialScope S=new OfficialScope("11111111-1111-4111-8111-111111111111","SYNTHETIC_GROUP_A","22222222-2222-4222-8222-222222222222");
    private static final String USER="SYNTHETIC_USER_A";
    private static final JsonArray WIRE=new JsonArray();
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
    private static String text(JsonObject value,String key) { return OfficialModState.string(value,key); }
    private static OfficialRuntimeSettings settings() {
        JsonObject document=new JsonObject(); document.addProperty("bindingPlatform","qq_official"); document.addProperty("environmentId",S.environmentId());
        document.addProperty("bindingGroupId",S.groupId()); document.addProperty("serverId",S.serverId()); document.addProperty("storageMode","local");
        document.addProperty("apiToken","SYNTHETIC_NOT_A_REAL_TOKEN_123456789"); JsonObject official=new JsonObject();
        official.addProperty("configurationRevision","1"); official.addProperty("allowCenterSync",false); document.add("official",official); return OfficialRuntimeSettings.parse(document);
    }
    private static final class Session implements AutoCloseable {
        final OfficialModState state; final OfficialMirrorService mirror; final OfficialLocalAuthority local; final OfficialTokenService tokens; final OfficialBindingApi api;
        Session(Path directory,boolean fresh) {
            state=new OfficialModState(directory,S,fresh,"local"); mirror=new OfficialMirrorService(S,state,null,() -> false); local=new OfficialLocalAuthority(S,mirror);
            tokens=new OfficialTokenService(S,state,local);
            OfficialNativeAdmin.Repository repository=new OfficialNativeAdmin.Repository() {
                public JsonArray rows() { return local.rows(); } public JsonObject operation(String id) { return local.operation(id); }
                public OfficialCenterMutations mutations(OfficialCenterMutations.PolicyGuard guard) { return new OfficialCenterMutations(S,local,guard); }
                public void committed(JsonObject operation) { local.acknowledgeOnIo(); } public JsonArray operations() { return local.operations(); }
            };
            api=new OfficialBindingApi(settings(),repository,tokens,() -> {});
        }
        JsonObject post(String path,JsonObject request) {
            var reply=api.dispatch("POST","/api/v2/binding/"+path,request);
            JsonObject example=new JsonObject(); example.addProperty("endpoint",path); example.addProperty("status",reply.status()); example.add("response",reply.body());
            JsonObject sent=request.deepCopy(); if (sent.has("token_code")) sent.addProperty("token_code","123456"); example.add("request",sent);
            WIRE.add(example);
            check(reply.body().get("success").getAsBoolean(),"mutation failed: "+reply.body().get("code")); return reply.body().getAsJsonObject("data").getAsJsonObject("operation");
        }
        public void close() { mirror.close(); state.close(); }
    }
    private static JsonObject bind(Session session,String game) {
        OfficialTokenService.Issued code=session.tokens.issue(game); JsonObject request=S.wire(); String id=UUID.randomUUID().toString();
        request.addProperty("operation_id",id); request.addProperty("request_key",OfficialIntent.hash(new JsonPrimitive(id))); request.addProperty("user_id",USER);
        request.addProperty("game_id",game); request.addProperty("token_generation",code.tokenGeneration()); request.addProperty("token_code",code.tokenCode());
        request.addProperty("policy_revision","1"); request.add("nickname_basis",JsonNull.INSTANCE); actor(request);
        JsonObject row=session.post("bind",request); check(text(row,"state").equals("COMMITTED"),"local bind did not commit");
        JsonObject root=session.state.snapshot();
        check(text(root.getAsJsonObject("reservations").getAsJsonObject(id),"state").equals("CONSUMED"),"token not consumed atomically");
        check(root.getAsJsonObject("pending_effects").has(id+":"+game.toLowerCase(Locale.ROOT)),"outbox missing");
        return row;
    }
    private static void actor(JsonObject request) {
        JsonObject actor=new JsonObject(),authorization=new JsonObject(); actor.addProperty("kind","qq_member"); actor.addProperty("id",USER);
        authorization.addProperty("scope","self"); authorization.addProperty("policy_revision","1"); request.add("actor",actor); request.add("authorization",authorization);
    }
    private static JsonObject pending(Session session) {
        var code=session.tokens.issue("Player_C"); String id=UUID.randomUUID().toString();
        JsonObject intent=new JsonObject(), body=new JsonObject(), auth=new JsonObject();
        intent.addProperty("environment_id",S.environmentId()); intent.addProperty("group_id",S.groupId()); intent.addProperty("actor_kind","qq_member");
        intent.addProperty("actor_id",USER); intent.addProperty("user_id",USER); intent.addProperty("server_id",S.serverId());
        intent.addProperty("token_generation",code.tokenGeneration()); intent.addProperty("policy_revision","1"); intent.addProperty("kind","bind"); intent.add("nickname_basis",JsonNull.INSTANCE);
        auth.addProperty("scope","self"); auth.addProperty("policy_revision","1"); body.add("authorization",auth);
        JsonArray servers=new JsonArray(); servers.add(S.serverId()); body.add("target_server_ids",servers); body.addProperty("game_id","Player_C"); body.addProperty("game_key","player_c"); intent.add("intent",body);
        var mutations=new OfficialCenterMutations(S,session.local,(action,request) -> new OfficialCenterMutations.PolicyLease() { public String policyRevision() { return "1"; } public void close() { } });
        String key=OfficialIntent.hash(new JsonPrimitive(id)); mutations.prepare(id,key,intent); mutations.claimReservation(id,intent);
        session.tokens.reserve(id,USER,"player_c",code.tokenGeneration(),code.tokenCode());
        JsonObject request=S.wire(); request.addProperty("operation_id",id); request.addProperty("request_key",key); request.addProperty("user_id",USER);
        request.addProperty("game_id","Player_C"); request.addProperty("token_generation",code.tokenGeneration()); request.addProperty("policy_revision","1");
        request.add("nickname_basis",JsonNull.INSTANCE); request.addProperty("resume",true); actor(request);
        return request;
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("official-final-synthetic-"); String first; JsonObject resume;
        try (Session session=new Session(root,true)) {
            first=text(bind(session,"Player_A"),"operation_id"); bind(session,"Player_B");
            check(session.mirror.activeGames(USER).size()==2,"one-to-many missing");
            JsonObject request=S.wire(); String id=UUID.randomUUID().toString(); request.addProperty("operation_id",id); request.addProperty("request_key",OfficialIntent.hash(new JsonPrimitive(id)));
            request.addProperty("kind","unbind_game"); request.addProperty("target_user_id",USER); actor(request);
            JsonArray targets=new JsonArray(); JsonObject target=new JsonObject(); target.addProperty("game_key","player_a"); target.addProperty("expected_revision","1"); targets.add(target); request.add("targets",targets);
            JsonObject row=session.post("unbind",request); check(row.getAsJsonArray("effects").get(0).getAsJsonObject().get("revision").getAsString().equals("2"),"revoke revision");
            check(!session.mirror.isBoundFast("Player_A") && session.mirror.isBoundFast("Player_B"),"selective unbind");
            check(text(session.post("unbind",request),"operation_id").equals(id),"duplicate mutation changed ID");
            AtomicInteger calls=new AtomicInteger();
            OfficialCommandService commands=new OfficialCommandService(S,session.mirror,(action,payload,active) -> { calls.incrementAndGet(); return "SUCCEEDED"; },() -> true);
            JsonObject command=S.wire(); command.addProperty("operation_id",UUID.randomUUID().toString()); command.addProperty("request_key","a".repeat(64));
            command.addProperty("action","broadcast"); command.addProperty("payload","SYNTHETIC_PAYLOAD_DO_NOT_PERSIST"); command.addProperty("config_revision","1");
            commands.dispatch(command); commands.dispatch(command); check(calls.get()==1,"command replayed");
            check(!Files.readString(root.resolve("official-state.json")).contains("SYNTHETIC_PAYLOAD_DO_NOT_PERSIST"),"payload persisted");
            resume=pending(session);
            System.out.println("PASS local multi-account/atomic token-outbox/revoke/idempotency and command once");
        }
        try (Session session=new Session(root,false)) {
            check(!session.mirror.isBoundFast("Player_A") && session.mirror.isBoundFast("Player_B"),"restart lost state");
            JsonObject request=S.wire(); var reply=session.api.dispatch("GET","/api/v2/binding/operations/"+first,request);
            check(reply.body().get("success").getAsBoolean(),"restart operation query");
            check(!session.mirror.isBoundFast("Player_C"),"pending bind was auto-committed");
            JsonObject row=session.post("bind",resume); check(text(row,"state").equals("COMMITTED") && session.mirror.isBoundFast("Player_C"),"fresh authorized resume failed");
            System.out.println("PASS persisted unresolved reservation requires explicit resume after restart");
            System.out.println("PASS restart and original-operation lookup");
        }
        Files.writeString(Path.of("delivery-wire-synthetic.json"),WIRE.toString());
    }
}
