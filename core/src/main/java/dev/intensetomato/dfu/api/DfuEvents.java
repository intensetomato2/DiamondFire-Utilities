package dev.intensetomato.dfu.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class DfuEvents {
    private static final List<Consumer<String>> ACTION_BAR = new CopyOnWriteArrayList<>();
    private DfuEvents() {}
    public static void onActionBar(Consumer<String> listener) { ACTION_BAR.add(listener); }
    public static void offActionBar(Consumer<String> listener) { ACTION_BAR.remove(listener); }
    public static void fireActionBar(String message) { for (Consumer<String> listener : ACTION_BAR) listener.accept(message); }
}
