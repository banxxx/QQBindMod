package com.poso.qqbind.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 控制台命令接口的准入判定与输出整理。
 *
 * 执行侧的权限天花板等同服务器控制台，这里的黑名单只负责挡掉不可逆操作，
 * 不构成访问控制；访问控制由 consoleToken 与机器人端的发送者权限负责。
 */
public final class ConsoleCommandPolicy {
    /** 不可逆或自我提权类命令，配置无法放开 */
    private static final Set<String> HARD_BANNED = Set.of(
            "stop", "restart", "reload", "deploy",
            "ban", "ban-ip", "pardon", "pardon-ip", "schedule");
    // 命令名整词校验：只取到第一个空白为止，且不允许冒号——
    // 否则 `minecraft:stop` 会被截成 `minecraft` 从而绕过硬禁清单
    private static final Pattern COMMAND_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern SECRET_HINT =
            Pattern.compile("(?i)\\b(seed|rcon|password|passwd|token|secret)\\b");
    private static final int MAX_COMMAND_LENGTH = 200;
    private static final int MAX_OUTPUT_LINES = 30;
    private static final int MAX_LINE_CHARS = 500;

    public enum Verdict {
        OK,
        EMPTY,
        UNRECOGNIZED,
        TOO_LONG,
        MULTILINE,
        LEADING_SLASH,
        BANNED
    }

    private ConsoleCommandPolicy() {
    }

    /**
     * 命令首词（小写，不含参数）。首词含非法字符（如冒号）时返回 null。
     */
    public static String commandName(String command) {
        if (command == null) {
            return null;
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String first = trimmed.split("\\s", 2)[0];
        return COMMAND_NAME.matcher(first).matches() ? first.toLowerCase(Locale.ROOT) : null;
    }

    /**
     * 判定命令是否允许执行：先查格式，再叠加硬禁清单与配置里的 consoleBannedCommands。
     */
    public static Verdict check(String command) {
        if (command == null || command.trim().isEmpty()) {
            return Verdict.EMPTY;
        }
        String trimmed = command.trim();
        if (trimmed.length() > MAX_COMMAND_LENGTH) {
            return Verdict.TOO_LONG;
        }
        if (trimmed.startsWith("/")) {
            return Verdict.LEADING_SLASH;
        }
        if (trimmed.indexOf('\n') >= 0 || trimmed.indexOf('\r') >= 0) {
            return Verdict.MULTILINE;
        }
        String name = commandName(trimmed);
        if (name == null) {
            return Verdict.UNRECOGNIZED;
        }
        if (HARD_BANNED.contains(name) || bannedFromConfig().contains(name)) {
            return Verdict.BANNED;
        }
        return Verdict.OK;
    }

    private static Set<String> bannedFromConfig() {
        String configured = com.poso.qqbind.QQBindConfig.CONSOLE_BANNED_COMMANDS;
        if (configured == null || configured.trim().isEmpty()) {
            return Set.of();
        }
        Set<String> names = new java.util.HashSet<>();
        for (String part : configured.split(",")) {
            String name = part.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** 整理后的输出：回显行 + 是否发生过截断 */
    public record Formatted(List<String> lines, boolean truncated) {
    }

    /**
     * 输出整理：按行切分、去掉空行、逐行截断、限制总行数，并对疑似口令的行做脱敏。
     */
    public static Formatted formatOutput(String response) {
        List<String> lines = new ArrayList<>();
        boolean truncated = false;
        if (response == null || response.isEmpty()) {
            return new Formatted(lines, false);
        }
        for (String raw : response.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (lines.size() >= MAX_OUTPUT_LINES) {
                truncated = true;
                break;
            }
            if (SECRET_HINT.matcher(line).find()) {
                lines.add("[已隐藏：内容疑似包含口令或种子]");
            } else if (line.length() > MAX_LINE_CHARS) {
                lines.add(line.substring(0, MAX_LINE_CHARS) + "...");
                truncated = true;
            } else {
                lines.add(line);
            }
        }
        return new Formatted(lines, truncated);
    }

    public static String message(Verdict verdict) {
        return switch (verdict) {
            case EMPTY -> "命令为空";
            case UNRECOGNIZED -> "无法识别的命令名";
            case TOO_LONG -> "命令过长（上限 " + MAX_COMMAND_LENGTH + " 字符）";
            case MULTILINE -> "不支持多条命令";
            case LEADING_SLASH -> "命令不要带 / 前缀";
            case BANNED -> "该命令已被禁用";
            default -> "不允许";
        };
    }
}
