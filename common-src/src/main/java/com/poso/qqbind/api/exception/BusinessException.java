package com.poso.qqbind.api.exception;

import com.poso.qqbind.api.response.ErrorCode;

/**
 * 业务逻辑异常
 */
public class BusinessException extends ApiException {
    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }
    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}