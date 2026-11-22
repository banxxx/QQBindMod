package net.minecraft.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerPlayer;

/** Deterministic replacement for only the queue and player lookup. No game/server I/O. */
public final class MinecraftServer {
    private final Thread gameThread = Thread.currentThread();
    private final PlayerList players = new PlayerList();
    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

    public boolean isSameThread() { return Thread.currentThread() == gameThread; }
    public PlayerList getPlayerList() {
        if (!isSameThread()) throw new AssertionError("Player lookup off game thread");
        return players;
    }
    public void execute(Runnable task) { tasks.add(task); }
    public Runnable takeTask() throws InterruptedException {
        Runnable task = tasks.poll(5, TimeUnit.SECONDS);
        if (task == null) throw new AssertionError("Expected a game task");
        return task;
    }

    public static final class PlayerList {
        private final Map<UUID, ServerPlayer> players = new HashMap<>();
        public ServerPlayer getPlayer(UUID id) { return players.get(id); }
        public java.util.List<ServerPlayer> getPlayers() { return java.util.List.copyOf(players.values()); }
        public ServerPlayer getPlayerByName(String gameId) {
            return players.values().stream().filter(player -> player.getScoreboardName().equalsIgnoreCase(gameId)).findFirst().orElse(null);
        }
        public void put(ServerPlayer player) { players.put(player.getUUID(), player); }
    }
}
