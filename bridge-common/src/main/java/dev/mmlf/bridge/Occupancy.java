package dev.mmlf.bridge;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Messages.ObjectState;
import dev.mmlf.common.Wire;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The primary's world knows where other runtimes' blocks are. The primary holds the shared vanilla world, but another
 * runtime's block exists only in that runtime's world: without this, the primary's world reads air there, so its light
 * passes through, faces next to it are drawn and shaded as if open, water flows in, mobs spawn inside, and the terrain
 * renderer has nothing at that position to compile. The opposite direction is {@link NeighborMirror}.
 * <p>
 * From the boundary objects other runtimes publish, every position they occupy reads as a {@link StandInBlock} carrying
 * the owner's light and occlusion behaviour and collision boxes:
 * <ul>
 * <li>Server: reads of the position answer the stand-in ({@code LevelChunkMixin}), nothing is placed, so nothing is
 * saved, dropped or ticked. The primary's own block, where it has one, always wins.</li>
 * <li>Client: chunk data comes from the server's storage, not from reads, so the client chunk holds the stand-in itself,
 * written whenever it changes and whenever the chunk arrives again.</li>
 * </ul>
 * Only the primary hosts stand-ins. Backgrounds keep seeing other runtimes' blocks only as mirrored neighbours.
 */
public final class Occupancy {
    private Occupancy() {}
    /** One position occupied by another runtime: what reads answer there, and its collision boxes relative to the block. */
    record Entry(String id,String owner,BlockState state,VoxelShape collision) {}
    /** Per dimension, packed position to entry. Read from any thread that reads chunks or collisions. */
    private static final Map<String,Map<Long,Entry>> occupied=new ConcurrentHashMap<>();
    private static final Map<String,Boundary.At> positions=new ConcurrentHashMap<>();
    private static final Direction[] DIRECTIONS=Direction.values();
    private static volatile boolean any;
    private static boolean unregisteredLogged;
    /** {@code MMLF_OCCUPANCY=off} (or {@code -Dmmlf.occupancy=off}): the primary's world reads air there, as before. */
    private static final boolean ENABLED=!"off".equalsIgnoreCase(System.getProperty("mmlf.occupancy",System.getenv().getOrDefault("MMLF_OCCUPANCY","on")));

    /** Network thread: another runtime's boundary object appeared, changed or went away. */
    static void receive(ObjectState object) {
        if(!ENABLED || !BridgeCore.isPrimary() || object==null || object.owner()==null || object.owner().equals(BridgeCore.runtimeId())) return;
        boolean gone=object.type()==null || object.type().equals("minecraft:air");
        Boundary.At previous=positions.get(object.id());
        if(gone) { if(previous!=null) { positions.remove(object.id()); update(previous,null,true); } return; }
        BlockState standIn=StandInBlock.of(!"partial".equals(object.state().get("_occlusion")),parseInt(object.state().get("_light")));
        if(standIn==null) {
            if(!unregisteredLogged) { unregisteredLogged=true; MmlfLog.warn("occupancy","stand_in_unregistered",Map.of()); }
            return;
        }
        var at=new Boundary.At(object.dimension(),new BlockPos(object.x(),object.y(),object.z()));
        if(previous!=null && !previous.equals(at)) update(previous,null,true);
        positions.put(object.id(),at);
        update(at,new Entry(object.id(),object.owner(),standIn,collision(object.state().get("_collision"))),true);
    }
    /** A runtime left ({@code owner}) or the link dropped ({@code null}): its positions read as this world's own again. */
    static void forget(String owner) {
        for(var entry:new ArrayList<>(positions.entrySet())) {
            var at=entry.getValue(); var states=occupied.get(at.dimension());
            Entry current=states==null ? null : states.get(at.pos().asLong());
            if(owner!=null && current!=null && !owner.equals(current.owner())) continue;
            positions.remove(entry.getKey());
            // Not a removal in the owner's world: blocks attached to the stand-in keep their shape.
            update(at,null,false);
        }
    }

    private static void update(Boundary.At at,Entry entry,boolean reshape) {
        var states=occupied.computeIfAbsent(at.dimension(),d->new ConcurrentHashMap<>());
        long key=at.pos().asLong();
        Entry before=entry==null ? states.remove(key) : states.put(key,entry);
        if(entry==null && before==null) return;
        any=occupied.values().stream().anyMatch(m->!m.isEmpty());
        boolean changed=before==null || entry==null || !before.state().equals(entry.state());
        var mc=Minecraft.getInstance(); MinecraftServer server=mc.getSingleplayerServer();
        if(server==null) return; // applied when the chunks load
        server.execute(()->{
            ServerLevel level=RedstoneBridge.level(server,at.dimension());
            if(level==null || !level.isLoaded(at.pos())) return;
            LevelChunk chunk=level.getChunkAt(at.pos());
            NeighborMirror.reflag(chunk);
            BlockState underlying=own(chunk,at.pos());
            if(!changed) return;
            level.getChunkSource().getLightEngine().checkBlock(at.pos());
            BlockState now=level.getBlockState(at.pos());
            if(reshape) reshapeNeighbours(level,at.pos(),now);
            BlockState client=entry!=null && underlying.isAir() ? entry.state() : underlying;
            mc.tell(()->writeClient(mc,at,client));
            MmlfLog.debug("occupancy",entry==null?"released":"occupied",Map.of("position",at.pos().toShortString(),"state",now.toString()));
            MmlfLog.sampled("occupancy","stand_ins",5000,Map.of("positions",positions.size(),"last",at.pos().toShortString(),"state",now.toString(),"client",client.toString()));
        });
    }

    /** As when a block changes: this world's blocks beside it may connect differently, but never disappear. */
    private static void reshapeNeighbours(ServerLevel level,BlockPos pos,BlockState now) {
        for(Direction d:DIRECTIONS) {
            BlockPos next=pos.relative(d); BlockState own=level.getBlockState(next);
            if(own.isAir() || StandInBlock.is(own)) continue;
            BlockState shaped=own.updateShape(d.getOpposite(),now,level,next,pos);
            // The other runtime's block may be momentary (its world still being built); a removal cannot be undone.
            if(shaped!=own && shaped.is(own.getBlock())) level.setBlock(next,shaped,Block.UPDATE_ALL);
        }
    }

    /** Render thread: the client chunk shows the stand-in, or what the server holds there again. */
    private static void writeClient(Minecraft mc,Boundary.At at,BlockState target) {
        ClientLevel level=mc.level;
        if(level==null || !level.dimension().location().toString().equals(at.dimension()) || !level.isLoaded(at.pos())) return;
        BlockState current=level.getBlockState(at.pos());
        if(current==target) return;
        // Only replaces what the stand-in may stand for: emptiness, or an earlier stand-in.
        if(!current.isAir() && !StandInBlock.is(current)) return;
        level.setBlock(at.pos(),target,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
        dev.mmlf.bridge.render.TerrainMeshes.invalidate();
    }

    /** Render thread, a chunk arrived from the server: its storage holds air where stand-ins belong. */
    public static void clientChunkLoaded(ClientLevel level,ChunkPos chunk) {
        if(!any || !BridgeCore.isPrimary()) return;
        var states=occupied.get(level.dimension().location().toString()); if(states==null) return;
        var mc=Minecraft.getInstance();
        String dimension=level.dimension().location().toString();
        for(var e:states.entrySet()) {
            long packed=e.getKey();
            if((BlockPos.getX(packed)>>4)!=chunk.x || (BlockPos.getZ(packed)>>4)!=chunk.z) continue;
            var at=new Boundary.At(dimension,BlockPos.of(packed)); BlockState state=e.getValue().state();
            mc.tell(()->writeClient(mc,at,state));
        }
    }
    /** Server thread, a chunk became loaded: its positions are flagged, and emitted light is spread. */
    static void chunk(LevelChunk chunk,boolean loaded) {
        if(!loaded || !any || !(chunk.getLevel() instanceof ServerLevel level)) return;
        var states=occupied.get(level.dimension().location().toString()); if(states==null) return;
        var cp=chunk.getPos(); List<BlockPos> emitting=new ArrayList<>();
        for(var e:states.entrySet()) {
            long packed=e.getKey();
            if((BlockPos.getX(packed)>>4)==cp.x && (BlockPos.getZ(packed)>>4)==cp.z && e.getValue().state().getLightEmission()>0) emitting.add(BlockPos.of(packed));
        }
        // Light sources of a loaded chunk are found from its storage, which holds air here.
        if(!emitting.isEmpty()) level.getServer().execute(()->{ for(BlockPos pos:emitting) if(level.isLoaded(pos)) level.getChunkSource().getLightEngine().checkBlock(pos); });
    }

    /** Whether any position of the chunk is occupied. */
    static boolean covers(LevelChunk chunk) {
        if(!any) return false;
        var states=occupied.get(chunk.getLevel().dimension().location().toString()); if(states==null) return false;
        var cp=chunk.getPos();
        for(long packed:states.keySet()) if((BlockPos.getX(packed)>>4)==cp.x && (BlockPos.getZ(packed)>>4)==cp.z) return true;
        return false;
    }
    /** {@code LevelChunkMixin}, server chunks flagged as overlaid: the stand-in, unless this world has its own block there. */
    public static BlockState read(LevelChunk chunk,BlockPos pos,BlockState own) {
        if(!any || !own.isAir()) return own;
        var states=occupied.get(chunk.getLevel().dimension().location().toString());
        Entry entry=states==null ? null : states.get(pos.asLong());
        return entry==null ? own : entry.state();
    }
    /** {@link StandInBlock}: the owner's collision boxes at the position, or null when unknown. */
    static VoxelShape collision(BlockGetter getter,BlockPos pos) {
        if(!any) return null;
        long key=pos.asLong();
        if(getter instanceof Level level) {
            var states=occupied.get(level.dimension().location().toString());
            Entry entry=states==null ? null : states.get(key);
            return entry==null ? null : entry.collision();
        }
        // Region views (path finding, section copies) do not name their dimension.
        for(var states:occupied.values()) { Entry entry=states.get(key); if(entry!=null) return entry.collision(); }
        return null;
    }

    /** What the server chunk's own storage holds, without the overlay. */
    private static BlockState own(LevelChunk chunk,BlockPos pos) {
        int index=chunk.getSectionIndex(pos.getY());
        if(index<0 || index>=chunk.getSections().length) return Blocks.AIR.defaultBlockState();
        return chunk.getSection(index).getBlockState(pos.getX()&15,pos.getY()&15,pos.getZ()&15);
    }
    private static VoxelShape collision(String encoded) {
        if(encoded==null) return null;
        try {
            double[][] boxes=Wire.JSON.fromJson(encoded,double[][].class);
            VoxelShape shape=Shapes.empty();
            for(double[] b:boxes) {
                if(b==null || b.length!=6 || b[3]<=b[0] || b[4]<=b[1] || b[5]<=b[2]) continue;
                shape=Shapes.or(shape,Shapes.box(clamp(b[0]),clamp(b[1]),clamp(b[2]),clamp(b[3]),clamp(b[4]),clamp(b[5])));
            }
            return shape.optimize();
        } catch(RuntimeException e) { return null; }
    }
    /** Collision boxes may reach past their block (fences); one block either way is the vanilla limit. */
    private static double clamp(double v) { return Double.isFinite(v) ? Math.max(-1,Math.min(2,v)) : 0; }
    private static int parseInt(String value) { try { return value==null ? 0 : Integer.parseInt(value); } catch(NumberFormatException e) { return 0; } }
}
