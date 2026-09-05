package com.poso.qqbind.neoforge;

import com.mojang.logging.LogUtils;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.api.WebServer;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.server.ServerProviderHolder;
import com.poso.qqbind.storage.JsonStorage;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * 模组主类，Forge 模组入口.
 * 负责模组的初始化、注册事件监听器、启动 HTTP API 服务器，并在服务器关闭时释放资源.
 * 通过 {@link #commonSetup(FMLCommonSetupEvent)} 在游戏加载阶段完成配置加载、
 * 绑定管理器初始化以及 WebServer 的启动.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  12:45
 * @since : 1.0
 */
@Mod(QQBindMod.MODID)
public class QQBindMod {
    public static final String MODID = "qqbind";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static WebServer webServer;
    private static BindingManager bindingManager;

    public QQBindMod(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);

        NeoForge.EVENT_BUS.register(new EventHandler());
        NeoForge.EVENT_BUS.register(new ServerCommands());

        LOGGER.info("QQBindMod (NeoForge) initialized!");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ServerProviderHolder.setProvider(new NeoForgeServerProvider());

            QQBindConfig.load();

            JsonStorage storage = new JsonStorage();
            bindingManager = new BindingManager(storage);

            webServer = new WebServer(bindingManager);
            webServer.start();

            LOGGER.info("QQBindMod common setup completed! HTTP server started on port {}", QQBindConfig.HTTP_PORT);
        });
    }

    public static BindingManager getBindingManager() {
        return bindingManager;
    }
}