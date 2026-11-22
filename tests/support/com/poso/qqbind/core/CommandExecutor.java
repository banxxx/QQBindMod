package com.poso.qqbind.core;
/** Core-only regression harness: no game commands or runtime access. */
public final class CommandExecutor {
    public static String executeOfficial(String action,String payload,java.util.function.BooleanSupplier active) {
        throw new com.poso.qqbind.official.OfficialException("NOT_SUPPORTED");
    }
    public static com.google.gson.JsonObject executeOfficialResult(String action,String payload,java.util.function.BooleanSupplier active) {
        throw new com.poso.qqbind.official.OfficialException("NOT_SUPPORTED");
    }
}
