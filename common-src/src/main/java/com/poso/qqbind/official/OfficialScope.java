package com.poso.qqbind.official;

import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.UUID;

/** Scope and validation shared by all loader-independent official components. */
public record OfficialScope(String environmentId, String groupId, String serverId) {
    public OfficialScope {
        uuid(environmentId);
        identifier(groupId);
        uuid(serverId);
    }

    public JsonObject wire() {
        JsonObject result = new JsonObject();
        result.addProperty("protocol_version", 2);
        result.addProperty("environment_id", environmentId);
        result.addProperty("group_id", groupId);
        result.addProperty("server_id", serverId);
        return result;
    }

    public static String uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException e) { throw new OfficialException("INVALID_INPUT"); }
    }

    public static String identifier(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 1
                || value.codePointCount(0, value.length()) > 64
                || value.codePoints().anyMatch(c -> c <= 32 || c == 127 || c >= 0xd800 && c <= 0xdfff)) {
            throw new OfficialException("INVALID_INPUT");
        }
        int first = value.codePointAt(0), last = value.codePointBefore(value.length());
        if (Character.isWhitespace(first) || Character.isSpaceChar(first) || first == 0x85
                || Character.isWhitespace(last) || Character.isSpaceChar(last) || last == 0x85) {
            throw new OfficialException("INVALID_INPUT");
        }
        return value;
    }

    public static String game(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_]{1,16}")) throw new OfficialException("INVALID_INPUT");
        return value;
    }

    public static String key(String value) { return game(value).toLowerCase(Locale.ROOT); }

    public static long revision(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException e) { throw new OfficialException("INVALID_INPUT"); }
    }
}
