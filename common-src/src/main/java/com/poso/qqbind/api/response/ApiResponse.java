package com.poso.qqbind.api.response;

/**
 * 统一 API 响应格式
 * @param <T> 响应数据类型
 */
public class ApiResponse<T> {
    private final boolean success;
    private final int code;
    private final String message;
    private final T data;

    private ApiResponse(boolean success, int code, String message, T data) {
        this.success = success;
        this.code = code;
        this.message = message;
        this.data = data;
    }

    // ---- 成功响应 ----
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(true, ErrorCode.SUCCESS.getCode(), message, data);
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(true, ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    // ---- 失败响应 ----
    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, errorCode.getCode(), message, null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(false, code, message, null);
    }

    // ---- Getters ----
    public boolean isSuccess() { return success; }
    public int getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }

}