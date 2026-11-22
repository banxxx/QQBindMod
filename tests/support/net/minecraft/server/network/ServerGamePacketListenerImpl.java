package net.minecraft.server.network;

import java.util.ArrayList;
import java.util.List;

/** Synthetic player connection. Captures packets in memory and checks thread use. */
public final class ServerGamePacketListenerImpl {
    private final Thread gameThread = Thread.currentThread();
    public final List<Object> packets = new ArrayList<>();
    public boolean accepting = true;

    public boolean isAcceptingMessages() {
        if (Thread.currentThread() != gameThread) throw new AssertionError("Connection read off game thread");
        return accepting;
    }

    public void send(Object packet) {
        if (!isAcceptingMessages()) throw new AssertionError("Sent a packet after disconnect");
        packets.add(packet);
    }
}
