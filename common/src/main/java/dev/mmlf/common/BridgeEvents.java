package dev.mmlf.common;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.*;
import static dev.mmlf.common.Messages.*;

/** Native mods use this API without either loader's API leaking into the protocol. */
public final class BridgeEvents {
    private static final CopyOnWriteArrayList<Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile BiConsumer<String,Event> sender = (target,event) -> {};
    private BridgeEvents() {}
    public static void subscribe(Consumer<Event> listener) { LISTENERS.add(listener); }
    public static void install(BiConsumer<String,Event> sink) { sender = sink; }
    public static void emit(String target, String topic, java.util.Map<String,String> values) {
        sender.accept(target, new Event(topic, values));
    }
    /** Caller must dispatch on the native server thread. */
    public static void deliver(Event event) { LISTENERS.forEach(c -> c.accept(event)); }
}
