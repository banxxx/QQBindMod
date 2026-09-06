package com.poso.qqbind.api.constants;

/**
 * API 常量定义
 */
public final class ApiConstants {
    private ApiConstants() {}

    // HTTP 状态码（也可直接使用 HttpURLConnection 常量）
    public static final int HTTP_OK = 200;
    public static final int HTTP_BAD_REQUEST = 400;
    public static final int HTTP_UNAUTHORIZED = 401;
    public static final int HTTP_FORBIDDEN = 403;
    public static final int HTTP_NOT_FOUND = 404;
    public static final int HTTP_METHOD_NOT_ALLOWED = 405;
    public static final int HTTP_CONFLICT = 409;
    public static final int HTTP_INTERNAL_ERROR = 500;
    public static final int HTTP_SERVICE_UNAVAILABLE = 503;

    // 通用消息
    public static final String MSG_METHOD_NOT_ALLOWED = "Method Not Allowed";
    public static final String MSG_UNAUTHORIZED = "Unauthorized";
    public static final String MSG_INTERNAL_ERROR = "Internal Server Error";

    // 内容类型
    public static final String CONTENT_TYPE_JSON = "application/json; charset=UTF-8";
}