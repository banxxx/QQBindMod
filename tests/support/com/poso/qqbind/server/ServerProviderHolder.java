package com.poso.qqbind.server;
import net.minecraft.server.MinecraftServer;
/** No Minecraft classes, player data or actual game thread are loaded. */
public final class ServerProviderHolder {
    public static volatile MinecraftServer server;
    public static Provider get() { return new Provider(); }
    public static final class Provider { public MinecraftServer getCurrentServer() { return server; } }
}
