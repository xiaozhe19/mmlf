package dev.mmlf.bridge;

import java.util.Locale;
import java.util.Set;

/**
 * Thin-runtime experiments, all off unless named in {@code MMLF_EXPERIMENTS} (or {@code -Dmmlf.experiments}), comma
 * separated. Each runtime of a session reads the same variable from the launcher's environment.
 * <ul>
 * <li>{@code probe}: the primary runs {@link ExperimentProbe} after the collaboration self-test.</li>
 * <li>{@code thin_world}: a background opens a void world and places only what it owns.</li>
 * <li>{@code on_demand}: a background renders at a low rate while neither the camera nor its objects change.</li>
 * <li>{@code render_scale}: a background renders at {@code mmlf.renderScale} (default 0.5) of the primary's size.</li>
 * <li>{@code geometry}: the runtime that does not draw the scene meshes the sections holding its own blocks and
 * publishes them with its block atlas ({@link dev.mmlf.bridge.render.GeometryLayer}).</li>
 * <li>{@code geometry_replay} (with {@code geometry}): unified render layer. The render host draws the submitted
 * sections and the submitter's recorded block entities and entities; once it confirms, the submitter leaves all of it
 * out of its own world pass and eventually skips that pass ({@link dev.mmlf.bridge.render.NativeWorld}).</li>
 * <li>{@code follow}: a background loads the chunks of its objects as far as the primary loads them, and its players
 * hold no chunk tickets ({@link ChunkFollow}).</li>
 * <li>{@code small_view}: a background uses render distance 2, so its integrated server loads few chunks.</li>
 * <li>{@code lockstep}: a background's server ticks once per primary server tick ({@link TickSync}).</li>
 * </ul>
 */
public final class Experiments {
    private Experiments() {}
    private static final Set<String> ENABLED=parse(System.getProperty("mmlf.experiments",System.getenv("MMLF_EXPERIMENTS")));
    public static final boolean PROBE=ENABLED.contains("probe");
    public static final boolean THIN_WORLD=ENABLED.contains("thin_world");
    public static final boolean ON_DEMAND=ENABLED.contains("on_demand");
    public static final boolean RENDER_SCALE=ENABLED.contains("render_scale");
    public static final boolean LOCKSTEP=ENABLED.contains("lockstep");
    public static final boolean GEOMETRY=ENABLED.contains("geometry");
    /** Unified render layer; the composited native frame remains only as a fallback for residual content. */
    public static final boolean GEOMETRY_REPLAY=GEOMETRY && ENABLED.contains("geometry_replay");
    /** Diagnostic whole-scene presentation; incomplete scene types still require native fallback. */
    public static final boolean UNIFIED_PREVIEW=GEOMETRY_REPLAY && ENABLED.contains("unified_preview");
    public static final boolean SMALL_VIEW=ENABLED.contains("small_view");
    public static final boolean FOLLOW=ENABLED.contains("follow");
    public static final double SCALE=RENDER_SCALE ? Double.parseDouble(System.getProperty("mmlf.renderScale","0.5")) : 1.0;
    public static String describe() { return ENABLED.isEmpty() ? "none" : String.join(",",new java.util.TreeSet<>(ENABLED)); }
    private static Set<String> parse(String value) {
        if(value==null || value.isBlank()) return Set.of();
        var result=new java.util.HashSet<String>();
        for(String part:value.split(",")) if(!part.isBlank()) result.add(part.trim().toLowerCase(Locale.ROOT));
        return Set.copyOf(result);
    }
}
