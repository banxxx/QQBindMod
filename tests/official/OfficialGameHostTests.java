package com.poso.qqbind.official;

import com.google.gson.JsonObject;
import com.poso.qqbind.core.BindingBootstrap;
import com.poso.qqbind.core.PlayerPermissions;
import com.poso.qqbind.core.PlayerStateManager;
import com.poso.qqbind.server.ServerProviderHolder;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.poso.qqbind.official.OfficialCenterTests.*;

/** Invokes the real game host against in-memory players and an in-memory HTTP exchange. */
final class OfficialGameHostTests {
    private static final String BASE = "/api/v2/binding/";
    private static final String AUTH = "Bearer " + OfficialRuntimeTests.TOKEN;

    private static ServerPlayer join(MinecraftServer server, UUID uuid, String name) {
        var player = new ServerPlayer(uuid, name);
        server.getPlayerList().put(player);
        check(PlayerStateManager.handleRuntimeLogin(player));
        return player;
    }

    private static void quit(ServerPlayer player) {
        PlayerStateManager.removePlayer(player);
        player.connection.accepting = false;
    }

    private static String subtitle(ServerPlayer player) {
        return player.connection.packets.stream().filter(ClientboundSetSubtitleTextPacket.class::isInstance)
                .map(ClientboundSetSubtitleTextPacket.class::cast).map(p -> p.text().text())
                .filter(value -> value.matches("§f[0-9]{6}")).map(value -> value.substring(2)).reduce((a, b) -> b).orElse(null);
    }

    private static OfficialTokenApi.Reply call(ExecutorService io, String path, JsonObject body) throws Exception {
        return io.submit(() -> BindingBootstrap.dispatchOfficialHttp("POST", BASE + path, body, AUTH)).get(5, TimeUnit.SECONDS);
    }

    private static String validate(ExecutorService io, String code) throws Exception {
        var body = S.wire();
        body.addProperty("token_code", code);
        return call(io, "token/validate", body).body().get("code").getAsString();
    }

    static void run() throws Exception {
        MinecraftServer server = new MinecraftServer();
        ServerProviderHolder.server = server;
        PlayerPermissions.register(player -> player.operator);
        ExecutorService io = Executors.newSingleThreadExecutor();
        try {
            for (int attempt = 0; attempt < 300 && !BindingBootstrap.officialSnapshotReady(); attempt++) {
                BindingBootstrap.tickOfficial();
                Thread.sleep(10);
            }
            check(BindingBootstrap.officialSnapshotReady());
            test("official_game_login_gets_a_code_validated_by_the_same_session", () -> {
                var player = join(server, new UUID(1, 1), "Player_A");
                check(PlayerStateManager.isRestricted(player) && player.mode == GameType.SPECTATOR);
                server.takeTask().run();
                String code = subtitle(player);
                check(code != null && validate(io, code).equals("OK"));
                quit(player);
                check(validate(io, code).equals("TOKEN_INVALID"));
            });
            test("late_game_callback_and_logout_do_not_touch_the_replacement_connection", () -> {
                UUID uuid = new UUID(1, 2);
                var old = join(server, uuid, "Player_X");
                Runnable late = server.takeTask();
                PlayerStateManager.sendRestrictionMessage(old);
                String oldCode = subtitle(old);
                check(oldCode != null);
                quit(old);
                var replacement = join(server, uuid, "Player_X");
                int packets = replacement.connection.packets.size();
                late.run();
                PlayerStateManager.removePlayer(old);
                check(replacement.connection.packets.size() == packets && PlayerStateManager.isRestricted(replacement));
                server.takeTask().run();
                String replacementCode = subtitle(replacement);
                check(replacementCode != null && !replacementCode.equals(oldCode) && validate(io, replacementCode).equals("OK"));
                quit(replacement);
            });
            test("player_entity_replacement_on_the_same_connection_can_receive_its_code", () -> {
                UUID uuid = new UUID(1, 3);
                var original = join(server, uuid, "Player_C");
                Runnable ready = server.takeTask();
                var replacement = new ServerPlayer(uuid, "Player_C", original.connection);
                server.getPlayerList().put(replacement);
                ready.run();
                check(PlayerStateManager.isRestricted(replacement) && subtitle(replacement) != null);
                quit(replacement);
            });
            test("operator_login_and_operator_change_suppress_official_codes", () -> {
                var operator = new ServerPlayer(new UUID(1, 4), "Player_D");
                operator.operator = true;
                server.getPlayerList().put(operator);
                check(PlayerStateManager.handleRuntimeLogin(operator) && operator.mode == GameType.CREATIVE && operator.connection.packets.isEmpty());
                quit(operator);
                var promoted = join(server, new UUID(1, 5), "Player_E");
                Runnable ready = server.takeTask();
                promoted.operator = true;
                ready.run();
                PlayerStateManager.sendRestrictionMessage(promoted);
                check(subtitle(promoted) == null);
                quit(promoted);
            });
            test("bound_login_preserves_game_mode_and_sync_suppresses_a_queued_code", () -> {
                var player = join(server, new UUID(1, 6), "Player_F");
                Runnable ready = server.takeTask();
                JsonObject sync = S.wire(), effect = new JsonObject();
                effect.addProperty("game_id", "Player_F");
                effect.addProperty("game_key", "player_f");
                effect.addProperty("state", "active");
                effect.addProperty("revision", "1");
                effect.addProperty("user_id", "SYNTHETIC_USER_A");
                var effects = new com.google.gson.JsonArray();
                effects.add(effect);
                sync.add("effects", effects);
                sync.addProperty("operation_id", OP);
                check(call(io, "sync", sync).body().get("success").getAsBoolean());
                ready.run();
                check(subtitle(player) == null && PlayerStateManager.isRestricted(player));
                quit(player);
                var bound = join(server, new UUID(1, 6), "Player_F");
                check(!PlayerStateManager.isRestricted(bound) && bound.mode == GameType.CREATIVE && bound.connection.packets.isEmpty());
                quit(bound);
            });
            test("signed_scope_marker_restores_only_its_saved_mode_after_restart", () -> {
                var settings = OfficialRuntimeSettings.parse(OfficialRuntimeTests.config());
                UUID playerId = new UUID(1, 8);
                String marker = settings.restrictionMarker(playerId.toString(), "CREATIVE");
                check(BindingBootstrap.stop());
                check(BindingBootstrap.start(() -> { throw new AssertionError("Unexpected legacy storage"); }) == null);

                // No center read since restart: the same scope's durable active mirror suffices.
                check(!BindingBootstrap.officialSnapshotReady());
                String forged = marker.substring(0, marker.length() - 1) + (marker.endsWith("0") ? "1" : "0");
                var attacker = new ServerPlayer(playerId, "Player_B", new net.minecraft.server.network.ServerGamePacketListenerImpl(), java.util.Set.of(forged));
                attacker.mode = GameType.SPECTATOR;
                server.getPlayerList().put(attacker);
                check(PlayerStateManager.handleRuntimeLogin(attacker));
                check(attacker.mode == GameType.SPECTATOR && !PlayerStateManager.isRestricted(attacker));
                quit(attacker);

                var reconnect = new ServerPlayer(playerId, "Player_B", new net.minecraft.server.network.ServerGamePacketListenerImpl(), java.util.Set.of(marker));
                reconnect.mode = GameType.SPECTATOR;
                server.getPlayerList().put(reconnect);
                check(PlayerStateManager.handleRuntimeLogin(reconnect));
                check(!PlayerStateManager.isRestricted(reconnect) && reconnect.mode == GameType.CREATIVE);
                check(reconnect.savedTags().isEmpty());
                quit(reconnect);
                for (int attempt = 0; attempt < 300 && !BindingBootstrap.officialSnapshotReady(); attempt++) {
                    BindingBootstrap.tickOfficial(); Thread.sleep(10);
                }
                check(BindingBootstrap.officialSnapshotReady());
            });
            test("native_shutdown_discards_a_queued_code", () -> {
                var player = join(server, new UUID(1, 7), "Player_G");
                Runnable ready = server.takeTask();
                check(BindingBootstrap.stop());
                ready.run();
                check(subtitle(player) == null && !PlayerStateManager.isRestricted(player));
            });
        } finally {
            BindingBootstrap.stop();
            ServerProviderHolder.server = null;
            io.shutdownNow();
        }
    }
}
