package com.poso.qqbind.core;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.ToIntFunction;

/**
 * 玩家延迟的读取口：原版挪过位置——1.20.1 是 ServerPlayer 的公开字段 latency，
 * 1.21.1 改成 connection.latency()。按字段名反射在生产环境会被改名打回 0，
 * 所以由各平台入口注入一行直读代码，让映射在编译期跟着走。
 * 未注入时返回 -1，调用方按"未测得"省略字段。
 */
public final class PlayerPing {

    private static volatile ToIntFunction<ServerPlayer> reader;

    private PlayerPing() {
    }

    public static void register(ToIntFunction<ServerPlayer> pingReader) {
        reader = pingReader;
    }

    /** 未注入读取口时返回 -1，调用方用 &lt; 0 过滤 */
    public static int get(ServerPlayer player) {
        ToIntFunction<ServerPlayer> current = reader;
        return current == null ? -1 : current.applyAsInt(player);
    }
}
