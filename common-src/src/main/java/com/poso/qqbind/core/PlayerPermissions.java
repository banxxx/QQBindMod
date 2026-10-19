package com.poso.qqbind.core;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * 管理员判定的读取口：1.20.1/1.21.1 用 hasPermissions(4)，26.x 换成 permissions() 的具名权限，
 * 由各平台入口注入一行直读代码。
 * 未注入时按非管理员处理——这里判成 true 等于让所有人免过绑定校验，所以保守取 false。
 */
public final class PlayerPermissions {

    private static volatile Predicate<ServerPlayer> operatorCheck;

    private PlayerPermissions() {
    }

    public static void register(Predicate<ServerPlayer> operatorChecker) {
        operatorCheck = operatorChecker;
    }

    public static boolean isOperator(ServerPlayer player) {
        Predicate<ServerPlayer> current = operatorCheck;
        return current != null && current.test(player);
    }
}
