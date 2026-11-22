package net.minecraft.network.protocol.game;

import net.minecraft.network.chat.Component;

public record ClientboundSetActionBarTextPacket(Component text) { }
