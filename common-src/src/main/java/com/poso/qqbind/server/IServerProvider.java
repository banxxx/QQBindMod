package com.poso.qqbind.server;

import net.minecraft.server.MinecraftServer;

/**
 * 服务器提供者接口，用于获取当前 MinecraftServer 实例.
 * 各平台（Forge/NeoForge）需实现此接口，并在模组主类中注入.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  01:14
 * @since : 1.0
 */

public interface IServerProvider {
    MinecraftServer getCurrentServer();
}
