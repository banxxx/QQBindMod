package com.poso.qqbind.api.exception;

import com.poso.qqbind.api.response.ErrorCode;

/**
 * 参数无效异常
 */
public class InvalidParameterException extends ApiException {

    public InvalidParameterException(ErrorCode errorCode) {
        super(errorCode);
    }

    public InvalidParameterException(String message) {
        super(ErrorCode.INVALID_PARAMETER, message);
    }

    public InvalidParameterException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}