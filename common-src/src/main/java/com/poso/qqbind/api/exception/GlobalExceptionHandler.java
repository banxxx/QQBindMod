package com.poso.qqbind.api.exception;

import com.google.gson.JsonObject;
import com.poso.qqbind.api.response.ApiResponse;
import com.poso.qqbind.api.response.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 全局异常处理器，负责捕获 ApiException 并返回标准错误响应
 */
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理异常并发送响应
     * @param exchange HTTP 交换
     * @param ex 异常
     */
    public static void handleException(HttpExchange exchange, Exception ex) {
        try {
            ApiResponse<?> response;
            int statusCode;

            if (ex instanceof ApiException) {
                ApiException apiEx = (ApiException) ex;
                response = ApiResponse.error(apiEx.getErrorCode(), apiEx.getMessage());
                statusCode = mapErrorCodeToHttpStatus(apiEx.getErrorCode());
                LOGGER.warn("API 异常: {} - {}", apiEx.getErrorCode().getCode(), apiEx.getMessage());
            } else {
                // 未捕获的其他异常
                response = ApiResponse.error(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.getMessage());
                statusCode = ErrorCode.INTERNAL_ERROR.getCode();
                LOGGER.error("未处理的异常", ex);
            }

            sendResponse(exchange, statusCode, response);
        } catch (Exception e) {
            LOGGER.error("处理异常响应时发生错误", e);
            try {
                sendSimpleError(exchange, 500, "Internal Server Error");
            } catch (IOException ioEx) {
                LOGGER.error("发送简单错误响应失败", ioEx);
            }
        }
    }

    /**
     * 将错误码映射为 HTTP 状态码
     */
    private static int mapErrorCodeToHttpStatus(ErrorCode errorCode) {
        int code = errorCode.getCode();
        if (code >= 400 && code < 500) {
            return code;
        }
        // 根据错误类型映射
        return switch (errorCode) {
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case GAME_ID_NOT_FOUND, QQ_NOT_FOUND, PLAYER_NOT_FOUND -> 404;
            case GAME_ID_ALREADY_BOUND, QQ_ALREADY_BOUND -> 409;
            case SERVER_NOT_AVAILABLE -> 503;
            default -> 500;
        };
    }

    /**
     * 发送 JSON 响应
     */
    private static void sendResponse(HttpExchange exchange, int statusCode, ApiResponse<?> response) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("success", response.isSuccess());
        json.addProperty("code", response.getCode());
        json.addProperty("message", response.getMessage());
        if (response.getData() != null) {
            // 由于 data 是泛型，此处无法直接序列化，由调用者自行处理
            // 但在我们的使用中，data 通常为 null，因此此方法仅用于错误响应
        }
        String jsonStr = json.toString();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, jsonStr.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(jsonStr.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * 发送简单的错误响应（当无法构建标准响应时）
     */
    private static void sendSimpleError(HttpExchange exchange, int statusCode, String message) throws IOException {
        String response = "{\"success\":false,\"code\":" + statusCode + ",\"message\":\"" + message + "\"}";
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, response.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response.getBytes(StandardCharsets.UTF_8));
        }
    }
}
