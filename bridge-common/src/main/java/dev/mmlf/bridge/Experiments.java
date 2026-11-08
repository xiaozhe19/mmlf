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
 * <li>{@code geometry}: a background publishes its chunk-section meshes and block atlas; the primary draws them in its
 * own section layers instead of compositing the background's world pixels ({@link dev.mmlf.bridge.render.GeometryLayer}).</li>
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
