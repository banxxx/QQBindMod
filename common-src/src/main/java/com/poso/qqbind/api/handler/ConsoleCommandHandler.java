package com.poso.qqbind.api.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.api.exception.BusinessException;
import com.poso.qqbind.api.exception.InvalidParameterException;
import com.poso.qqbind.api.response.ErrorCode;
import com.poso.qqbind.core.CommandExecutor;
import com.poso.qqbind.core.CommandRateLimiter;
import com.poso.qqbind.core.ConsoleCommandPolicy;
import com.sun.net.httpserver.HttpExchange;

/**
 * 控制台命令处理器 - POST /api/console
 *
 * 以控制台身份执行 Minecraft 原生命令并回显其输出。仅在配置了 consoleToken 时注册，
 * 且使用的是与绑定接口相互独立的令牌。
 * 请求体: {"command": "whitelist list", "timeoutMs": 3000, "actor": "qq:12345"}
 */
public class ConsoleCommandHandler extends BaseHandler {
    private static final long DEFAULT_TIMEOUT_MS = 3000;
    private static final long MIN_TIMEOUT_MS = 200;
    private static final long MAX_TIMEOUT_MS = 8000;
    private static final int MAX_ACTOR_CHARS = 40;

    @Override
    protected void doHandle(HttpExchange exchange) throws Exception {
        if (!validateMethod(exchange, "POST")) return;
        if (!validateBearer(exchange, QQBindConfig.CONSOLE_TOKEN)) return;

        String ip = clientIp(exchange);
        if (CommandRateLimiter.isBlocked(ip)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        String body = readRequestBody(exchange);
        JsonObject json = body == null || body.isEmpty() ? null : gson.fromJson(body, JsonObject.class);
        String command = json != null && json.has("command") ? json.get("command").getAsString() : "";
        String actor = json != null && json.has("actor") ? sanitizeActor(json.get("actor").getAsString()) : "-";
        long timeoutMs = json != null && json.has("timeoutMs")
                ? clampTimeout(json.get("timeoutMs").getAsLong())
                : DEFAULT_TIMEOUT_MS;

        ConsoleCommandPolicy.Verdict verdict = ConsoleCommandPolicy.check(command);
        if (verdict != ConsoleCommandPolicy.Verdict.OK) {
            LOGGER.warn("[console] rejected ip={} actor={} reason={} command={}",
                    ip, actor, verdict, abbreviate(command));
            String detail = ConsoleCommandPolicy.message(verdict);
            if (verdict == ConsoleCommandPolicy.Verdict.BANNED) {
                detail += "：" + ConsoleCommandPolicy.commandName(command);
            }
            throw new InvalidParameterException(ErrorCode.COMMAND_NOT_ALLOWED, detail);
        }

        String trimmed = command.trim();
        CommandRateLimiter.recordCall(ip);
        long startedAt = System.currentTimeMillis();
        try {
            CommandExecutor.CommandOutput output = CommandExecutor.executeWithOutput(trimmed, timeoutMs);
            ConsoleCommandPolicy.Formatted formatted = ConsoleCommandPolicy.formatOutput(output.response());
            long elapsedMs = System.currentTimeMillis() - startedAt;
            LOGGER.info("[console] ip={} actor={} status=executed result={} lines={} elapsedMs={} command={}",
                    ip, actor, output.result(), formatted.lines().size(), elapsedMs, trimmed);

            JsonArray lines = new JsonArray();
            formatted.lines().forEach(lines::add);
            JsonObject data = new JsonObject();
            data.addProperty("status", "executed");
            data.addProperty("result", output.result());
            data.addProperty("elapsedMs", elapsedMs);
            data.addProperty("truncated", formatted.truncated());
            data.add("output", lines);
            sendSuccess(exchange, data);
        } catch (IllegalStateException e) {
            // 等待超时后任务仍在主线程队列里，命令很可能已经执行，不能对外宣称"已取消"
            long elapsedMs = System.currentTimeMillis() - startedAt;
            LOGGER.warn("[console] ip={} actor={} status=timeout elapsedMs={} command={} detail={}",
                    ip, actor, elapsedMs, trimmed, e.getMessage());

            JsonObject data = new JsonObject();
            data.addProperty("status", "timeout");
            data.addProperty("elapsedMs", elapsedMs);
            data.add("output", new JsonArray());
            sendSuccess(exchange, data);
        }
    }

    private static long clampTimeout(long requested) {
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, requested));
    }

    /**
     * actor 由调用方提供，会进审计日志，去掉换行防止伪造日志行
     */
    private static String sanitizeActor(String raw) {
        String clean = raw.replaceAll("[\\r\\n]", "");
        return clean.length() > MAX_ACTOR_CHARS ? clean.substring(0, MAX_ACTOR_CHARS) : clean;
    }

    private static String abbreviate(String raw) {
        String clean = raw.replaceAll("[\\r\\n]", " ");
        return clean.length() > 80 ? clean.substring(0, 80) + "..." : clean;
    }
}
