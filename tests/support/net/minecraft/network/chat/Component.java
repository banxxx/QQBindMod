package net.minecraft.network.chat;

public record Component(String text) {
    public static Component literal(String text) { return new Component(text); }
}
