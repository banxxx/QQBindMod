package net.minecraft.network.protocol.game;

public record ClientboundSetTitlesAnimationPacket(int fadeIn, int stay, int fadeOut) { }
