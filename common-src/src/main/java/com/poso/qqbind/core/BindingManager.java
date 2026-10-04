package com.poso.qqbind.core;

import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.server.ServerProviderHolder;
import com.poso.qqbind.storage.DataStorage;
import com.poso.qqbind.storage.JsonStorage;
import com.poso.qqbind.storage.RemoteStorage;
import com.poso.qqbind.utils.GameIdValidator;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 绑定业务逻辑核心类，负责管理 QQ 号与游戏 ID 的绑定关系.
 * @author : Ban
 * @version : 1.0
 * @createTime: 2026-09-05  00:30
 * @since : 1.0
 */
public class BindingManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BindingManager.class);
    private final DataStorage storage;
    private DataStorage localFallback; // 仅当使用 hybrid 时保留本地引用

    /** 后台回源/重载执行器：登录校正、reload 等含 DB IO 的操作绝不进主线程 */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "QQBind-BindingIo");
        t.setDaemon(true);
        return t;
    });

    public BindingManager(DataStorage storage) {
        this.storage = storage;
        // 注册恢复回调：DB 重连并补同步后，重校验在线玩家的限制状态
        if (storage instanceof RemoteStorage) {
            ((RemoteStorage) storage).setOnRecoveredCallback(this::revalidateOnlinePlayers);
        }
        // 如果配置为 hybrid，则 primaryStorage 应该是 RemoteStorage，但我们仍保留 JsonStorage 作为备用
        // 但 RemoteStorage 内部已有 fallback，所以这里直接使用 primaryStorage
        this.storage.load();
    }

    /**
     * 绑定 QQ 号与游戏 ID
     */
    public BindResult bind(String qq, String gameId) {
        if (!GameIdValidator.isValid(gameId)) {
            LOGGER.warn("拒绝绑定：gameId 非法：{}", gameId);
            return new BindResult(false, "游戏ID不合法（仅允许字母、数字、下划线，长度1-16）");
        }

        // 检查 gameId 是否已被绑定
        String existingQQ = storage.getQQ(gameId);
        if (existingQQ != null) {
            return new BindResult(false, "该游戏ID已被绑定 (QQ: " + existingQQ + ")");
        }

        // 检查 qq 是否已绑定其他 ID
        String existingGameId = storage.getGameId(qq);
        if (existingGameId != null) {
            return new BindResult(false, "该QQ已绑定游戏ID: " + existingGameId);
        }

        // 执行绑定
        storage.save(qq, gameId);

        // 执行 whitelist add 命令
        CommandExecutor.addWhitelist(gameId);

        // ========== 绑定成功后检查玩家是否在线，若在线则解除限制 ==========
        unrestrictOnlinePlayer(gameId);

        LOGGER.info("Bound QQ {} to game ID {}", qq, gameId);
        return new BindResult(true, "绑定成功！您现在可以登录服务器了。");
    }

    /**
     * 若玩家在线且处于受限状态，解除限制并推送绑定成功通知。
     * 异常不影响绑定/同步本身。
     * 整体（含玩家查找）在主线程执行：玩家列表与玩家对象只能在主线程访问。
     */
    private void unrestrictOnlinePlayer(String gameId) {
        MainThread.post(() -> {
            try {
                MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
                if (server == null) {
                    return;
                }
                ServerPlayer player = server.getPlayerList().getPlayerByName(gameId);
                if (player == null || !PlayerStateManager.isRestricted(player)) {
                    return;
                }
                PlayerStateManager.setRestricted(player, false);
                LOGGER.info("玩家 {} 绑定生效，已解除限制", player.getScoreboardName());

                String title = QQBindConfig.formatMessage(QQBindConfig.BIND_SUCCESS_TITLE, null);
                String subtitle = QQBindConfig.formatMessage(QQBindConfig.BIND_SUCCESS_SUBTITLE, null);
                String actionBar = QQBindConfig.formatMessage(QQBindConfig.BIND_SUCCESS_ACTION_BAR, null);

                player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
                player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
                player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 10));
                player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(actionBar)));
            } catch (Exception e) {
                LOGGER.warn("解除玩家限制时发生异常（不影响绑定本身）: {}", e.getMessage());
            }
        });
    }

    /**
     * 若玩家在线，立即应用未绑定限制（旁观者 + 提示令牌）。
     * 整体（含玩家查找）在主线程执行。
     */
    private void restrictOnlinePlayer(String gameId) {
        MainThread.post(() -> {
            try {
                MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
                if (server == null) {
                    return;
                }
                ServerPlayer player = server.getPlayerList().getPlayerByName(gameId);
                if (player == null) {
                    return;
                }
                PlayerStateManager.setRestricted(player, true);
                PlayerStateManager.sendRestrictionMessage(player);
                LOGGER.info("玩家 {} 绑定已失效，已应用限制", player.getScoreboardName());
            } catch (Exception e) {
                LOGGER.warn("限制玩家时发生异常: {}", e.getMessage());
            }
        });
    }

    /**
     * 下行同步绑定（插件已直写中心库成功时调用）：
     * 仅更新本地镜像与缓存，不写数据库；RemoteStorage 降级期间也能实时生效。
     */
    public void applySyncedBinding(String qq, String gameId) {
        if (!GameIdValidator.isValid(gameId)) {
            LOGGER.warn("拒绝同步绑定：gameId 非法：{}", gameId);
            return;
        }
        if (storage instanceof RemoteStorage) {
            ((RemoteStorage) storage).syncFromCentral(qq, gameId);
        } else {
            storage.save(qq, gameId);
        }
        CommandExecutor.addWhitelist(gameId);
        unrestrictOnlinePlayer(gameId);
    }

    /**
     * 下行同步解绑（插件已从中心库删除时调用）：仅删本地镜像与缓存。
     */
    public void applySyncedUnbind(String gameId) {
        if (!GameIdValidator.isValid(gameId)) {
            LOGGER.warn("拒绝同步解绑：gameId 非法：{}", gameId);
            return;
        }
        if (storage instanceof RemoteStorage) {
            ((RemoteStorage) storage).syncRemovalFromCentral(gameId);
        } else {
            storage.remove(gameId);
        }
        CommandExecutor.removeWhitelist(gameId);
        restrictOnlinePlayer(gameId);
    }

    /**
     * 数据库恢复并补同步后，重校验在线玩家：
     * 已绑定但仍受限 → 解除限制；未绑定但未受限（非 OP）→ 应用限制。
     * 三段式执行：主线程快照玩家 → 当前（后台）线程查绑定 → 主线程应用状态，
     * 保证 DB 查询不进主线程、玩家对象只在主线程访问。
     */
    public void revalidateOnlinePlayers() {
        // 与 JOIN 处理一致：关闭绑定检查时不得对玩家施加/恢复限制
        if (!QQBindConfig.ENABLE_WHITELIST_CHECK) {
            return;
        }
        try {
            // 第 1 步：主线程快照（纯数据，不持有玩家对象引用）
            List<Snapshot> snapshots = MainThread.call(3000, server -> {
                List<Snapshot> list = new ArrayList<>();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    list.add(new Snapshot(player.getName().getString(),
                            PlayerStateManager.isRestricted(player),
                            player.hasPermissions(4)));
                }
                return list;
            });

            // 第 2 步：在当前（后台）线程查绑定关系，DB/镜像 IO 不进主线程
            Map<String, Boolean> boundByName = new HashMap<>();
            for (Snapshot snap : snapshots) {
                boundByName.put(snap.name, storage.getQQ(snap.name) != null);
            }

            // 第 3 步：回主线程应用限制状态（此处的 unrestrict/restrict 内部也是 post，
            // 但此时已在主线程，会立即同步执行）
            MainThread.post(() -> {
                for (Snapshot snap : snapshots) {
                    boolean bound = boundByName.getOrDefault(snap.name, false);
                    if (bound && snap.restricted) {
                        unrestrictOnlinePlayer(snap.name);
                    } else if (!bound && !snap.restricted && !snap.isOp) {
                        restrictOnlinePlayer(snap.name);
                    }
                }
            });
            LOGGER.info("数据库恢复后已完成在线玩家重校验（{} 人）", snapshots.size());
        } catch (Exception e) {
            LOGGER.warn("在线玩家重校验失败: {}", e.getMessage());
        }
    }

    /** revalidateOnlinePlayers 的主线程快照条目 */
    private static class Snapshot {
        final String name;
        final boolean restricted;
        final boolean isOp;

        Snapshot(String name, boolean restricted, boolean isOp) {
            this.name = name;
            this.restricted = restricted;
            this.isOp = isOp;
        }
    }

    public void close() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (storage instanceof RemoteStorage) {
            ((RemoteStorage) storage).close();
        } else if (storage instanceof JsonStorage) {
            // JsonStorage 无需关闭
        }
    }

    public DataStorage getStorage() {
        return storage;
    }

    /**
     * 通过游戏 ID 解绑
     */
    public boolean unbindByGameId(String gameId) {
        if (!GameIdValidator.isValid(gameId)) {
            LOGGER.warn("拒绝解绑：gameId 非法：{}", gameId);
            return false;
        }
        String qq = storage.getQQ(gameId);
        if (qq == null) {
            return false;
        }

        // 移除存储数据
        storage.remove(gameId);
        // 移除白名单
        CommandExecutor.removeWhitelist(gameId);

        // ---- 若玩家在线，立即应用限制 ----
        restrictOnlinePlayer(gameId);

        LOGGER.info("Unbound game ID {} (QQ: {})", gameId, qq);
        return true;
    }

    /**
     * 通过 QQ 号解绑
     */
    public boolean unbindByQQ(String qq) {
        String gameId = storage.getGameId(qq);
        if (gameId == null) {
            return false;
        }

        // 移除存储数据
        storage.remove(gameId);
        // 移除白名单
        CommandExecutor.removeWhitelist(gameId);

        // ---- 若玩家在线，立即应用限制 ----
        restrictOnlinePlayer(gameId);

        LOGGER.info("Unbound QQ {} (game ID: {})", qq, gameId);
        return true;
    }

    /**
     * 检查游戏 ID 是否已绑定（完整版，可能发起 DB 查询）。
     * 只允许在后台线程调用（HTTP 线程、健康检查线程），不得在主线程使用。
     */
    public boolean isBound(String gameId) {
        LOGGER.info("isBound({}) = {}", gameId, storage.getQQ(gameId) != null);
        return storage.getQQ(gameId) != null;
    }

    /**
     * 登录路径专用的非阻塞绑定判定：只读内存缓存与本地镜像，绝不发起 DB IO，
     * 不会阻塞 server tick。结果可能因缓存未热而滞后，配合 {@link #verifyBindingAsync(String)}。
     */
    public boolean isBoundFast(String gameId) {
        return storage.getQQNonBlocking(gameId) != null;
    }

    /**
     * 后台回源校正：缓存未命中导致登录时按"未绑定"处理了的玩家，
     * 异步查一次权威数据，若实际已绑定则回主线程解除限制。
     * DB 抖动时最多占用一个后台线程，不影响主线程。
     */
    public void verifyBindingAsync(String gameId) {
        ioExecutor.execute(() -> {
            try {
                if (isBound(gameId)) {
                    MainThread.post(() -> unrestrictOnlinePlayer(gameId));
                }
            } catch (Exception e) {
                LOGGER.warn("异步校验 {} 的绑定状态失败: {}", gameId, e.getMessage());
            }
        });
    }

    /**
     * 获取游戏 ID 绑定的 QQ 号
     */
    public String getQQ(String gameId) {
        return storage.getQQ(gameId);
    }

    /**
     * 通过 QQ 号获取绑定的游戏 ID
     */
    public String getGameIdByQQ(String qq) {
        return storage.getGameId(qq);
    }

    /**
     * 获取所有绑定数据
     */
    public Map<String, String> getAllBindings() {
        return storage.getAll();
    }

    /**
     * 重新加载数据。RemoteStorage.load() 包含 DB 探测与全量同步（可能阻塞数秒），
     * 必须放到后台线程执行，不能在命令所在的主线程直接调用。
     */
    public void reload() {
        ioExecutor.execute(() -> {
            try {
                storage.load();
                LOGGER.info("Binding data reloaded (async)");
            } catch (Exception e) {
                LOGGER.error("异步重载绑定数据失败", e);
            }
        });
    }

    public static class BindResult {
        public final boolean success;
        public final String message;

        public BindResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}