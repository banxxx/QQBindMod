package com.poso.qqbind.forge;

import com.mojang.logging.LogUtils;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.api.WebServer;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.server.ServerProviderHolder;
import com.poso.qqbind.storage.JsonStorage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * 模组主类，Forge 模组入口.
 * 负责模组的初始化、注册事件监听器、启动 HTTP API 服务器，并在服务器关闭时释放资源.
 * 通过 {@link #commonSetup(FMLCommonSetupEvent)} 在游戏加载阶段完成配置加载、
 * 绑定管理器初始化以及 WebServer 的启动.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
@Mod(QQBindMod.MODID)
public class QQBindMod {
    public static final String MODID = "qqbind";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static WebServer webServer;
    private static BindingManager bindingManager;

    public QQBindMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // 注册通用设置事件
        modEventBus.addListener(this::commonSetup);

        // 注册事件处理器到 Forge 事件总线
        MinecraftForge.EVENT_BUS.register(new EventHandler());
        MinecraftForge.EVENT_BUS.register(new ServerCommands());

        LOGGER.info("QQBindMod (Forge) initialized!");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // 设置服务器提供者
            ServerProviderHolder.setProvider(new ForgeServerProvider());

            // 加载配置（会在首次运行时创建默认文件）
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