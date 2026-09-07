package com.poso.qqbind.fabric;

import com.poso.qqbind.server.IServerProvider;
import net.minecraft.server.MinecraftServer;

public class FabricServerProvider implements IServerProvider {
    private static MinecraftServer serverInstance;

    public static void setServer(MinecraftServer server) {
        serverInstance = server;
    }

    @Override
    public MinecraftServer getCurrentServer() {
        return serverInstance;
    }
}