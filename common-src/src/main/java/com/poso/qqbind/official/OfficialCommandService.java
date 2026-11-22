package com.poso.qqbind.official;

import com.google.gson.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Persist-before-send, at-most-once commands. Never stores payload text.
 * A crashed DISPATCHED receipt is UNKNOWN and is never automatically replayed. */
public final class OfficialCommandService {
    @FunctionalInterface public interface Executor {
        String execute(String action,String payload,BooleanSupplier active);
        default JsonObject takeOutput() { return null; }
    }
    private final OfficialScope scope;
    private final OfficialMirrorService mirror;
    private final Executor executor;
    private final BooleanSupplier active;
    public OfficialCommandService(OfficialScope scope,OfficialMirrorService mirror,Executor executor,BooleanSupplier active) {
        this.scope=scope; this.mirror=mirror; this.executor=executor; this.active=active;
    }
    static void validate(JsonObject receipts) {
        if (receipts.size()>10000) throw new OfficialException("LIMIT_EXCEEDED");
        for (var entry : receipts.entrySet()) {
            JsonObject value=entry.getValue().getAsJsonObject();
            OfficialScope.uuid(entry.getKey()); OfficialMirrorModel.fields(value,Set.of("request_key","payload_hash","action","state"));
            if (!text(value,"request_key").matches("[a-f0-9]{64}") || !text(value,"payload_hash").matches("[a-f0-9]{64}")
                    || !Set.of("broadcast","console").contains(text(value,"action"))
                    || !Set.of("DISPATCHED","SUCCEEDED","REJECTED","UNKNOWN").contains(text(value,"state"))) throw new OfficialException("STORAGE_ERROR");
        }
    }
    public JsonObject dispatch(JsonObject request) {
        Set<String> expected=new HashSet<>(scope.wire().keySet()); expected.addAll(Set.of("operation_id","request_key","action","payload","config_revision"));
        if (!request.keySet().equals(expected)) throw new OfficialException("INVALID_INPUT");
        String id=OfficialScope.uuid(text(request,"operation_id")), key=text(request,"request_key"), action=text(request,"action"), payload=text(request,"payload");
        OfficialScope.revision(text(request,"config_revision"));
        if (!key.matches("[a-f0-9]{64}") || !Set.of("broadcast","console").contains(action) || payload.isBlank()
                || payload.length()>(action.equals("console") ? 200 : 1000) || payload.chars().anyMatch(c -> c<32 || c==127)) throw new OfficialException("INVALID_INPUT");
        String digest=OfficialIntent.hash(request.get("payload"));
        boolean claimed=mirror.atomicOnIo(root -> {
            if (!active.getAsBoolean()) throw new OfficialException("NOT_SUPPORTED");
            if (!root.has("command_receipts")) root.add("command_receipts",new JsonObject());
            JsonObject receipts=root.getAsJsonObject("command_receipts"), prior=receipts.getAsJsonObject(id);
            for (var entry : receipts.entrySet()) if (text(entry.getValue().getAsJsonObject(),"request_key").equals(key) && !entry.getKey().equals(id)) throw new OfficialException("CONFLICT");
            if (prior!=null) {
                if (!text(prior,"request_key").equals(key) || !text(prior,"payload_hash").equals(digest) || !text(prior,"action").equals(action)) throw new OfficialException("CONFLICT");
                return false;
            }
            if (receipts.size()>=10000) throw new OfficialException("LIMIT_EXCEEDED");
            JsonObject receipt=new JsonObject(); receipt.addProperty("request_key",key); receipt.addProperty("payload_hash",digest);
            receipt.addProperty("action",action); receipt.addProperty("state","DISPATCHED"); receipts.add(id,receipt); return true;
        });
        JsonObject transientOutput=null;
        if (claimed) {
            String outcome;
            try { outcome=executor.execute(action,payload,active); } catch (RuntimeException e) { outcome="UNKNOWN"; }
            finally { transientOutput=executor.takeOutput(); }
            if (!Set.of("SUCCEEDED","REJECTED","UNKNOWN").contains(outcome)) outcome="UNKNOWN";
            final String result=outcome;
            mirror.atomicOnIo(root -> { root.getAsJsonObject("command_receipts").getAsJsonObject(id).addProperty("state",result); return null; });
        }
        JsonObject reply=status(id);
        if (transientOutput!=null && text(reply,"state").equals("SUCCEEDED")) {
            for (String field : List.of("output","result","truncated")) if (transientOutput.has(field)) reply.add(field,transientOutput.get(field));
        }
        return reply;
    }
    public JsonObject status(String id) {
        OfficialScope.uuid(id); JsonObject receipts=mirror.durableOnIo().getAsJsonObject("command_receipts");
        JsonObject stored=receipts==null ? null : receipts.getAsJsonObject(id); if (stored==null) throw new OfficialException("SCOPE_MISMATCH");
        JsonObject result=new JsonObject(); result.addProperty("protocol_version",2); result.add("scope",scope.wire()); result.addProperty("operation_id",id);
        String state=text(stored,"state"); result.addProperty("state",state.equals("DISPATCHED") ? "UNKNOWN" : state); result.addProperty("kind","command"); return result;
    }
    private static String text(JsonObject value,String name) { return OfficialModState.string(value,name); }
}
