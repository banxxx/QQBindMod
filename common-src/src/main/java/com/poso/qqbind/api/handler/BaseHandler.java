package com.poso.qqbind.api.handler;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.api.exception.GlobalExceptionHandler;
import com.poso.qqbind.api.response.ApiResponse;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 所有 API 处理器的抽象基类，提供公共方法：
 * - 请求方法验证
 * - 授权验证
 * - JSON 响应发送
 * - 异常处理
 */
public abstract class BaseHandler implements HttpHandler {
    protected static final Logger LOGGER = LoggerFactory.getLogger(BaseHandler.class);
    protected final Gson gson = new Gson();

    /**
     * 处理请求的抽象方法，子类实现具体逻辑
     * @param exchange HTTP 交换
     * @throws Exception 业务异常
     */
    protected abstract void doHandle(HttpExchange exchange) throws Exception;

    /**
     * 入口方法，捕获异常并调用 doHandle
     */
    @Override
    public final void handle(HttpExchange exchange) {
        try {
            doHandle(exchange);
        } catch (Exception e) {
            GlobalExceptionHandler.handleException(exchange, e);
        }
    }

    /**
     * 验证请求方法是否匹配预期（如果不匹配，自动返回 405）
     * @param exchange HTTP 交换
     * @param expectedMethod 期望的方法（GET/POST等）
     * @return true 如果方法匹配，否则已发送405响应并返回 false
     */
    protected boolean validateMethod(HttpExchange exchange, String expectedMethod) throws IOException {
        if (expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            return true;   // 方法匹配，验证通过
        }
        sendError(exchange, 405, "Method Not Allowed");
        return false;      // 方法不匹配，验证失败
    }

    /**
     * 验证授权（检查 Authorization 头）
     * @param exchange HTTP 交换
     * @return true 如果授权通过，否则已发送401响应并返回 false
     */
    protected boolean validateAuth(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + QQBindConfig.API_TOKEN;
        if (auth != null && auth.equals(expected)) {
            return true;
        }
        sendError(exchange, 401, "Unauthorized");
        return false;
    }

    /**
     * 发送 JSON 成功响应
     * @param exchange HTTP 交换
     * @param data 响应数据 (JsonObject)
     */
    protected void sendSuccess(HttpExchange exchange, JsonObject data) throws IOException {
        ApiResponse<JsonObject> response = ApiResponse.success(data);
        sendJson(exchange, 200, response, data);
    }

    /**
     * 发送 JSON 成功响应（无数据）
     */
    protected void sendSuccess(HttpExchange exchange) throws IOException {
        ApiResponse<Void> response = ApiResponse.success();
        sendJson(exchange, 200, response, null);
    }

    /**
     * 发送 JSON 错误响应（使用 ErrorCode）
     */
    protected void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        ApiResponse<Void> response = ApiResponse.error(statusCode, message);
        sendJson(exchange, statusCode, response, null);
    }

    /**
     * 发送 JSON 错误响应（使用 ErrorCode 枚举）
     */
    protected void sendError(HttpExchange exchange, com.poso.qqbind.api.response.ErrorCode errorCode) throws IOException {
        ApiResponse<Void> response = ApiResponse.error(errorCode);
        sendJson(exchange, mapErrorCodeToStatus(errorCode), response, null);
    }

    /**
     * 底层 JSON 发送方法
     */
    private void sendJson(HttpExchange exchange, int statusCode, ApiResponse<?> apiResponse, JsonObject data) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("success", apiResponse.isSuccess());
        json.addProperty("code", apiResponse.getCode());
        json.addProperty("message", apiResponse.getMessage());
        if (data != null) {
            json.add("data", data);
        }
        String jsonStr = gson.toJson(json);
        byte[] bytes = jsonStr.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * 从请求体中读取 JSON 字符串
     */
    protected String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 从查询字符串中获取参数
     */
    protected String getQueryParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        String prefix = key + "=";
        int start = query.indexOf(prefix);
        if (start == -1) return null;
        start += prefix.length();
        int end = query.indexOf("&", start);
        return end == -1 ? query.substring(start) : query.substring(start, end);
    }

    /**
     * 将 ErrorCode 映射为 HTTP 状态码（简化版）
     */
    private int mapErrorCodeToStatus(com.poso.qqbind.api.response.ErrorCode errorCode) {
        int code = errorCode.getCode();
        if (code >= 400 && code < 500) {
            return code;
        }
        return switch (errorCode) {
            case UNAUTHORIZED -> 401;
            case FORBIDDEN -> 403;
            case GAME_ID_NOT_FOUND, QQ_NOT_FOUND, PLAYER_NOT_FOUND -> 404;
            case GAME_ID_ALREADY_BOUND, QQ_ALREADY_BOUND -> 409;
            case SERVER_NOT_AVAILABLE -> 503;
            default -> 500;
        };
    }
}