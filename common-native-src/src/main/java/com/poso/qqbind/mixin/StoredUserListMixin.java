package com.poso.qqbind.mixin;

import com.mojang.authlib.GameProfile;
import com.poso.qqbind.nativebridge.*;
import com.poso.qqbind.official.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.*;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.poso.qqbind.official.OfficialWhitelistFile.Profile;

/** The same map vanilla login reads. Other vanilla lists and OneBot are untouched. */
@Mixin(StoredUserList.class)
public abstract class StoredUserListMixin implements OfficialWhitelistAccess {
    @Shadow @Final private Map<String,StoredUserEntry<?>> map;
    @Shadow public abstract void save() throws IOException;
    @Shadow public abstract void load() throws IOException;
    @Unique private OfficialWhitelistFile qqbind$file;
    @Unique private BooleanSupplier qqbind$active,qqbind$gameThread;
    @Unique private boolean qqbind$nativeCall;

    public void qqbind$attach(OfficialWhitelistFile file,BooleanSupplier active,BooleanSupplier gameThread) {
        if (!((Object)this instanceof UserWhiteList) || qqbind$file != null || !gameThread.getAsBoolean())
            throw new OfficialException("CONFLICT");
        qqbind$file=Objects.requireNonNull(file); qqbind$active=active; qqbind$gameThread=gameThread;
    }
    public void qqbind$detach(OfficialWhitelistFile file) {
        if (qqbind$file == file) { qqbind$file=null; qqbind$active=null; qqbind$gameThread=null; }
    }
    @Unique private void qqbind$guard() {
        if (qqbind$file == null || !qqbind$active.getAsBoolean() || !qqbind$gameThread.getAsBoolean())
            throw new OfficialException("CONFLICT");
    }
    @Unique private static GameProfile qqbind$profile(StoredUserEntry<?> entry) {
        if (entry == null || entry.getClass() != UserWhiteListEntry.class
                || !(((OfficialEntryAccess)entry).qqbind$user() instanceof GameProfile profile))
            throw new OfficialException("NOT_SUPPORTED");
        return profile;
    }
    public List<Profile> qqbind$snapshot() {
        qqbind$guard();
        if (map.size() > OfficialWhitelistFile.MAX_PROFILES) throw new OfficialException("LIMIT_EXCEEDED");
        List<Profile> result=new ArrayList<>(map.size());
        for (var row : map.entrySet()) {
            GameProfile profile=qqbind$profile(row.getValue());
            if (profile.getId() == null || !row.getKey().equals(profile.getId().toString())) throw new OfficialException("CONFLICT");
            result.add(new Profile(profile.getId(),profile.getName()));
        }
        return List.copyOf(result);
    }
    public void qqbind$apply(Profile profile,boolean enabled) {
        qqbind$guard();
        String key=profile.uuid().toString();
        if (enabled) {
            if (map.size() >= OfficialWhitelistFile.MAX_PROFILES && !map.containsKey(key)) throw new OfficialException("LIMIT_EXCEEDED");
            map.put(key,new UserWhiteListEntry(new GameProfile(profile.uuid(),profile.name())));
        } else map.remove(key);
    }
    @Unique private CommandSyntaxException qqbind$feedback(OfficialException error) {
        String text=error.code().equals("RESULT_UNKNOWN")
                ? "白名单结果正在确认，请先查询名单，勿重复提交。"
                : "白名单正在同步或未就绪，本次操作未确认，请稍后查询重试。";
        return new SimpleCommandExceptionType(Component.literal(text)).create();
    }
    @Unique private void qqbind$saveCandidate(List<Profile> next,OfficialWhitelistFile.NativeSave mutation) throws CommandSyntaxException {
        try {
            qqbind$guard();
            var snapshot=qqbind$file.captureOnGame(next);
            qqbind$file.nativeSave(snapshot,() -> {
                qqbind$nativeCall=true;
                try { mutation.run(); save(); } finally { qqbind$nativeCall=false; }
            });
        } catch (OfficialException error) { throw qqbind$feedback(error); }
    }
    @Inject(method="add(Lnet/minecraft/server/players/StoredUserEntry;)V",at=@At("HEAD"),cancellable=true)
    private void qqbind$nativeAdd(StoredUserEntry<?> entry,CallbackInfo ci) throws CommandSyntaxException {
        if (qqbind$file == null) return;
        try {
            qqbind$guard(); if (qqbind$nativeCall) return;
            GameProfile profile=qqbind$profile(entry); Profile value=new Profile(profile.getId(),profile.getName());
            List<Profile> next=new ArrayList<>(qqbind$snapshot());
            next.removeIf(row -> row.uuid().equals(value.uuid())); next.add(value);
            qqbind$saveCandidate(next,() -> map.put(value.uuid().toString(),entry)); ci.cancel();
        } catch (OfficialException error) { throw qqbind$feedback(error); }
    }
    @Inject(method="remove(Ljava/lang/Object;)V",at=@At("HEAD"),cancellable=true)
    private void qqbind$nativeRemove(Object user,CallbackInfo ci) throws CommandSyntaxException {
        if (qqbind$file == null) return;
        try {
            qqbind$guard(); if (qqbind$nativeCall) return;
            if (!(user instanceof GameProfile profile) || profile.getId() == null) throw new OfficialException("INVALID_INPUT");
            List<Profile> next=new ArrayList<>(qqbind$snapshot()); next.removeIf(row -> row.uuid().equals(profile.getId()));
            qqbind$saveCandidate(next,() -> map.remove(profile.getId().toString())); ci.cancel();
        } catch (OfficialException error) { throw qqbind$feedback(error); }
    }
    @Inject(method="save()V",at=@At("HEAD"),cancellable=true)
    private void qqbind$nativeSave(CallbackInfo ci) throws CommandSyntaxException {
        if (qqbind$file == null) return;
        try {
            qqbind$guard(); if (qqbind$nativeCall) return;
            qqbind$saveCandidate(qqbind$snapshot(),() -> { }); ci.cancel();
        } catch (OfficialException error) { throw qqbind$feedback(error); }
    }
    @Inject(method="load()V",at=@At("HEAD"),cancellable=true)
    private void qqbind$nativeLoad(CallbackInfo ci) throws CommandSyntaxException {
        if (qqbind$file == null) return;
        try {
            qqbind$guard(); if (qqbind$nativeCall) return;
            qqbind$file.nativeLoad(() -> {
                qqbind$nativeCall=true;
                try { load(); return qqbind$file.captureOnGame(qqbind$snapshot()); } finally { qqbind$nativeCall=false; }
            });
            ci.cancel();
        } catch (OfficialException error) { throw qqbind$feedback(error); }
    }
}
