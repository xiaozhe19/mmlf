package dev.mmlf.bridge.render;

import dev.mmlf.bridge.Experiments;
import dev.mmlf.common.MmlfLog;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;

/**
 * Whether the submitting runtime still renders its own world. While the render host confirms it draws everything this
 * runtime submits ({@link OwnedSectionCompiler#hideNative()}), this runtime's world pass leaves out all of it, and what
 * remains in its frame is only content no generic path reaches (for example a mod drawing straight to the GPU). The
 * exported depth of those frames tells whether anything remains: once nothing has for a while, the world pass is
 * skipped altogether (not while a hosted screen, the native hand or item icons need it), except for one probe frame
 * per second that brings the world pass back as soon as something appears. The composited frame is then only a fallback for such
 * residual content, never a second copy of the scene.
 * <p>{@code MMLF_REMOTE_WORLD} (or {@code -Dmmlf.remoteWorld}): {@code auto} (default) as above, {@code off} skips the
 * world pass whenever hiding is confirmed, {@code on} always renders it (diagnostics).
 */
public final class NativeWorld {
    private NativeWorld() {}
    private static final String MODE=System.getProperty("mmlf.remoteWorld",System.getenv().getOrDefault("MMLF_REMOTE_WORLD","auto")).toLowerCase(Locale.ROOT);
    private static final long CLEAN_NANOS=2_000_000_000L, PROBE_NANOS=1_000_000_000L;
    private static final int CLEAN_FRAMES=10;
    private static long hiddenSince, lastResidual, lastProbe;
    private static int cleanFrames;
    private static boolean skipping, wasHidden, reported;

    /** Whether this runtime's own world pass leaves out what the render host draws: everything it submits, and the
     *  rest of its world, which the render host's own world already shows. Never while {@link DynamicCapture} runs. */
    public static boolean hidden() { return Experiments.GEOMETRY_REPLAY && OwnedSectionCompiler.hideNative() && !DynamicCapture.capturing(); }
    /** Whether the world pass is currently being skipped. */
    public static boolean skipping() { return skipping; }

    /**
     * Render thread, for a frame without the world pass: the client-side work vanilla does only inside that pass.
     * Light data of received chunks and block changes is queued and processed there; without it, sections compiled
     * for submission would see missing or stale light until the next probe frame (blocks flickering dark).
     */
    public static void keepClientWorld(net.minecraft.client.Minecraft mc) {
        if(mc.level==null) return;
        mc.level.pollLightUpdates();
        mc.level.getChunkSource().getLightEngine().runLightUpdates();
    }
    /** Render thread, before each frame: whether this frame renders the world ({@code MinecraftMixin}). */
    public static boolean renderWorld(boolean requested) {
        boolean hidden=Experiments.GEOMETRY_REPLAY && OwnedSectionCompiler.hideNative();
        long now=System.nanoTime();
        if(hidden!=wasHidden) { wasHidden=hidden; hiddenSince=lastResidual=now; cleanFrames=0; }
        // Icon and hosted-screen frames are drawn inside or over the world pass, so they keep it (held items are meshes).
        var mc=net.minecraft.client.Minecraft.getInstance();
        boolean needed=mc.screen!=null || dev.mmlf.bridge.VisualLayers.iconsPending();
        boolean skip=hidden && !MODE.equals("on") && !needed
            && (MODE.equals("off") || now-lastResidual>CLEAN_NANOS && cleanFrames>=CLEAN_FRAMES);
        boolean probe=false;
        if(skip && MODE.equals("auto") && now-lastProbe>PROBE_NANOS) { lastProbe=now; skip=false; probe=true; }
        if(skip!=skipping) {
            skipping=skip;
            // Probe frames, and the return to skipping after one, are not reported; only lasting changes are.
            if(!probe && skip!=reported) { reported=skip; MmlfLog.info("render.submission","native_world",Map.of("rendered",!skip,"mode",MODE)); }
        }
        return requested && !skip;
    }

    /**
     * Render thread, with the exported depth of a frame rendered while hiding (window depth, 1.0 is empty). Floats
     * below 1.0 compare below it as raw bits, so the scan needs no conversion.
     */
    public static void observeDepth(ByteBuffer depth) {
        if(!wasHidden) return;
        var ints=depth.duplicate().order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
        int empty=Float.floatToRawIntBits(1.0f);
        boolean residual=false;
        for(int i=0,n=ints.remaining();i<n;i++) if(ints.get(i)<empty) { residual=true; break; }
        if(residual) {
            if(cleanFrames>0 || skipping) MmlfLog.sampled("render.submission","native_world_residual",5000,Map.of());
            lastResidual=System.nanoTime(); cleanFrames=0;
        } else cleanFrames++;
    }
}
