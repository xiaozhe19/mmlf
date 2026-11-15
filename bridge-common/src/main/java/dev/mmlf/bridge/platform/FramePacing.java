package dev.mmlf.bridge.platform;

import java.util.function.BooleanSupplier;

/**
 * Experiment {@code on_demand}: what keeps a background rendering at full rate. Its own world changing
 * ({@link #activity}) and anything it is busy with for the player (a hosted screen, mining for the primary) count; the
 * camera publisher reads these and idles otherwise. Lower layers report here instead of naming the render layer.
 */
public final class FramePacing {
    private FramePacing() {}
    private static volatile long lastActivity;
    private static volatile BooleanSupplier busy=()->false;

    /** Something of this runtime's own changed (a block or object it owns). */
    public static void activity() { lastActivity=System.nanoTime(); }
    public static long lastActivity() { return lastActivity; }
    /** Registered by the entry: whether this runtime is busy for the player right now. */
    public static void busyWhen(BooleanSupplier supplier) { busy=supplier; }
    public static boolean busy() { return busy.getAsBoolean(); }
}
