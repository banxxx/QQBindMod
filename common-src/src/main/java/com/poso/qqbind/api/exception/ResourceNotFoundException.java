package com.poso.qqbind.api.exception;

import com.poso.qqbind.api.response.ErrorCode;

/**
 * 资源不存在异常，用于查询不到指定资源（如游戏ID、QQ号、玩家等）。
 */
public class ResourceNotFoundException extends ApiException {
    public ResourceNotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ResourceNotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public ResourceNotFoundException(String message) {
        super(ErrorCode.GAME_ID_NOT_FOUND, message);
    }
}