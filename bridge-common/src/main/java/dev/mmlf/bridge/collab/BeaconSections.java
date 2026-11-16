package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.transport.SessionLink;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;

/**
 * A background's beacon beams. The beam's sections (where it is coloured by glass) are computed by the beacon's block
 * entity on each side from its own level. A background's server reads the primary's map ({@link SharedWorld}), so its
 * beacon sees the primary's glass above it; its client level does not. The client therefore shows the sections its
 * own server computed (same JVM), and the beam the primary draws is coloured as the one world is.
 */
public final class BeaconSections {
    private BeaconSections() {}
    /** This world's beacons: gone when it closes ({@link dev.mmlf.bridge.world.Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        final Map<String,List<BeaconBlockEntity.BeaconBeamSection>> computed=new ConcurrentHashMap<>();
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("beacon_sections",State::new);
    private static Map<String,List<BeaconBlockEntity.BeaconBeamSection>> computed() {
        var world=dev.mmlf.bridge.world.Worlds.current(); return world==null ? Map.of() : world.get(STATE).computed;
    }
    private static String key(Level level,BlockPos pos) { return level.dimension().location()+"@"+pos.asLong(); }
    private static boolean background() { return !SessionLink.isPrimary() && SessionLink.connected(); }

    /**
     * Server thread, the beacon's scan for glass above it: vanilla stops at its own world's surface. This runtime's
     * heightmap knows only its own blocks (the primary's glass above a beacon is not in it), so it scans to the top.
     */
    public static int height(Level level,int own) {
        if(level.isClientSide() || !background()) return own;
        return Math.max(own,level.getMaxBuildHeight()-1);
    }
    /** Server thread, after a beacon's tick: its sections as last computed. */
    public static void computed(Level level,BlockPos pos,List<BeaconBlockEntity.BeaconBeamSection> sections) {
        if(level.isClientSide() || !background()) return;
        var map=computed(); if(map.isEmpty() && dev.mmlf.bridge.world.Worlds.current()==null) return;
        var before=map.put(key(level,pos),List.copyOf(sections));
        if(before==null || before.size()!=sections.size())
            dev.mmlf.common.MmlfLog.info("render.submission","beacon_sections",Map.of("position",pos.toShortString(),"sections",sections.size()));
    }
    /** Client: the sections the server computed for this beacon, or null to keep its own. */
    public static List<BeaconBlockEntity.BeaconBeamSection> shown(Level level,BlockPos pos) {
        if(level==null || !level.isClientSide() || !background()) return null;
        var map=computed(); return map.isEmpty() ? null : map.get(key(level,pos));
    }
}
