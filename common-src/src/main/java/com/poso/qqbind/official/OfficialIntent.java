package com.poso.qqbind.official;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Python-compatible normalized intent bytes, never a hash of a six-digit code. */
public final class OfficialIntent {
    private OfficialIntent() { }
    public static final int MAX_BYTES = 1024 * 1024;

    public static String canonical(JsonElement value) {
        StringBuilder out = new StringBuilder(); append(value, out, 0);
        if (out.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new OfficialException("LIMIT_EXCEEDED");
        return out.toString();
    }

    public static String hash(JsonElement value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical(value).getBytes(StandardCharsets.UTF_8)));
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("INVALID_INPUT"); }
    }

    private static void append(JsonElement value, StringBuilder out, int depth) {
        if (depth > 20 || out.length() > MAX_BYTES) throw new OfficialException("LIMIT_EXCEEDED");
        if (value == null || value.isJsonNull()) { out.append("null"); return; }
        if (value.isJsonObject()) {
            out.append('{'); boolean first = true;
            for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) {
                if (!key.matches("[a-z_]+")) throw new OfficialException("INVALID_INPUT");
                if (!first) out.append(','); first = false;
                quote(key, out); out.append(':'); append(value.getAsJsonObject().get(key), out, depth + 1);
            }
            out.append('}');
        } else if (value.isJsonArray()) {
            out.append('['); boolean first = true;
            for (JsonElement item : value.getAsJsonArray()) {
                if (!first) out.append(','); first = false; append(item, out, depth + 1);
            }
            out.append(']');
        } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) quote(value.getAsString(), out);
        else throw new OfficialException("INVALID_INPUT");
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) throw new OfficialException("INVALID_INPUT");
                out.append(c).append(text.charAt(++i)); continue;
            }
            if (Character.isLowSurrogate(c)) throw new OfficialException("INVALID_INPUT");
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
                    else out.append(c); // Python ensure_ascii=False, including U+2028/U+2029.
                }
            }
        }
        out.append('"');
    }

    static String text(JsonObject value, String field) { return OfficialModState.string(value, field); }
    private static void fields(JsonObject value, String... fields) {
        if (value == null || !value.keySet().equals(Set.of(fields))) throw new OfficialException("INVALID_INPUT");
    }
    private static boolean absent(JsonObject value, String field) { return value.has(field) && value.get(field).isJsonNull(); }
    private static String revision(JsonObject value, String field) {
        String result = text(value, field); OfficialScope.revision(result); return result;
    }
    private static void keyRevision(JsonObject value) {
        fields(value, "game_key", "expected_revision");
        String key = text(value, "game_key");
        if (!OfficialScope.key(key).equals(key)) throw new OfficialException("INVALID_INPUT");
        revision(value, "expected_revision");
    }

    /** Reject non-normalized, extra/private fields before accepting persisted intent. */
    public static JsonObject validate(JsonObject value) {
        try {
            fields(value, "environment_id", "group_id", "actor_kind", "actor_id", "user_id", "server_id",
                "token_generation", "policy_revision", "kind", "intent", "nickname_basis");
            OfficialScope.uuid(text(value, "environment_id")); OfficialScope.identifier(text(value, "group_id"));
            OfficialScope.identifier(text(value, "actor_id")); OfficialScope.identifier(text(value, "user_id"));
            String policy = revision(value, "policy_revision"), kind = text(value, "kind"), actor = text(value, "actor_kind");
            if (!Set.of("qq_member", "game_admin").contains(actor)) throw new OfficialException("INVALID_INPUT");
            JsonObject intent = value.getAsJsonObject("intent"), auth = intent.getAsJsonObject("authorization");
            fields(auth, "scope", "policy_revision"); String scope = text(auth, "scope");
            if (!Set.of("self", "group", "environment").contains(scope) || !policy.equals(text(auth, "policy_revision"))
                    || scope.equals("self") && (!actor.equals("qq_member") || !text(value, "actor_id").equals(text(value, "user_id")))) {
                throw new OfficialException("INVALID_INPUT");
            }
            JsonArray servers = intent.getAsJsonArray("target_server_ids");
            if (servers.size() > 100) throw new OfficialException("LIMIT_EXCEEDED");
            String previous = "";
            for (JsonElement server : servers) {
                if (!server.isJsonPrimitive() || !server.getAsJsonPrimitive().isString()) throw new OfficialException("INVALID_INPUT");
                String id = OfficialScope.uuid(server.getAsString());
                if (id.compareTo(previous) <= 0) throw new OfficialException("INVALID_INPUT"); previous = id;
            }
            if (kind.equals("bind")) {
                fields(intent, "game_id", "game_key", "authorization", "target_server_ids");
                if (!OfficialScope.key(text(intent, "game_id")).equals(text(intent, "game_key"))) throw new OfficialException("INVALID_INPUT");
                OfficialScope.uuid(text(value, "server_id")); OfficialScope.uuid(text(value, "token_generation"));
                if (actor.equals("qq_member") && !text(value, "actor_id").equals(text(value, "user_id"))) throw new OfficialException("INVALID_INPUT");
                if (!absent(value, "nickname_basis")) keyRevision(value.getAsJsonObject("nickname_basis"));
            } else {
                if (!Set.of("unbind_game", "unbind_user").contains(kind)) throw new OfficialException("INVALID_INPUT");
                fields(intent, "targets", "authorization", "target_server_ids");
                if (!absent(value, "server_id") || !absent(value, "token_generation") || !absent(value, "nickname_basis")) throw new OfficialException("INVALID_INPUT");
                JsonArray targets = intent.getAsJsonArray("targets");
                if (targets.size() < 1 || targets.size() > 500 || kind.equals("unbind_game") && targets.size() != 1) throw new OfficialException("LIMIT_EXCEEDED");
                previous = "";
                for (JsonElement item : targets) {
                    JsonObject target = item.getAsJsonObject(); keyRevision(target); String key = text(target, "game_key");
                    if (key.compareTo(previous) <= 0) throw new OfficialException("INVALID_INPUT"); previous = key;
                }
            }
            canonical(value); return value.deepCopy();
        } catch (OfficialException e) { throw e; }
        catch (Exception e) { throw new OfficialException("INVALID_INPUT"); }
    }
}
