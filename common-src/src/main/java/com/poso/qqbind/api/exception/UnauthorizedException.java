package com.poso.qqbind.api.exception;

import com.poso.qqbind.api.response.ErrorCode;

/**
 * 未授权异常，用于认证失败或 token 无效的情况。
 */
public class UnauthorizedException extends ApiException {
    public UnauthorizedException() {
        super(ErrorCode.UNAUTHORIZED);
    }

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }

    public UnauthorizedException(ErrorCode errorCode) {
        super(errorCode);
    }
}