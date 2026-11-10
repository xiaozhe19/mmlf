package dev.mmlf.bridge;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * Redstone between mod blocks of different runtimes. Vanilla redstone runs natively, all of it in the primary's world;
 * another runtime's mod block is a signal endpoint there:
 * <ul>
 * <li>its inputs: each runtime measures natively what its own world gives the positions next to other runtimes'
 * objects (a lever, a wire, a powered block beside them) and publishes it;</li>
 * <li>its outputs: each runtime measures what its own signal-emitting or comparator-readable objects give out and
 * publishes it.</li>
 * </ul>
 * A receiving runtime answers Minecraft's own signal queries at those positions with the published values
 * ({@code BlockStateSignalMixin}, {@code ComparatorMixin}) and lets native logic react through ordinary neighbour
 * updates. An object's output is the block's own signal, never power conducted through it: a vanilla wire pointing into a
 * remote solid block would otherwise get its own power back and never switch off. Signals take effect up to one tick
 * later than within one runtime, depending on how the two runtimes' ticks fall.
 *
 * Server thread only, except {@link #receive}, which hands over to it.
 */
public final class RedstoneBridge {
    private RedstoneBridge() {}
    private static final SignalTable.Outbox outbox=new SignalTable.Outbox();
    private static final SignalTable.Inbox inbox=new SignalTable.Inbox();
    private static volatile boolean any;
    private static final Direction[] DIRECTIONS=Direction.values();
    private static long metricSince, metricChanges;

    /** {@code BlockStateSignalMixin}: what other runtimes give a neighbour of this position; 0 when nothing is published. */
    public static int remoteSignal(Level level,BlockPos pos,Direction direction,boolean strong) {
        if(!any || level.isClientSide()) return 0;
        return inbox.signal(level.dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ(),direction.ordinal(),strong);
    }
    /** {@code ComparatorMixin}: what a comparator reads from another runtime's object at this position, or -1. */
    public static int remoteAnalog(Level level,BlockPos pos) {
        if(!any || level.isClientSide()) return -1;
        return inbox.analog(level.dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());
    }

    /** Server thread, every tick: indexes what changed, then publishes this runtime's boundary signals and neighbours. */
    static void tick(MinecraftServer server) {
        Boundary.tick(server);
        ChunkFollow.tick(server);
        SharedWorld.serverTick(server);
        ContainerBridge.tick(server);
        List<SignalState> boundary=new ArrayList<>();
        Set<Boundary.At> seen=new HashSet<>();
        for(ObjectState own:Boundary.localObjects()) {
            ServerLevel level=level(server,own.dimension()); if(level==null) continue;
            BlockPos pos=new BlockPos(own.x(),own.y(),own.z());
            if(!level.isLoaded(pos)) continue;
            BlockState state=level.getBlockState(pos);
            if((state.isSignalSource() || state.hasAnalogOutputSignal()) && seen.add(new Boundary.At(own.dimension(),pos))) boundary.add(output(level,own.dimension(),pos,state));
        }
        List<Boundary.At> neighbours=new ArrayList<>();
        for(ObjectState other:Boundary.remoteObjects()) {
            ServerLevel level=level(server,other.dimension()); if(level==null) continue;
            BlockPos at=new BlockPos(other.x(),other.y(),other.z());
            for(Direction d:DIRECTIONS) {
                BlockPos pos=at.relative(d); Boundary.At key=new Boundary.At(other.dimension(),pos);
                if(Boundary.remoteAt(other.dimension(),pos) || !level.isLoaded(pos) || !seen.add(key)) continue;
                neighbours.add(key);
                boundary.add(input(level,other.dimension(),pos));
            }
        }
        if(BridgeCore.isPrimary()) NeighborMirror.tick(server,neighbours);
        List<SignalState> changes=outbox.diff(boundary);
        if(changes.isEmpty()) return;
        metricChanges+=changes.size();
        // One batch per tick, kept in order and delivered once: a lost change would leave a lamp lit for good.
        BridgeCore.sendReliable("signals","*",new Signals(changes));
        MmlfLog.debug("redstone","published",Map.of("changes",changes.size(),"first",changes.get(0).location()));
        long now=System.nanoTime();
        if(metricSince==0) metricSince=now;
        if(now-metricSince>=10_000_000_000L) { MmlfLog.info("metrics","redstone",Map.of("changesPerSecond",metricChanges/((now-metricSince)/1e9),"boundary",boundary.size(),"published",outbox.size())); metricChanges=0; metricSince=now; }
    }
    /** What this runtime's object gives out by itself. */
    private static SignalState output(ServerLevel level,String dimension,BlockPos pos,BlockState state) {
        int[] weak=new int[6], strong=new int[6];
        for(Direction d:DIRECTIONS) { weak[d.ordinal()]=state.getSignal(level,pos,d); strong[d.ordinal()]=state.getDirectSignal(level,pos,d); }
        int analog=state.hasAnalogOutputSignal() ? state.getAnalogOutputSignal(level,pos) : -1;
        return new SignalState(dimension,pos.getX(),pos.getY(),pos.getZ(),weak,strong,analog);
    }
    /** What this runtime's world gives another runtime's object from a position next to it, measured natively. */
    private static SignalState input(ServerLevel level,String dimension,BlockPos pos) {
        int[] weak=new int[6], strong=new int[6];
        for(Direction d:DIRECTIONS) { weak[d.ordinal()]=level.getSignal(pos,d); strong[d.ordinal()]=level.getDirectSignal(pos,d); }
        return new SignalState(dimension,pos.getX(),pos.getY(),pos.getZ(),weak,strong,-1);
    }

    /** Network thread: another runtime's boundary changed. */
    static void receive(String source,Signals signals) {
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if(server==null || signals==null || signals.changes()==null) return;
        server.execute(()->{
            List<SignalState> changed=new ArrayList<>();
            for(SignalState change:signals.changes()) if(inbox.apply(source,change)) changed.add(change);
            any=!inbox.isEmpty();
            notifyNeighbours(server,changed);
            if(!changed.isEmpty()) MmlfLog.debug("redstone","received",Map.of("source",source,"changes",changed.size(),"first",changed.get(0).location()));
        });
    }
    /** A runtime left: its signals stop, and what they powered here reacts. */
    static void runtimeLeft(String runtime) {
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{ var gone=inbox.forget(runtime); any=!inbox.isEmpty(); notifyNeighbours(server,gone); outbox.reset(); });
        NeighborMirror.clear(runtime);
        ChunkFollow.forget(runtime); ChunkFollow.reset();
    }
    /** A runtime (re)joined and knows nothing of this boundary: it is published in full on the next tick. */
    static void runtimeJoined() {
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(outbox::reset);
        NeighborMirror.runtimeJoined();
        ChunkFollow.reset();
    }
    static void disconnected() {
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{ var gone=inbox.forgetAll(); any=false; notifyNeighbours(server,gone); outbox.reset(); });
        NeighborMirror.clear(null);
        ChunkFollow.reset();
    }

    /**
     * Lets native logic react: the neighbours of a changed position are told it changed, and so are theirs, since a
     * strongly powered block passes power on to its own neighbours, as a lever's attached block does.
     */
    private static void notifyNeighbours(MinecraftServer server,List<SignalState> changed) {
        for(SignalState s:changed) {
            ServerLevel level=level(server,s.dimension()); if(level==null) continue;
            BlockPos pos=new BlockPos(s.x(),s.y(),s.z());
            if(!level.isLoaded(pos)) continue;
            level.updateNeighborsAt(pos,Blocks.AIR);
            for(Direction d:DIRECTIONS) level.updateNeighborsAt(pos.relative(d),Blocks.AIR);
        }
    }
    static ServerLevel level(MinecraftServer server,String dimension) {
        var location=ResourceLocation.tryParse(dimension);
        return location==null ? null : server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,location));
    }
}
