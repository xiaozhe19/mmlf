package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.common.EndpointTable;
import dev.mmlf.common.MmlfLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/**
 * Primary: other runtimes' plain blocks ({@link Boundary#plain}) that are interacting with the primary's world right now.
 * Objects are always on the boundary; plain blocks (a mod lamp, door or probe without a block entity, and the ores and
 * plants generation places by the hundred thousand) only while something happens next to them. The trigger is native:
 * the {@link StandInBlock} at the position gets Minecraft's own neighbour update when a lever is pulled, a wire powers,
 * a campfire is lit or a block beside it is placed or broken. The position then gets what an object gets
 * ({@link RedstoneBridge#tick}): the signals the primary's world gives it, and its vanilla neighbours published to the
 * owner ({@link NeighborMirror}), whose block is updated natively when they arrive. It stays open for
 * {@link #IDLE_TICKS} after its last update, and as long as a signal still reaches it.
 * <p>
 * Updates between stand-ins (another runtime's blocks being placed next to each other) are that runtime's own business
 * and open nothing. Server thread only.
 */
final class PlainEndpoints {
    private PlainEndpoints() {}
    /** This world's part of PlainEndpoints: made when the world first needs it, gone when it closes ({@link Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        final EndpointTable table=new EndpointTable(IDLE_TICKS,CAPACITY);
        boolean fullLogged;
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("plain_endpoints",State::new);
    /** The open world's state; with none open, a throwaway one: what is written to it reaches no world. */
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }

    static final long IDLE_TICKS=600;
    private static final int CAPACITY=4096;
    private static long tick, opened;

    /** {@link StandInBlock#neighborChanged}, primary's server: the block at {@code from} changed next to the stand-in at {@code pos}. */
    static void touched(ServerLevel level,BlockPos pos,BlockPos from) {
        if(!SessionLink.isPrimary() || StandInBlock.is(level.getBlockState(from))) return;
        String dimension=level.dimension().location().toString();
        boolean was=state().table.isActive(dimension,pos.asLong());
        if(state().table.touch(dimension,pos.asLong(),tick)) { if(!was) opened++; return; }
        if(!state().fullLogged) { state().fullLogged=true; MmlfLog.warn("redstone","plain_endpoints_full",Map.of("capacity",CAPACITY,"position",pos.toShortString())); }
    }
    /**
     * {@link StandInBlock#onPlace}: a stand-in appeared (the owner placed its block, or reported it late). No neighbour
     * changed, so nothing would open it, yet a wire beside it may already be powered. One check, not an opening: stand-ins
     * placed by the hundred for generated ores must not all become boundary.
     */
    static void placed(ServerLevel level,BlockPos pos) {
        if(!SessionLink.isPrimary() || !level.hasNeighborSignal(pos)) return;
        String dimension=level.dimension().location().toString();
        if(state().table.touch(dimension,pos.asLong(),tick)) opened++;
    }
    /** A signal still reaches the position: it stays open. */
    static void keep(Boundary.At at) { state().table.touch(at.dimension(),at.pos().asLong(),tick); }

    /**
     * {@link RedstoneBridge#tick}, once per server tick: the open positions whose stand-in is loaded. One whose stand-in
     * is gone (the block was removed, or the position is the primary's again) closes; an unloaded one waits to expire.
     */
    static List<Boundary.At> active(MinecraftServer server) {
        tick++;
        state().table.expire(tick);
        if(state().table.size()==0) return List.of();
        List<Boundary.At> out=new ArrayList<>(state().table.size());
        for(var endpoint:state().table.active()) {
            ServerLevel level=RedstoneBridge.level(server,endpoint.dimension());
            BlockPos pos=BlockPos.of(endpoint.packed());
            if(level==null) { state().table.remove(endpoint.dimension(),endpoint.packed()); continue; }
            if(!level.isLoaded(pos)) continue;
            if(!StandInBlock.is(level.getBlockState(pos))) { state().table.remove(endpoint.dimension(),endpoint.packed()); continue; }
            out.add(new Boundary.At(endpoint.dimension(),pos));
        }
        return out;
    }
    static Map<String,Object> metrics() { return Map.of("open",state().table.size(),"opened",opened,"refused",state().table.refused()); }
    static void clear() { state().table.clear(); state().fullLogged=false; }
}
