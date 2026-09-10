package com.poso.qqbind.neoforge;

import com.mojang.logging.LogUtils;
import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.api.WebServer;
import com.poso.qqbind.core.BindingManager;
import com.poso.qqbind.core.PlayerActivityManager;
import com.poso.qqbind.server.ServerProviderHolder;
import com.poso.qqbind.storage.DataStorage;
import com.poso.qqbind.storage.JsonStorage;
import com.poso.qqbind.storage.RemoteStorage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(new EventHandler());
        NeoForge.EVENT_BUS.register(new ServerCommands());
        NeoForge.EVENT_BUS.register(new RestrictionHandler());

        LOGGER.info("QQBindMod (NeoForge) initialized!");
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ServerProviderHolder.setProvider(new NeoForgeServerProvider());
            QQBindConfig.load();

            try {
                PlayerActivityManager.init();
            } catch (Exception e) {
                LOGGER.error("初始化 PlayerActivityManager 失败", e);
            }

            DataStorage storage;
            if ("local".equalsIgnoreCase(QQBindConfig.STORAGE_MODE)) {
                storage = new JsonStorage();
                LOGGER.info("Using local JsonStorage.");
            } else {
                storage = new RemoteStorage();
                LOGGER.info("Using RemoteStorage with TiDB.");
            }
            bindingManager = new BindingManager(storage);

            webServer = new WebServer(bindingManager);
            webServer.start();

            LOGGER.info("QQBindMod common setup completed! HTTP server started on port {}", QQBindConfig.HTTP_PORT);
        });
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        // 先标记所有在线玩家为已退出
        try {
            MinecraftServer server = event.getServer();
            Set<UUID> onlineUuids = server.getPlayerList().getPlayers()
                    .stream().map(ServerPlayer::getUUID).collect(Collectors.toSet());
            PlayerActivityManager.markAllOnlinePlayersAsQuit(onlineUuids);
        } catch (Exception e) {
            LOGGER.warn("标记在线玩家退出时异常: {}", e.getMessage());
        }

        // 关闭管理器（最后一次同步写盘）
        try {
            PlayerActivityManager.shutdown();
        } catch (Exception e) {
            LOGGER.error("关闭 PlayerActivityManager 失败", e);
        }

        if (bindingManager != null) {
            bindingManager.close();
        }
        if (webServer != null) {
            webServer.stop();
        }
        LOGGER.info("QQBindMod resources released.");
    }

    public static BindingManager getBindingManager() {
        return bindingManager;
    }
}