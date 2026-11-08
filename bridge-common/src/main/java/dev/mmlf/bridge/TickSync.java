package dev.mmlf.bridge;

import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.SampleWindow;
import net.minecraft.server.MinecraftServer;
import java.util.Map;

/**
 * {@code lockstep} experiment: the primary's server tick drives a background's. At the end of each of its ticks the
 * primary publishes its boundary (signals, neighbours) and then a tick permit; a background's server waits for the
 * permit instead of its own 50 ms timer, applies what arrived before it, runs one tick, and publishes its own boundary
 * at the end of that tick. Primary tick N's outputs are thus seen by background tick N, whose outputs reach primary tick
 * N+1. Without permits for {@link #FALLBACK_NANOS} (primary paused, in a menu or gone) a background ticks on its own timer.
 */
public final class TickSync {
    private TickSync() {}
    private static final long FALLBACK_NANOS=250_000_000L;
    /** Behind by more than this, a background skips ahead instead of running the missed ticks back to back. */
    private static final long MAX_CATCH_UP=40;
    private static volatile long permitted;
    private static long executed, lastPermitNanos, waits, fallbacks, skipped, catchUps;
    private static final SampleWindow waitMs=new SampleWindow(4096), permitIntervalMs=new SampleWindow(4096);
    private static volatile long permitArrived;
    /** The primary tick a background is running now, 0 outside lockstep. */
    private static volatile long current;

    public static boolean enabled() { return Experiments.LOCKSTEP && BridgeCore.connected(); }
    static long currentTick() { return current; }

    /** Network thread, background: the primary finished tick {@code tick}. */
    static void permit(long tick) {
        long now=System.nanoTime();
        if(permitArrived!=0) permitIntervalMs.add((now-permitArrived)/1e6);
        permitArrived=now;
        if(tick>permitted) permitted=tick;
    }

    /** Server thread, end of every server tick (both roles). */
    public static void endOfTick(MinecraftServer server) {
        ExperimentProbe.serverTick(server);
        // Every runtime publishes its boundary once per server tick, after the tick's changes and before any permit.
        RedstoneBridge.tick(server);
        if(!enabled()) return;
        if(BridgeCore.isPrimary()) BridgeCore.send("event","*",new Event("mmlf:tick",Map.of("tick",Long.toString(server.getTickCount()))));
    }

    /**
     * Background server thread, in place of the wait for the next tick: runs queued tasks until a permit arrives (or
     * none came for a while) and reports whether it waited for one, so the caller restarts its tick timer.
     */
    public static boolean awaitPermit(MinecraftServer server,java.util.function.LongSupplier now,java.util.function.Consumer<Boolean> chunkWork) {
        if(!enabled() || BridgeCore.isPrimary() || permitted==0) return false;
        long start=now.getAsLong();
        while(server.pollTask()) { }
        server.managedBlock(()->{
            chunkWork.accept(true);
            return executed<permitted || now.getAsLong()-Math.max(start,permitArrived)>FALLBACK_NANOS || !BridgeCore.connected();
        });
        // Messages that arrived before the permit (the primary's signals of that tick) are applied before this tick;
        // with the next tick due now, chunk work no longer counts as having time.
        chunkWork.accept(false);
        while(server.pollTask()) { }
        long end=now.getAsLong();
        if(executed<permitted) {
            if(permitted-executed>MAX_CATCH_UP) { skipped+=permitted-executed-1; executed=permitted-1; }
            if(permitted-executed>1) catchUps++;
            executed++; current=executed; waits++; waitMs.add((end-start)/1e6);
        } else { fallbacks++; current=0; }
        lastPermitNanos=end;
        return true;
    }

    static Map<String,Object> metrics() {
        Map<String,Object> m=new java.util.LinkedHashMap<>();
        var w=waitMs.drain(); var p=permitIntervalMs.drain();
        m.put("lockstepPermitted",permitted); m.put("lockstepExecuted",executed); m.put("lockstepWaits",waits); m.put("lockstepFallbacks",fallbacks);
        m.put("lockstepSkipped",skipped); m.put("lockstepCatchUps",catchUps);
        m.put("lockstepWaitP50Ms",w.p50()); m.put("lockstepWaitP95Ms",w.p95());
        m.put("permitIntervalP50Ms",p.p50()); m.put("permitIntervalP95Ms",p.p95()); m.put("permitIntervalP99Ms",p.p99());
        waits=fallbacks=catchUps=0;
        return m;
    }
    static void log(String event,Map<String,Object> values) { MmlfLog.info("experiment",event,values); }
}
