package com.poso.qqbind.nativebridge;

import com.mojang.authlib.GameProfile;
import com.poso.qqbind.official.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.UserWhiteList;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import static com.poso.qqbind.official.OfficialWhitelistFile.Profile;

/** Actual vanilla PlayerList adapter. Never replaces the list or reads UserCache. */
public final class OfficialMinecraftWhitelist implements OfficialWhitelistHost {
    private record Profiles(Map<String,Profile> names, Set<String> conflicts) { }
    private final MinecraftServer server;
    private final PlayerList players;
    private final UserWhiteList list;
    private final OfficialWhitelistAccess access;
    private final Function<String,GameProfile> repository;
    private final BooleanSupplier active;
    private final boolean authenticated;
    private volatile Profiles profiles = new Profiles(Map.of(),Set.of());
    private volatile boolean closed;
    private OfficialWhitelistFile file;
    private int profileTicks;

    public OfficialMinecraftWhitelist(MinecraftServer server, Function<String,GameProfile> repository, BooleanSupplier active) {
        this.server=Objects.requireNonNull(server); this.repository=Objects.requireNonNull(repository); this.active=Objects.requireNonNull(active);
        if (!server.isSameThread()) throw new OfficialException("CONFLICT");
        this.players=server.getPlayerList(); this.list=players.getWhiteList();
        if (!(list instanceof OfficialWhitelistAccess bridge)) throw new OfficialException("NOT_SUPPORTED");
        this.access=bridge; this.authenticated=server.usesAuthentication();
    }
    private boolean running() { return !closed && active.getAsBoolean(); }
    public boolean activeFast() { return running(); }
    private void game() {
        if (!running() || !server.isSameThread())
            throw new OfficialException("CONFLICT");
        if (server.getPlayerList() != players || players.getWhiteList() != list) {
            closed=true; throw new OfficialException("CONFLICT");
        }
    }
    public Path filePath() { return list.getFile().toPath(); }
    public void attachOnGame(OfficialWhitelistFile file) {
        game();
        if (this.file != null) throw new OfficialException("CONFLICT");
        access.qqbind$attach(file,this::running,server::isSameThread); this.file=file;
        refreshProfiles();
    }
    public List<Profile> snapshotOnGame() { game(); return access.qqbind$snapshot(); }
    public void applyOnGame(Profile profile, boolean enabled) {
        validateProfileOnGame(profile); access.qqbind$apply(profile,enabled);
    }
    public void validateProfileOnGame(Profile profile) {
        game(); refreshProfiles();
        String key=OfficialScope.key(profile.name()); Profiles known=profiles;
        Profile current=known.names().get(key);
        if (known.conflicts().contains(key) || current != null && !current.uuid().equals(profile.uuid()))
            throw new OfficialException("CONFLICT");
        for (var row : known.names().entrySet())
            if (row.getValue().uuid().equals(profile.uuid()) && !row.getKey().equals(key)) throw new OfficialException("CONFLICT");
    }
    public void tickOnGame() {
        game();
        if (profileTicks++ % 20 == 0) refreshProfiles();
    }
    private void refreshProfiles() {
        Map<String,Profile> next=new HashMap<>(profiles.names());
        Set<String> conflicts=new HashSet<>(profiles.conflicts());
        Map<UUID,String> ids=new HashMap<>();
        next.forEach((key,value) -> ids.put(value.uuid(),key));
        for (Profile profile : access.qqbind$snapshot()) remember(next,ids,conflicts,profile);
        for (var player : players.getPlayers()) {
            GameProfile profile=player.getGameProfile();
            if (profile != null && profile.getId() != null && profile.getName() != null)
                remember(next,ids,conflicts,new Profile(profile.getId(),profile.getName()));
        }
        profiles=new Profiles(Map.copyOf(next),Set.copyOf(conflicts));
    }
    private static void remember(Map<String,Profile> names,Map<UUID,String> ids,Set<String> conflicts,Profile profile) {
        String key;
        try { key=OfficialScope.key(profile.name()); } catch (OfficialException ignored) { return; }
        Profile previous=names.get(key); String oldName=ids.get(profile.uuid());
        if (previous != null && !previous.uuid().equals(profile.uuid()) || oldName != null && !oldName.equals(key)) {
            conflicts.add(key); if (oldName != null) conflicts.add(oldName); return;
        }
        if (names.size() >= OfficialWhitelistFile.MAX_PROFILES && previous == null) throw new OfficialException("LIMIT_EXCEEDED");
        names.put(key,profile); ids.put(profile.uuid(),key);
    }
    /** Called only on the runtime's single bounded worker, never the game thread. */
    public Profile resolveOnIo(String gameId) {
        if (!running() || server.isSameThread()) throw new OfficialException("NOT_SUPPORTED");
        String key=OfficialScope.key(gameId); Profiles known=profiles;
        if (known.conflicts().contains(key)) throw new OfficialException("CONFLICT");
        Profile profile=known.names().get(key);
        if (profile != null) return profile;
        // Offline/proxy identities must come from this server's accepted player
        // profiles or its actual list. Never invent an offline UUID or query Mojang.
        if (!authenticated) return null;
        GameProfile found=repository.apply(gameId);
        if (!running() || found == null || found.getId() == null || found.getName() == null) return null;
        if (!key.equals(OfficialScope.key(found.getName())) || found.getId().equals(new UUID(0,0)))
            throw new OfficialException("CONFLICT");
        Profiles latest=profiles;
        if (latest.conflicts().contains(key)) throw new OfficialException("CONFLICT");
        Profile current=latest.names().get(key);
        if (current != null && !current.uuid().equals(found.getId())) throw new OfficialException("CONFLICT");
        return new Profile(found.getId(),found.getName());
    }
    public void close() {
        closed=true;
        if (file != null) access.qqbind$detach(file);
        profiles=new Profiles(Map.of(),Set.of());
    }
    @Override public String toString() { return "OfficialMinecraftWhitelist[redacted]"; }
}
