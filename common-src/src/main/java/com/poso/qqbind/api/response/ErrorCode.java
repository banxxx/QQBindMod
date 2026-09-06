package com.poso.qqbind.api.response;

/**
 * API 错误码定义
 * 格式：分类(2位) + 具体错误(3位)
 * 例如：1xx 通用，2xx 参数，3xx 权限，4xx 业务
 */
public enum ErrorCode {
    // 通用成功
    SUCCESS(0, "成功"),

    // 通用错误 (100-199)
    INTERNAL_ERROR(500, "服务器内部错误"),
    UNKNOWN_ERROR(999, "未知错误"),

    // 参数错误 (200-299)
    MISSING_PARAMETER(400, "缺少必要参数"),
    INVALID_PARAMETER(400, "参数无效"),
    INVALID_TOKEN(400, "无效或已过期的令牌"),
    MISSING_GAME_ID_OR_TOKEN(400, "缺少 gameId 或 token"),
    MISSING_QQ(400, "缺少 qq 参数"),
    MISSING_PLAYER_NAME(400, "缺少玩家名称"),

    // 权限错误 (300-399)
    UNAUTHORIZED(401, "未授权，请提供有效的 Authorization 头"),
    FORBIDDEN(403, "无权限访问该资源"),

    // 业务逻辑错误 (400-499)
    GAME_ID_ALREADY_BOUND(409, "该游戏ID已被绑定"),
    QQ_ALREADY_BOUND(409, "该QQ已绑定游戏ID"),
    GAME_ID_NOT_FOUND(404, "游戏ID未找到"),
    QQ_NOT_FOUND(404, "QQ号未找到"),
    PLAYER_NOT_FOUND(404, "玩家不在线"),
    BIND_FAILED(400, "绑定失败"),
    UNBIND_FAILED(400, "解绑失败"),
    INVALID_TOKEN_ERROR(400, "令牌无效或已过期"),
    SERVER_NOT_AVAILABLE(503, "服务器不可用"),
    ;

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}