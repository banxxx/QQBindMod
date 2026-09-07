package com.poso.qqbind.fabric;

import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.api.WebServer;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.server.ServerProviderHolder;
import com.poso.qqbind.storage.JsonStorage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 模组主入口，实现 ModInitializer。
 * 负责初始化配置、绑定管理器、HTTP 服务，并注册事件监听和命令。
 */
public class FabricModInitializer implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("qqbind-fabric");
    private static BindingManager bindingManager;
    private static WebServer webServer;

    @Override
    public void onInitialize() {
        // 1. 设置服务器提供者（Fabric 实现）
        ServerProviderHolder.setProvider(new FabricServerProvider());

        // 2. 加载配置（若不存在则创建默认配置）
        QQBindConfig.load();

        // 3. 初始化存储和绑定管理器
        JsonStorage storage = new JsonStorage();
        bindingManager = new BindingManager(storage);

        // 4. 启动 HTTP API 服务
        webServer = new WebServer(bindingManager);
        webServer.start();
        LOGGER.info("HTTP server started on port {}", QQBindConfig.HTTP_PORT);

        // 5. 注册事件监听（玩家登录/登出）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                FabricEventHandler.onPlayerLogin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                FabricEventHandler.onPlayerLogout(handler.getPlayer()));

        // 6. 注册命令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                FabricServerCommands.register(dispatcher));

        // 7. 注册操作限制（所有拦截回调）
        FabricRestrictionHandler.register();

        // 8. 监听服务器启动事件，将服务器实例缓存到 FabricServerProvider
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            FabricServerProvider.setServer(server);
            LOGGER.info("Fabric server instance cached.");
        });

        // 9. 注册服务器停止时关闭 HTTP 服务，并清理缓存的服务器实例
        ServerLifecycleEvents.SERVER_STOPPING.register((server) -> {
            if (webServer != null) {
                webServer.stop();
                LOGGER.info("HTTP server stopped");
            }
            // 清理缓存，避免内存泄漏
            FabricServerProvider.setServer(null);
        });

        LOGGER.info("QQBindMod (Fabric) initialized successfully!");
    }

    public static BindingManager getBindingManager() {
        return bindingManager;
    }
}