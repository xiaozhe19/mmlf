package dev.mmlf.bridge;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.*;

/**
 * What a runtime's own mods see next to their blocks. Vanilla blocks belong to the primary and exist only in its world;
 * a background's world has nothing (or stale generated terrain) beside its mod blocks. The primary publishes the vanilla
 * blocks next to other runtimes' objects; a receiving runtime answers reads of those positions from its server chunks
 * with them ({@code LevelChunkMixin}), without placing anything: nothing ticks, drops or saves twice, and the receiver's
 * mods, using Minecraft's own {@code getBlockState}, find the primary's lever, campfire or wire, with its properties.
 *
 * Only vanilla blocks can be built from their published name on every runtime; another loader's mod block is published
 * as air. Server thread only, except {@link #receive}, which hands over to it.
 */
public final class NeighborMirror {
    private NeighborMirror() {}
    /** Per dimension, packed position to state. Read from any thread that reads server chunks (the light engine too). */
    private static final Map<String,Map<Long,BlockState>> overlay=new java.util.concurrent.ConcurrentHashMap<>();
    private static final SignalTable.NeighborOutbox outbox=new SignalTable.NeighborOutbox();
    private static final Direction[] DIRECTIONS=Direction.values();

    /** Implemented on {@link LevelChunk} by {@code LevelChunkMixin}: whether any position of the chunk is overlaid. */
    public interface Overlaid { boolean mmlf$overlaid(); void mmlf$setOverlaid(boolean value); }

    /** {@code LevelChunkMixin}, only for chunks flagged as overlaid. */
    public static BlockState read(LevelChunk chunk,BlockPos pos,BlockState own) {
        var states=overlay.get(chunk.getLevel().dimension().location().toString());
        if(states==null) return own;
        BlockState mirrored=states.get(pos.asLong());
        return mirrored==null ? own : mirrored;
    }
    /** {@code MirroredNeighborMixin}: whether the position shows another runtime's block, which must not run here. */
    public static boolean mirrored(net.minecraft.world.level.LevelAccessor level,BlockPos pos) {
        if(!any || !(level instanceof ServerLevel server)) return false;
        var states=overlay.get(server.dimension().location().toString());
        return states!=null && states.containsKey(pos.asLong());
    }
    private static volatile boolean any;
    private static void refreshAny() { any=overlay.values().stream().anyMatch(m->!m.isEmpty()); }

    /** Primary, server thread, once a tick: publishes the vanilla blocks next to other runtimes' objects that changed. */
    static void tick(MinecraftServer server,List<Boundary.At> neighbours) {
        List<NeighborState> boundary=new ArrayList<>(neighbours.size());
        for(Boundary.At at:neighbours) {
            ServerLevel level=RedstoneBridge.level(server,at.dimension()); if(level==null) continue;
            BlockState state=level.getBlockState(at.pos());
            // A mod block of the primary cannot be built elsewhere: its place reads as empty, which it is for that runtime's blocks.
            String name=Boundary.vanilla(state) ? BlockStateParser.serialize(state) : "minecraft:air";
            boundary.add(new NeighborState(at.dimension(),at.pos().getX(),at.pos().getY(),at.pos().getZ(),name));
        }
        var changes=outbox.diff(boundary);
        if(changes.isEmpty()) return;
        BridgeCore.sendReliable("neighbors","*",new Neighbors(changes));
        MmlfLog.debug("neighbors","published",Map.of("changes",changes.size(),"first",changes.get(0).location()));
    }

    /** Network thread: the primary's blocks next to this runtime's objects changed. */
    static void receive(String source,Neighbors neighbors) {
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if(server==null || neighbors==null || neighbors.changes()==null || !source.equals(BridgeCore.primaryId())) return;
        server.execute(()->{
            for(NeighborState change:neighbors.changes()) {
                ServerLevel level=RedstoneBridge.level(server,change.dimension()); if(level==null) continue;
                BlockPos pos=new BlockPos(change.x(),change.y(),change.z());
                BlockState state=null;
                if(change.state()!=null) {
                    try { state=BlockStateParser.parseForBlock(level.registryAccess().lookupOrThrow(Registries.BLOCK),change.state(),false).blockState(); }
                    catch(Exception e) { MmlfLog.warn("neighbors","unreadable_state",Map.of("state",change.state(),"reason",String.valueOf(e.getMessage()))); continue; }
                }
                // This runtime's own block wins: a published neighbour never hides what this runtime placed itself.
                if(state!=null && Boundary.owns(level.getChunkAt(pos).getBlockState(pos),pos)) continue;
                set(level,pos,state);
            }
        });
    }
    private static void set(ServerLevel level,BlockPos pos,BlockState state) {
        String dimension=level.dimension().location().toString();
        var states=overlay.computeIfAbsent(dimension,d->new java.util.concurrent.ConcurrentHashMap<>());
        BlockState before=state==null ? states.remove(pos.asLong()) : states.put(pos.asLong(),state);
        if(Objects.equals(before,state)) return;
        refreshAny();
        if(level.isLoaded(pos)) {
            LevelChunk chunk=level.getChunkAt(pos);
            reflag(chunk);
            BlockState now=level.getBlockState(pos);
            // As when a block changes: neighbours are told, and this runtime's own blocks beside it update their shape.
            level.updateNeighborsAt(pos,now.getBlock());
            for(Direction d:DIRECTIONS) {
                BlockPos next=pos.relative(d); BlockState own=level.getBlockState(next);
                if(!Boundary.owns(own,next)) continue;
                BlockState shaped=own.updateShape(d.getOpposite(),now,level,next,pos);
                // A mirrored neighbour may change how this runtime's block connects, never whether it exists: the
                // other runtime's block may be momentary (its world still being built), and a removal cannot be undone.
                if(shaped!=own && shaped.is(own.getBlock())) level.setBlock(next,shaped,3);
                else if(shaped!=own) MmlfLog.info("neighbors","shape_removal_ignored",Map.of("position",next.toShortString(),"block",own.toString(),"mirrored",pos.toShortString(),"neighbour",now.toString()));
            }
        }
    }
    /** This runtime placed its own block where a neighbour was mirrored: the mirror gives way. */
    static void drop(ServerLevel level,BlockPos pos) {
        var states=overlay.get(level.dimension().location().toString());
        if(states==null || states.remove(pos.asLong())==null) return;
        refreshAny();
        if(level.isLoaded(pos)) reflag(level.getChunkAt(pos));
    }
    /** A chunk was loaded again: its mirrored positions are flagged on the new chunk object. */
    static void chunk(LevelChunk chunk,boolean loaded) {
        if(!loaded) return;
        reflag(chunk);
    }
    /** Whether reads of the chunk consult an overlay: mirrored neighbours here, or other runtimes' blocks ({@link Occupancy}). */
    static void reflag(LevelChunk chunk) {
        var states=overlay.get(chunk.getLevel().dimension().location().toString());
        ((Overlaid)chunk).mmlf$setOverlaid(states!=null && hasAny(states,chunk) || Occupancy.covers(chunk));
    }
    private static boolean hasAny(Map<Long,BlockState> states,LevelChunk chunk) {
        var cp=chunk.getPos();
        for(long packed:states.keySet()) if((BlockPos.getX(packed)>>4)==cp.x && (BlockPos.getZ(packed)>>4)==cp.z) return true;
        return false;
    }
    /** The primary left or the link dropped: mirrored neighbours vanish, and this runtime's blocks see their own world again. */
    static void clear(String runtime) {
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{
            if(runtime!=null && !runtime.equals(BridgeCore.primaryId())) { outbox.reset(); return; }
            for(var entry:new ArrayList<>(overlay.entrySet())) {
                ServerLevel level=RedstoneBridge.level(server,entry.getKey()); if(level==null) continue;
                for(long packed:new ArrayList<>(entry.getValue().keySet())) set(level,BlockPos.of(packed),null);
            }
            outbox.reset();
        });
    }
    static void runtimeJoined() {
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(outbox::reset);
    }
}
