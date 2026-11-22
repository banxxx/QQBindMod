package net.minecraft.server.level;

import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;

public final class ServerPlayer {
    private final Thread gameThread = Thread.currentThread();
    private final UUID id;
    private final String gameId;
    public final ServerGamePacketListenerImpl connection;
    public GameType mode = GameType.CREATIVE;
    public boolean operator;
    public final Mode gameMode = new Mode();
    private final Set<String> tags = new HashSet<>();

    public final class Mode {
        public GameType getGameModeForPlayer() { checkGameThread(); return mode; }
    }

    public ServerPlayer(UUID id, String gameId) { this(id, gameId, new ServerGamePacketListenerImpl()); }
    public ServerPlayer(UUID id, String gameId, ServerGamePacketListenerImpl connection) { this(id, gameId, connection, Set.of()); }
    public ServerPlayer(UUID id, String gameId, ServerGamePacketListenerImpl connection, Set<String> tags) {
        this.id = id;
        this.gameId = gameId;
        this.connection = connection;
        this.tags.addAll(tags);
    }

    public UUID getUUID() { checkGameThread(); return id; }
    public String getScoreboardName() { checkGameThread(); return gameId; }
    public boolean setGameMode(GameType mode) { checkGameThread(); this.mode = mode; return true; }
    public Set<String> getTags() { checkGameThread(); return tags; }
    public boolean addTag(String tag) { checkGameThread(); return tags.add(tag); }
    public boolean removeTag(String tag) { checkGameThread(); return tags.remove(tag); }
    public Set<String> savedTags() { checkGameThread(); return Set.copyOf(tags); }

    private void checkGameThread() {
        if (Thread.currentThread() != gameThread) throw new AssertionError("Player accessed off game thread");
    }
}
