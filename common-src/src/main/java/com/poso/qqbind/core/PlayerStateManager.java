package com.poso.qqbind.core;

import com.poso.qqbind.QQBindConfig;
import com.poso.qqbind.official.OfficialLoginCodes;
import com.poso.qqbind.official.OfficialTokenService;
import com.poso.qqbind.server.ServerProviderHolder;
import com.poso.qqbind.utils.GameIdValidator;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家状态管理类，用于跟踪玩家是否处于“受限”状态（未绑定）。
 * 纯服务端实现，通过游戏模式（旁观者）和标记共同限制操作。
 */
public class PlayerStateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlayerStateManager.class);
    private static final String MODE_MARKER_PREFIX = "qqbind-official-restriction-v1:";

    // 存储受限玩家的 UUID
    private static final Map<UUID, Boolean> restrictedPlayers = new ConcurrentHashMap<>();
    private record PreviousMode(GameType mode, String marker) { }
    private record OfficialConnection(ServerGamePacketListenerImpl connection, OfficialLoginCodes.Login login, PreviousMode previous) { }
    private static final Map<UUID, OfficialConnection> officialRestrictions = new ConcurrentHashMap<>();

    /** Return true when this login must not enter the old binding/token path. */
    public static boolean handleRuntimeLogin(ServerPlayer player) {
        if (BindingBootstrap.legacyActive()) return false;
        if (QQBindConfig.isOfficialBindingSelected() && !BindingBootstrap.officialSnapshotReady()
                && !(GameIdValidator.isValid(player.getScoreboardName())
                     && BindingBootstrap.officialBoundFast(player.getScoreboardName()))) {
            deferUntilOfficialSnapshot(player);
            return true;
        }
        MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
        if (server == null || player.connection == null) return true;
        if (server.isSameThread()) {
            // Some JOIN hooks can run before the online list is fully populated.
            applyOfficialLogin(player);
        } else {
            var playerId = player.getUUID();
            var connection = player.connection;
            server.execute(() -> {
                ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                if (online != null && online.connection == connection) applyOfficialLogin(online);
            });
        }
        return true;
    }

    private static void deferUntilOfficialSnapshot(ServerPlayer requested) {
        MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
        if (server == null || requested.connection == null) return;
        UUID playerId = requested.getUUID();
        ServerGamePacketListenerImpl connection = requested.connection;
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null || player.connection != connection || !connection.isAcceptingMessages()) return;
            if (BindingBootstrap.officialSnapshotReady()
                    || GameIdValidator.isValid(player.getScoreboardName())
                       && BindingBootstrap.officialBoundFast(player.getScoreboardName())) {
                applyOfficialLogin(player);
                return;
            }
            if (QQBindConfig.ENABLE_WHITELIST_CHECK && !PlayerPermissions.isOperator(player)) {
                restrictOfficialPlayer(player, restrictionFor(player));
            }
            player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§c官方绑定状态正在核对")));
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§e请稍后重试；未知的服务器状态不会视为未绑定")));
            player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("§e正在核对绑定来源与版本")));
        });
    }

    private static void applyOfficialLogin(ServerPlayer player) {
        if (!player.connection.isAcceptingMessages()) return;
        OfficialConnection previous = restrictionFor(player);
        if (!QQBindConfig.ENABLE_WHITELIST_CHECK || PlayerPermissions.isOperator(player)) {
            if (previous != null) reconcileOfficialMode(player, previous);
            return;
        }

        String gameId = player.getScoreboardName();
        if (GameIdValidator.isValid(gameId) && BindingBootstrap.officialBoundFast(gameId)) {
            if (previous == null) {
                var saved = savedOfficialMode(player);
                if (saved == null) return; // A regular login must not change the player's mode.
                previous = new OfficialConnection(player.connection, null, saved);
                officialRestrictions.put(player.getUUID(), previous);
            }
            reconcileOfficialMode(player, previous);
            return;
        }

        restrictOfficialPlayer(player, previous);
        doSendOfficialPrompt(player, true);
    }

    private static OfficialConnection restrictionFor(ServerPlayer player) {
        UUID id = player.getUUID();
        var entry = officialRestrictions.get(id);
        if (entry == null || entry.connection() == player.connection) return entry;
        forgetOfficialRestriction(id, entry);
        return null;
    }

    private static boolean restrictOfficialPlayer(ServerPlayer player, OfficialConnection entry) {
        String gameId = player.getScoreboardName();
        var codes = BindingBootstrap.officialLoginCodes();
        var login = entry == null ? null : entry.login();
        if (login == null && codes != null && GameIdValidator.isValid(gameId)) login = codes.login(player.getUUID(), gameId);
        GameType current = player.gameMode.getGameModeForPlayer();
        if (entry == null) {
            var previous = savedOfficialMode(player);
            if (previous == null) {
                player.getTags().stream().filter(tag -> tag.startsWith(MODE_MARKER_PREFIX))
                        .toList().forEach(player::removeTag);
                String marker = BindingBootstrap.officialRestrictionMarker(player.getUUID().toString(), current.name());
                if (marker != null && !player.addTag(marker)) marker = null;
                previous = new PreviousMode(current, marker);
            }
            officialRestrictions.put(player.getUUID(), new OfficialConnection(player.connection, login, previous));
        } else if (entry.login() != login) {
            officialRestrictions.replace(player.getUUID(), entry, new OfficialConnection(player.connection, login, entry.previous()));
        }
        if (current != GameType.SPECTATOR) player.setGameMode(GameType.SPECTATOR);
        return player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR;
    }

    // Called by the I/O worker with identifiers only. Player lookup happens in the game task.
    static void queueOfficialCodeNotice(OfficialLoginCodes.Notice notice) {
        MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
        if (server != null) server.execute(() -> showOfficialCodeNotice(server, notice));
    }

    private static void showOfficialCodeNotice(MinecraftServer server, OfficialLoginCodes.Notice notice) {
        if (!server.isSameThread()) return;
        ServerPlayer player = server.getPlayerList().getPlayer(notice.login().playerId());
        if (player == null) return;
        OfficialConnection connection = promptTarget(player);
        var codes = BindingBootstrap.officialLoginCodes();
        if (connection == null || connection.login() != notice.login() || codes == null || !codes.eligible(notice.login())) return;
        var current = codes.current(notice.login());
        if (current != null && current.tokenGeneration().equals(notice.tokenGeneration())) showOfficialCode(player, current, true);
        else if (current == null && notice.tokenGeneration() == null) {
            officialActionBar(player, notice.awaitingConfirmation() ? "绑定操作待确认，请等待后再取码" : "暂时无法生成官方绑定码，稍后重试");
        }
    }

    private static OfficialConnection promptTarget(ServerPlayer player) {
        OfficialConnection connection = officialRestrictions.get(player.getUUID());
        if (connection == null || connection.connection() != player.connection || !player.connection.isAcceptingMessages()) return null;
        if (!QQBindConfig.ENABLE_WHITELIST_CHECK || PlayerPermissions.isOperator(player)) {
            if (connection.login() != null) connection.login().leave();
            return null;
        }
        return connection;
    }

    private static void doSendOfficialPrompt(ServerPlayer player, boolean full) {
        OfficialConnection connection = promptTarget(player);
        if (connection == null) return;
        String gameId = player.getScoreboardName();
        if (GameIdValidator.isValid(gameId) && BindingBootstrap.officialBoundFast(gameId)) {
            reconcileOfficialMode(player, connection);
            return;
        }
        var codes = BindingBootstrap.officialLoginCodes();
        var login = connection.login();
        if (codes == null || login == null || !codes.eligible(login)) {
            officialActionBar(player, "官方绑定暂不可用，请联系管理员");
            return;
        }
        var current = codes.current(login);
        if (current != null) showOfficialCode(player, current, full);
        else if (codes.request(login)) officialActionBar(player, "正在准备官方绑定码");
        else officialActionBar(player, "官方绑定码暂不可用，稍后重试");
    }

    private static void showOfficialCode(ServerPlayer player, OfficialTokenService.Issued issued, boolean full) {
        try {
            if (full) {
                player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 100, 20));
                player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§eQQ 官方绑定码")));
                player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§f" + issued.tokenCode())));
            }
            officialActionBar(player, "QQ 官方绑定码 " + issued.tokenCode() + " · 群绑定功能待开放");
        } catch (RuntimeException ignored) {
            // A send failure can include the packet text. The next prompt can display the current code again.
        }
    }

    private static void officialActionBar(ServerPlayer player, String message) {
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal("§e" + message)));
    }

    private static PreviousMode savedOfficialMode(ServerPlayer player) {
        PreviousMode saved = null;
        for (String tag : player.getTags()) {
            if (!tag.startsWith(MODE_MARKER_PREFIX)) continue;
            if (saved != null) return null;
            for (GameType mode : GameType.values()) {
                if (BindingBootstrap.verifiesOfficialRestrictionMarker(player.getUUID().toString(), mode.name(), tag))
                    saved = new PreviousMode(mode, tag);
            }
        }
        return saved;
    }

    private static void reconcileOfficialMode(ServerPlayer player, OfficialConnection entry) {
        BindingBootstrap.reconcileOfficialOnline(player.getScoreboardName(), () -> restoreOfficialMode(player, entry));
    }

    private static void restoreOfficialMode(ServerPlayer player, OfficialConnection entry) {
        UUID id = player.getUUID();
        if (officialRestrictions.get(id) != entry || entry.connection() != player.connection
                || !player.connection.isAcceptingMessages()) return;
        var previous = entry.previous();
        if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR && previous.mode() != GameType.SPECTATOR) {
            player.setGameMode(previous.mode());
            if (player.gameMode.getGameModeForPlayer() != previous.mode()) {
                throw new IllegalStateException("Official restriction not released");
            }
        }
        if (previous.marker() != null) player.removeTag(previous.marker());
        forgetOfficialRestriction(id, entry);
    }

    private static void forgetOfficialRestriction(UUID playerId, OfficialConnection entry) {
        if (officialRestrictions.remove(playerId, entry) && entry.login() != null) entry.login().leave();
    }

    static void clearOfficialLogins() {
        officialRestrictions.values().forEach(connection -> {
            if (connection.login() != null) connection.login().leave();
        });
        officialRestrictions.clear();
    }

    /** Invoked only inside the mirror's version-checked game callback. */
    static void applyOfficialBindingState(String gameId, boolean active) {
        MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Game thread required");
        if (!GameIdValidator.isValid(gameId)) throw new IllegalArgumentException("Invalid game key");
        ServerPlayer player = server.getPlayerList().getPlayerByName(gameId);
        if (player == null || !player.connection.isAcceptingMessages()) return;
        OfficialConnection entry = restrictionFor(player);
        if (!active && QQBindConfig.ENABLE_WHITELIST_CHECK && !PlayerPermissions.isOperator(player)) {
            if (!restrictOfficialPlayer(player, entry)) {
                throw new IllegalStateException("Server rejected official restriction");
            }
            doSendOfficialPrompt(player, true);
        } else if (entry != null) {
            restoreOfficialMode(player, entry);
        }
    }

    /**
     * 官方模式在线切换登录限制后的重校验，必须在服务端主线程执行。
     * 关闭时立即释放所有已记录的官方受限玩家；打开时只在绑定快照已就绪后
     * 重新判定在线玩家，避免把“未知状态”误当成未绑定而全场踢成旁观者。
     *
     * @return {在线人数, 本次释放人数}
     */
    static int[] revalidateOfficialWhitelist(boolean enabled) {
        MinecraftServer server = ServerProviderHolder.get().getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Game thread required");
        int online = 0, released = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online++;
            if (!enabled) {
                OfficialConnection entry = officialRestrictions.get(player.getUUID());
                if (entry == null) continue;
                try { restoreOfficialMode(player, entry); released++; }
                catch (RuntimeException error) { LOGGER.warn("释放官方登录限制失败: {}", error.getMessage()); }
                continue;
            }
            if (!BindingBootstrap.officialSnapshotReady()) continue;
            boolean wasRestricted = officialRestrictions.containsKey(player.getUUID());
            try { applyOfficialLogin(player); }
            catch (RuntimeException error) { LOGGER.warn("重新校验官方登录限制失败: {}", error.getMessage()); }
            if (wasRestricted && !officialRestrictions.containsKey(player.getUUID())) released++;
        }
        return new int[]{online, released};
    }

    /**
     * 设置玩家的受限状态。游戏模式切换排队到服务端主线程执行。
     * @param player   目标玩家
     * @param restricted true 表示限制，false 表示解除限制
     */
    public static void setRestricted(ServerPlayer player, boolean restricted) {
        MainThread.post(() -> applyRestricted(player, restricted));
    }

    private static void applyRestricted(ServerPlayer player, boolean restricted) {
        UUID uuid = player.getUUID();
        if (restricted) {
            restrictedPlayers.put(uuid, true);
            player.setGameMode(GameType.SPECTATOR);
            if (QQBindConfig.isLegacyBindingEnabled()) LOGGER.info("玩家 {} 已被限制（未绑定），切换为旁观者模式", player.getScoreboardName());
        } else {
            restrictedPlayers.remove(uuid);
            player.setGameMode(GameType.SURVIVAL);
            if (QQBindConfig.isLegacyBindingEnabled()) LOGGER.info("玩家 {} 解除限制，恢复生存模式", player.getScoreboardName());
        }
    }

    /**
     * 检查玩家是否处于受限状态。
     */
    public static boolean isRestricted(ServerPlayer player) {
        if (BindingBootstrap.legacyActive()) return restrictedPlayers.containsKey(player.getUUID());
        if (!QQBindConfig.ENABLE_WHITELIST_CHECK || PlayerPermissions.isOperator(player)) return false;
        OfficialConnection connection = officialRestrictions.get(player.getUUID());
        return connection != null && connection.connection() == player.connection;
    }

    /**
     * 玩家退出时清理状态。
     */
    public static void removePlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!QQBindConfig.isLegacyBindingEnabled()) {
            officialRestrictions.computeIfPresent(uuid, (id, connection) -> {
                if (connection.connection() != player.connection) return connection;
                if (connection.login() != null) connection.login().leave();
                return null;
            });
            return;
        }
        if (restrictedPlayers.remove(uuid) != null) {
            if (QQBindConfig.isLegacyBindingEnabled()) LOGGER.info("玩家 {} 退出，已清理受限状态", player.getScoreboardName());
        }
        // 同时清理令牌
        if (QQBindConfig.isLegacyBindingEnabled()) TokenManager.removeTokenForPlayer(player);
    }

    /**
     * 向受限玩家发送完整提示消息（Title + Subtitle + ActionBar + Chat），
     * 包含令牌信息。此方法自动获取或刷新令牌。
     * 排队到主线程执行，保证与 setRestricted 的先后顺序。
     * @param player 目标玩家
     */
    public static void sendRestrictionMessage(ServerPlayer player) {
        MainThread.post(() -> doSendRestrictionMessage(player));
    }

    private static void doSendRestrictionMessage(ServerPlayer player) {
        if (!BindingBootstrap.legacyActive()) { doSendOfficialPrompt(player, true); return; }
        // 获取或刷新令牌
        String token = TokenManager.getOrRefreshToken(player);

        // 构建消息
        String title = QQBindConfig.formatMessage(QQBindConfig.TITLE_TEMPLATE, token);
        String subtitle = QQBindConfig.formatMessage(QQBindConfig.SUBTITLE_TEMPLATE, token);
        String actionBar = QQBindConfig.formatMessage(QQBindConfig.ACTION_BAR_TEMPLATE, token);

        // 发送 Title
        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title)));
        player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 100, 20));

        // ActionBar
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(actionBar)));

    }

    /**
     * 发送简短的 ActionBar 提醒（用于操作拦截时快速反馈）
     */
    public static void sendActionBarReminder(ServerPlayer player) {
        MainThread.post(() -> doSendActionBarReminder(player));
    }

    private static void doSendActionBarReminder(ServerPlayer player) {
        if (!BindingBootstrap.legacyActive()) { doSendOfficialPrompt(player, false); return; }
        String token = TokenManager.getOrRefreshToken(player);
        String actionBar = QQBindConfig.formatMessage(QQBindConfig.ACTION_BAR_TEMPLATE, token);
        player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(actionBar)));
    }
}
