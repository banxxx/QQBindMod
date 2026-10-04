package com.poso.qqbind.utils;

import java.util.regex.Pattern;

/**
 * Minecraft 玩家名（游戏 ID）合法性校验。
 *
 * 与原版一致：仅允许字母、数字、下划线，长度 1-16。
 * 这是命令注入防护的一部分：gameId 会被拼进控制台命令（whitelist add/remove），
 * 而 Brigadier 的 GameProfileArgument 还接受选择器（如 @a）和带引号的特殊名字，
 * 未校验的外部输入可借此操纵白名单；一旦未来新增其他 executeCommand 调用点，
 * 同样的输入就可能升级为任意控制台命令。
 */
public final class GameIdValidator {
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    private GameIdValidator() {
    }

    public static boolean isValid(String gameId) {
        return gameId != null && VALID.matcher(gameId).matches();
    }
}
