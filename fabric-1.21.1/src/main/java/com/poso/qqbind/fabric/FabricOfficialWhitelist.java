package com.poso.qqbind.fabric;

import com.mojang.authlib.*;
import com.poso.qqbind.core.BindingBootstrap;
import com.poso.qqbind.nativebridge.OfficialMinecraftWhitelist;
import net.minecraft.server.MinecraftServer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Authlib 6.0.54 signature; no UserCache query or implicit offline UUID. */
final class FabricOfficialWhitelist {
    static void install(MinecraftServer server) {
        if (!BindingBootstrap.officialActive()) return;
        GameProfileRepository repository=server.getProfileRepository();
        BindingBootstrap.installOfficialWhitelist(new OfficialMinecraftWhitelist(server,name -> {
            AtomicReference<GameProfile> result=new AtomicReference<>(); AtomicBoolean failed=new AtomicBoolean();
            repository.findProfilesByNames(new String[]{name},new ProfileLookupCallback() {
                public void onProfileLookupSucceeded(GameProfile profile) {
                    if (!result.compareAndSet(null,profile)) failed.set(true);
                }
                public void onProfileLookupFailed(String name,Exception error) { failed.set(true); }
            });
            return failed.get() ? null : result.get();
        },BindingBootstrap::officialActive));
    }
    private FabricOfficialWhitelist() { }
}
