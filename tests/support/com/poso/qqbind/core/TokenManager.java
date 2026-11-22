package com.poso.qqbind.core;

import net.minecraft.server.level.ServerPlayer;

/** The official game host must never call legacy token methods. */
public final class TokenManager {
    public static String getOrRefreshToken(ServerPlayer player) { throw new AssertionError("Legacy code generation"); }
    public static void removeTokenForPlayer(ServerPlayer player) { throw new AssertionError("Legacy code cleanup"); }
}
