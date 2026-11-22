package com.poso.qqbind.mixin;

import com.poso.qqbind.nativebridge.OfficialEntryAccess;
import net.minecraft.server.players.StoredUserEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StoredUserEntry.class)
public interface StoredUserEntryMixin extends OfficialEntryAccess {
    @Accessor("user") Object qqbind$user();
}
