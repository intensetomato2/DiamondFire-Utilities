package dev.intensetomato.dfu.api;

import net.minecraft.client.gui.screens.Screen;

public interface DfuModule {
    String id();
    String name();
    void onLoad(DfuContext context);
    default void onUnload() {}
    default boolean isActive() { return false; }
    default Screen configScreen(Screen parent) { return parent; }
}
