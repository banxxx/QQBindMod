package com.poso.qqbind.official;

/** Safe protocol errors: never attach a request, identity, token, path, or cause. */
public final class OfficialException extends RuntimeException {
    private final String code;

    public OfficialException(String code) {
        super(switch (code) {
            case "INVALID_INPUT" -> "Invalid official protocol input";
            case "SCOPE_MISMATCH" -> "Official scope does not match";
            case "TOKEN_INVALID" -> "Token is invalid";
            case "CONFLICT" -> "Official operation conflicts";
            case "RESULT_UNKNOWN" -> "Operation result requires confirmation";
            case "DB_UNAVAILABLE" -> "Center is temporarily unavailable";
            case "SCHEMA_MISMATCH" -> "Official center schema does not match";
            case "LIMIT_EXCEEDED" -> "Official storage limit exceeded";
            case "UNAUTHORIZED" -> "Official request is not authorized";
            case "NOT_SUPPORTED" -> "Official capability is not connected";
            case "RESTART_REQUIRED" -> "A full process restart is required for this binding configuration";
            default -> "Official storage is unavailable";
        });
        this.code = code;
    }

    public String code() { return code; }
}
