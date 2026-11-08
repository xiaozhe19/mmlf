package dev.mmlf.bridge.render;

import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.common.MmlfLog;
import java.util.*;

/**
 * Which parts of the world a background runtime still draws when the primary already draws them. Every part a
 * background skips here is invisible in the composite anyway or drawn twice: its sky lies at the far plane, where the
 * primary's depth always wins, and its rain and snow would be baked into the colour of its own blocks on top of the
 * primary's weather. Block geometry is already split by owner ({@code SectionCompilerMixin}).
 *
 * <p>{@code -Dmmlf.renderOwnership} (or {@code MMLF_RENDER_OWNERSHIP}): {@code off} (default) draws everything; {@code safe} skips a part only when no
 * other mixin configuration in this runtime targets the classes that draw it, since a mod that changes how the sky or
 * weather is drawn (shaders, sky mods) may draw something of its own there. This never filters by mod id.
 */
public final class RenderOwnership {
    private RenderOwnership() {}
    public enum Part { SKY, WEATHER }
    private static final String MODE=System.getProperty("mmlf.renderOwnership",System.getenv().getOrDefault("MMLF_RENDER_OWNERSHIP","off")).toLowerCase(Locale.ROOT);
    /** Diagnostic fallback: keep the far runtime's block meshes intact instead of applying owner filtering. */
    private static final boolean FILTER_BLOCKS=!System.getProperty("mmlf.blockOwnership",
        System.getenv().getOrDefault("MMLF_BLOCK_OWNERSHIP","filtered")).equalsIgnoreCase("full");
    public static boolean filterBlocks() { return FILTER_BLOCKS; }
    /** Classes whose drawing a part relies on: a foreign mixin into any of them keeps the part drawn. */
    private static final Map<Part,List<Class<?>>> DRAWN_BY=Map.of(
        Part.SKY,List.of(net.minecraft.client.renderer.LevelRenderer.class,net.minecraft.client.renderer.DimensionSpecialEffects.class),
        Part.WEATHER,List.of(net.minecraft.client.renderer.LevelRenderer.class));
    private static volatile Map<Part,Boolean> decided;

    /** Whether this runtime skips drawing {@code part}; only ever true for a connected background. */
    public static boolean skip(Part part) {
        if(!MODE.equals("safe") || BridgeCore.isPrimary() || !BridgeCore.connected()) return false;
        Map<Part,Boolean> d=decided;
        if(d==null) d=decided=decide();
        return d.getOrDefault(part,false);
    }

    /** Decided once, when the first part is asked about: mixin configurations are all known by the time the world renders. */
    private static synchronized Map<Part,Boolean> decide() {
        if(decided!=null) return decided;
        Map<String,Set<String>> foreign=foreignTargets();
        Map<Part,Boolean> result=new EnumMap<>(Part.class);
        Map<String,Object> report=new LinkedHashMap<>();
        for(Part part:Part.values()) {
            List<String> blockers=new ArrayList<>();
            for(Class<?> drawer:DRAWN_BY.get(part)) {
                String name=drawer.getName();
                foreign.forEach((config,targets)->{ if(targets.contains(name)) blockers.add(config+" -> "+name); });
            }
            result.put(part,blockers.isEmpty());
            report.put(part.name(),blockers.isEmpty()?"skipped":"drawn: "+blockers);
        }
        MmlfLog.info("render","ownership",report);
        return result;
    }

    /** Targets of every mixin configuration but MMLF's own, by configuration name; runtime class names, dotted. */
    private static Map<String,Set<String>> foreignTargets() {
        Map<String,Set<String>> out=new TreeMap<>();
        try {
            for(var config:org.spongepowered.asm.mixin.Mixins.getConfigs()) {
                String name=config.getName();
                if(name.startsWith("mmlf.")) continue;
                Set<String> targets=new HashSet<>();
                for(String t:config.getConfig().getTargets()) targets.add(t.replace('/','.'));
                out.put(name,targets);
            }
        } catch(RuntimeException|LinkageError e) {
            // Unknown targets: draw everything rather than guess.
            MmlfLog.warn("render","ownership_targets_unknown",Map.of("exception",e.toString()));
            out.put("<unknown>",Set.of(net.minecraft.client.renderer.LevelRenderer.class.getName(),net.minecraft.client.renderer.DimensionSpecialEffects.class.getName()));
        }
        return out;
    }
}
