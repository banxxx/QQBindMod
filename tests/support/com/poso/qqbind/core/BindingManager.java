package com.poso.qqbind.core;
import com.poso.qqbind.storage.DataStorage;
/** Native bridge test double; not a Minecraft binding implementation. */
public final class BindingManager {
    public static int created,closed;
    public BindingManager(DataStorage storage) { created++; storage.load(); }
    public void close() { closed++; }
}
