package dev.mmlf.bridge;

import dev.mmlf.common.Messages;
import dev.mmlf.common.Messages.ChunkBlocks;
import dev.mmlf.common.Messages.ObjectState;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.OwnershipRules;
import dev.mmlf.common.StandInSpec;
import dev.mmlf.common.Wire;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
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
 * The primary's map records which positions are another runtime's: a {@link StandInBlock} there, saved with the map,
 * carrying what the primary's world logic needs of the actual block (light, occlusion, fluid, redstone conduction).
 * That runtime holds the actual block in its own world; a position never holds a block in both.
 * <p>
 * Other runtimes report their blocks (objects one by one, plain blocks per chunk, {@link Boundary}). The primary keeps
 * what they reported per chunk and checks the chunk's positions against its map ({@link #reconcile}, by
 * {@link OwnershipRules}): a reported block takes an empty or replaceable position at once; where the primary's own block
 * is, the primary's wins once the mismatch has settled, and the owner removes its block; a stand-in no longer reported
 * becomes the primary's again. A chunk is checked whenever a report for it arrives, its generation instructions were
 * carried out, it loads, and every second while a mismatch in it is settling, so a lost or late message only delays.
 * Only the primary records anything; while the owner is away its stand-ins stay as they are.
 */
public final class Occupancy {
    private Occupancy() {}
    /** One reported position: the object's id (null for a plain block), its owner, the stand-in it takes, its collision boxes. */
    record Entry(String id,String owner,BlockState state,VoxelShape collision) {}
    /** Per dimension, per chunk ({@link ChunkPos#toLong}), packed position to entry; a chunk's map is never changed once published. */
    private static final Map<String,Map<Long,Long2ObjectOpenHashMap<Entry>>> reported=new ConcurrentHashMap<>();
    /** Object id to where it is. */
    private static final Map<String,Boundary.At> positions=new ConcurrentHashMap<>();
    /** Chunks ("dimension@chunk") whose owner reported their plain blocks since it connected: its silence there then means "none". */
    private static final Set<String> reportedChunks=ConcurrentHashMap.newKeySet();
    /** Per dimension, positions whose owner reported a block before and has removed it since: their stand-ins go at once. */
    private static final Map<String,Set<Long>> released=new ConcurrentHashMap<>();
    private static void release(String dimension,long pos) { released.computeIfAbsent(dimension,d->ConcurrentHashMap.newKeySet()).add(pos); }
    /** Chunks to check ("dimension@chunk"), and mismatches settling there: per dimension, position to when first seen. */
    private static final Set<String> dirty=ConcurrentHashMap.newKeySet();
    private static final Map<String,Map<Long,Long>> settling=new HashMap<>();
    private static final long GRACE_NANOS=3_000_000_000L, TICK_BUDGET_NANOS=2_000_000L;
    private static boolean unregisteredLogged;
    /** {@code MMLF_OCCUPANCY=off} (or {@code -Dmmlf.occupancy=off}): the primary's map records nothing of other runtimes. */
    private static final boolean ENABLED=!"off".equalsIgnoreCase(System.getProperty("mmlf.occupancy",System.getenv().getOrDefault("MMLF_OCCUPANCY","on")));

    private static String chunkKey(String dimension,long chunk) { return dimension+"@"+chunk; }
    /** Marks a chunk of the primary's map to be checked against what was reported for it. */
    static void check(String dimension,long chunk) { if(ENABLED && BridgeCore.isPrimary()) dirty.add(chunkKey(dimension,chunk)); }

    // ---- reports (network thread) ----

    private interface Change { void apply(Long2ObjectOpenHashMap<Entry> map); }
    private static synchronized void change(String dimension,long chunk,Change change) {
        var chunks=reported.computeIfAbsent(dimension,d->new ConcurrentHashMap<>());
        var before=chunks.get(chunk);
        var next=before==null ? new Long2ObjectOpenHashMap<Entry>() : new Long2ObjectOpenHashMap<>(before);
        change.apply(next);
        if(next.isEmpty()) chunks.remove(chunk); else { next.trim(); chunks.put(chunk,next); }
        check(dimension,chunk);
    }

    /** Another runtime's object appeared, changed or went away. */
    static void receive(ObjectState object) {
        if(!ENABLED || !BridgeCore.isPrimary() || object==null || object.owner()==null || object.owner().equals(BridgeCore.runtimeId())) return;
        boolean gone=object.type()==null || object.type().equals("minecraft:air");
        Boundary.At previous=positions.get(object.id());
        if(gone) { if(previous!=null) { positions.remove(object.id()); set(previous,object.id(),null); } return; }
        BlockState standIn=standIn(object.state()); if(standIn==null) return;
        var at=new Boundary.At(object.dimension(),new BlockPos(object.x(),object.y(),object.z()));
        if(previous!=null && !previous.equals(at)) set(previous,object.id(),null);
        positions.put(object.id(),at);
        set(at,object.id(),new Entry(object.id(),object.owner(),standIn,collision(object.state().get("_collision"))));
    }
    private static void set(Boundary.At at,String id,Entry entry) {
        long key=at.pos().asLong();
        change(at.dimension(),ChunkPos.asLong(at.pos().getX()>>4,at.pos().getZ()>>4),map->{
            Entry before=map.get(key);
            if(entry==null) { if(before!=null && id.equals(before.id())) { map.remove(key); release(at.dimension(),key); } }
            else { map.put(key,entry); var r=released.get(at.dimension()); if(r!=null) r.remove(key); }
        });
    }
    private static long plainReceived;
    /** Another runtime's plain blocks of one chunk, replacing what it reported there before. */
    static void receive(ChunkBlocks blocks) {
        if(!ENABLED || !BridgeCore.isPrimary() || blocks==null || blocks.owner()==null || blocks.owner().equals(BridgeCore.runtimeId())) return;
        if(blocks.positions()==null || (blocks.kinds()==null ? 0 : blocks.kinds().size())!=blocks.positions().length) return;
        Long2ObjectOpenHashMap<Entry> incoming=new Long2ObjectOpenHashMap<>();
        for(int i=0;i<blocks.positions().length;i++) {
            var kind=blocks.kinds().get(i);
            BlockState standIn=kind==null ? null : standIn(kind.state());
            if(standIn==null) continue;
            Entry entry=new Entry(null,blocks.owner(),standIn,collision(kind.state().get("_collision")));
            for(int packed:blocks.positions()[i])
                incoming.put(BlockPos.asLong(Messages.unpackX(blocks.x(),packed),Messages.unpackY(packed),Messages.unpackZ(blocks.z(),packed)),entry);
        }
        plainReceived+=incoming.size();
        long chunk=ChunkPos.asLong(blocks.x(),blocks.z());
        reportedChunks.add(chunkKey(blocks.dimension(),chunk));
        change(blocks.dimension(),chunk,map->{
            // The owner's earlier plain blocks of the chunk go, its objects stay; the ones no longer there were removed by it.
            map.long2ObjectEntrySet().removeIf(e->{
                if(e.getValue().id()!=null || !e.getValue().owner().equals(blocks.owner())) return false;
                if(!incoming.containsKey(e.getLongKey())) release(blocks.dimension(),e.getLongKey());
                return true;
            });
            var r=released.get(blocks.dimension());
            for(var e:incoming.long2ObjectEntrySet()) {
                Entry old=map.get(e.getLongKey()); if(old==null || old.id()==null) map.put(e.getLongKey(),e.getValue());
                if(r!=null) r.remove(e.getLongKey());
            }
        });
    }
    /** What the reported block's stand-in carries, from its reported properties ({@code Boundary.properties}). */
    private static BlockState standIn(Map<String,String> state) {
        BlockState standIn=StandInBlock.of(new StandInSpec(!"partial".equals(state.get("_occlusion")),parseInt(state.get("_light")),
            state.getOrDefault("_fluid",StandInSpec.NONE),"true".equals(state.get("_conductor"))));
        if(standIn==null && !unregisteredLogged) { unregisteredLogged=true; MmlfLog.warn("occupancy","stand_in_unregistered",Map.of()); }
        return standIn;
    }

    /**
     * A runtime left ({@code owner}) or the link dropped ({@code null}): what it reported is forgotten. Its stand-ins
     * stay in the map, as its blocks stay in its own world.
     */
    static void forget(String owner) {
        positions.entrySet().removeIf(e->{
            var chunks=reported.get(e.getValue().dimension());
            var map=chunks==null ? null : chunks.get(ChunkPos.asLong(e.getValue().pos().getX()>>4,e.getValue().pos().getZ()>>4));
            Entry entry=map==null ? null : map.get(e.getValue().pos().asLong());
            return owner==null || entry==null || owner.equals(entry.owner());
        });
        synchronized(Occupancy.class) {
            for(var chunks:reported.values()) for(var it=chunks.entrySet().iterator();it.hasNext();) {
                var entry=it.next();
                if(owner==null) { it.remove(); continue; }
                var next=new Long2ObjectOpenHashMap<>(entry.getValue());
                next.long2ObjectEntrySet().removeIf(e->owner.equals(e.getValue().owner()));
                if(next.isEmpty()) it.remove(); else entry.setValue(next);
            }
        }
        // One other runtime at most: with it gone, no chunk is reported.
        reportedChunks.clear(); dirty.clear(); released.clear();
    }

    // ---- checking the map (primary, server thread) ----

    /** Server thread, inside a chunk's promotion or unload: a loaded chunk is checked. */
    static void chunk(LevelChunk chunk,boolean loaded) {
        if(loaded && chunk.getLevel() instanceof ServerLevel level) check(level.dimension().location().toString(),chunk.getPos().toLong());
    }

    private static int ticks;
    private static long placed, updated, reverted, cleared;
    /** Primary, server thread, every tick: checks marked chunks for a few milliseconds; settling ones again every second. */
    static void tick(MinecraftServer server) {
        if(!ENABLED || !BridgeCore.isPrimary()) return;
        if(++ticks%20==0) for(var entry:settling.entrySet()) for(long pos:entry.getValue().keySet())
            dirty.add(chunkKey(entry.getKey(),ChunkPos.asLong(BlockPos.getX(pos)>>4,BlockPos.getZ(pos)>>4)));
        if(dirty.isEmpty()) return;
        long started=System.nanoTime();
        for(var it=dirty.iterator();it.hasNext() && System.nanoTime()-started<TICK_BUDGET_NANOS;) {
            String key=it.next(); it.remove();
            int at=key.lastIndexOf('@');
            ServerLevel level=RedstoneBridge.level(server,key.substring(0,at)); if(level==null) continue;
            long chunk=Long.parseLong(key.substring(at+1));
            LevelChunk loaded=level.getChunkSource().getChunkNow(ChunkPos.getX(chunk),ChunkPos.getZ(chunk));
            if(loaded==null) continue; // checked again when it loads
            reconcile(level,loaded);
        }
    }

    /** One chunk of the primary's map against what was reported for it. */
    private static void reconcile(ServerLevel level,LevelChunk chunk) {
        String dimension=level.dimension().location().toString(); ChunkPos cp=chunk.getPos();
        var chunks=reported.get(dimension);
        var entries=chunks==null ? null : chunks.get(cp.toLong());
        boolean chunkReported=reportedChunks.contains(chunkKey(dimension,cp.toLong()));
        boolean waiting=CoGeneration.instructionsWaiting(dimension,cp.x,cp.z);
        Set<Long> releasedHere=released.getOrDefault(dimension,Set.of());
        var settlingHere=settling.computeIfAbsent(dimension,d->new HashMap<>());
        long now=System.nanoTime();
        Map<String,List<Long>> reverts=new HashMap<>();
        LongOpenHashSet stillSettling=new LongOpenHashSet();
        if(entries!=null) for(var e:entries.long2ObjectEntrySet()) {
            long key=e.getLongKey(); Entry entry=e.getValue(); BlockPos pos=BlockPos.of(key);
            BlockState current=raw(chunk,pos);
            var kind=StandInBlock.is(current) ? OwnershipRules.Current.STAND_IN
                : current.isAir() || current.canBeReplaced() ? OwnershipRules.Current.REPLACEABLE : OwnershipRules.Current.OCCUPIED;
            var action=OwnershipRules.decide(true,kind,current==entry.state(),chunkReported,waiting,age(settlingHere,key,now),GRACE_NANOS);
            switch(action) {
                case PLACE -> { set(level,pos,entry.state()); placed++; }
                case UPDATE -> { set(level,pos,entry.state()); updated++; }
                case WAIT -> stillSettling.add(key);
                case REVERT -> { reverts.computeIfAbsent(entry.owner(),o->new ArrayList<>()).add(key); reverted++;
                    MmlfLog.sampled("occupancy","conflict_reverted",2000,Map.of("position",pos.toShortString(),"primary",current.toString(),"owner",entry.owner())); }
                default -> { }
            }
        }
        // Stand-ins nothing reports any more.
        var sections=chunk.getSections();
        for(int i=0;i<sections.length;i++) {
            var section=sections[i];
            if(section.hasOnlyAir() || !section.maybeHas(StandInBlock::is)) continue;
            int baseY=chunk.getSectionYFromSectionIndex(i)<<4;
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                if(!StandInBlock.is(section.getBlockState(x,y,z))) continue;
                long key=BlockPos.asLong(cp.getMinBlockX()+x,baseY+y,cp.getMinBlockZ()+z);
                if(entries!=null && entries.containsKey(key)) continue;
                var action=OwnershipRules.decide(false,OwnershipRules.Current.STAND_IN,false,chunkReported,waiting,age(settlingHere,key,now),GRACE_NANOS,releasedHere.contains(key));
                if(action==OwnershipRules.Action.WAIT) stillSettling.add(key);
                else if(action==OwnershipRules.Action.CLEAR) { set(level,BlockPos.of(key),Blocks.AIR.defaultBlockState()); cleared++; }
            }
        }
        // Releases in this chunk are acted on (or the position is not a stand-in any more).
        if(!releasedHere.isEmpty()) releasedHere.removeIf(key->(BlockPos.getX(key)>>4)==cp.x && (BlockPos.getZ(key)>>4)==cp.z);
        // Mismatches of this chunk that are resolved or acted on are no longer settling.
        settlingHere.keySet().removeIf(key->(BlockPos.getX(key)>>4)==cp.x && (BlockPos.getZ(key)>>4)==cp.z && !stillSettling.contains(key));
        for(long key:stillSettling) settlingHere.putIfAbsent(key,now);
        reverts.forEach((owner,list)->CoGeneration.revertOwnerBlocks(dimension,list.stream().mapToLong(Long::longValue).toArray()));
        MmlfLog.sampled("occupancy","reconciled",5000,Map.of("placed",placed,"updated",updated,"reverted",reverted,"cleared",cleared,
            "settling",settlingHere.size(),"lastChunk",cp.x+","+cp.z,"reportedBlocks",plainReceived));
    }
    private static long age(Map<Long,Long> settlingHere,long key,long now) { Long since=settlingHere.get(key); return since==null ? -1 : now-since; }
    /** As vanilla places and removes blocks, but no drops: what a stand-in replaces is the owner's to keep, not an item. */
    private static void set(ServerLevel level,BlockPos pos,BlockState state) { level.setBlock(pos,state,Block.UPDATE_ALL|Block.UPDATE_SUPPRESS_DROPS); }
    /** What the chunk's own storage holds. */
    private static BlockState raw(LevelChunk chunk,BlockPos pos) {
        int index=chunk.getSectionIndex(pos.getY());
        if(index<0 || index>=chunk.getSections().length) return Blocks.VOID_AIR.defaultBlockState();
        return chunk.getSection(index).getBlockState(pos.getX()&15,pos.getY()&15,pos.getZ()&15);
    }

    // ---- collision ----

    /** {@link StandInBlock}: the owner's collision boxes at the position, or null when unknown. */
    static VoxelShape collision(BlockGetter getter,BlockPos pos) {
        if(reported.isEmpty()) return null;
        long key=pos.asLong(); long chunk=ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4);
        if(getter instanceof Level level) {
            var chunks=reported.get(level.dimension().location().toString()); var states=chunks==null ? null : chunks.get(chunk);
            Entry entry=states==null ? null : states.get(key);
            return entry==null ? null : entry.collision();
        }
        // Region views (path finding, section copies) do not name their dimension.
        for(var chunks:reported.values()) { var states=chunks.get(chunk); Entry entry=states==null ? null : states.get(key); if(entry!=null) return entry.collision(); }
        return null;
    }
    /** Parsed collision shapes, by their encoding: a chunk's plain blocks share a handful. */
    private static final Map<String,VoxelShape> shapes=new ConcurrentHashMap<>();
    private static VoxelShape collision(String encoded) {
        if(encoded==null) return null;
        VoxelShape known=shapes.get(encoded); if(known!=null) return known;
        try {
            double[][] boxes=Wire.JSON.fromJson(encoded,double[][].class);
            VoxelShape shape=Shapes.empty();
            for(double[] b:boxes) {
                if(b==null || b.length!=6 || b[3]<=b[0] || b[4]<=b[1] || b[5]<=b[2]) continue;
                shape=Shapes.or(shape,Shapes.box(clamp(b[0]),clamp(b[1]),clamp(b[2]),clamp(b[3]),clamp(b[4]),clamp(b[5])));
            }
            shape=shape.optimize();
            if(shapes.size()<4096) shapes.put(encoded,shape);
            return shape;
        } catch(RuntimeException e) { return null; }
    }
    /** Collision boxes may reach past their block (fences); one block either way is the vanilla limit. */
    private static double clamp(double v) { return Double.isFinite(v) ? Math.max(-1,Math.min(2,v)) : 0; }
    private static int parseInt(String value) { try { return value==null ? 0 : Integer.parseInt(value); } catch(NumberFormatException e) { return 0; } }
}
