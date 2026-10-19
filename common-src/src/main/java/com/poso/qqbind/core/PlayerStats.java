package com.poso.qqbind.core;

import net.minecraft.server.level.ServerPlayer;

import java.util.function.ToIntBiFunction;

/**
 * 玩家统计的读取口：统计标识对象的类型随版本变（1.x 是 ResourceLocation，26.x 是 Identifier），
 * 所以这里只把它当不透明键传给各平台入口注入的读取代码，类型在平台侧确定、编译期跟着走。
 * 未注入时返回 0，与"读不到统计"的既有行为一致。
 */
public final class PlayerStats {

    private static volatile ToIntBiFunction<ServerPlayer, Object> reader;

    private PlayerStats() {
    }

    public static void register(ToIntBiFunction<ServerPlayer, Object> statReader) {
        reader = statReader;
    }

    /** statKey 是原版 Stats 类里的统计常量，本类不解释其类型 */
    public static int get(ServerPlayer player, Object statKey) {
        ToIntBiFunction<ServerPlayer, Object> current = reader;
        return current == null ? 0 : current.applyAsInt(player, statKey);
    }
}
