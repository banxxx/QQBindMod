package com.poso.qqbind.forge;

import com.poso.qqbind.server.IServerProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Forge 平台的服务器提供者实现，通过 ServerLifecycleHooks 获取当前服务器实例。
 *
 * @author POSOO
 * @version 1.0
 * @since 1.0
 */
public class ForgeServerProvider implements IServerProvider {
    @Override
    public MinecraftServer getCurrentServer() {
        return ServerLifecycleHooks.getCurrentServer();
    }
}