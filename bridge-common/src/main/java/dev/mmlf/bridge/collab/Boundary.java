package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.platform.ItemAccess;
import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.BlockKind;
import dev.mmlf.common.Messages.ChunkBlocks;
import dev.mmlf.common.Messages.ObjectState;
import dev.mmlf.common.StandInSpec;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The blocks this runtime owns in its loaded chunks, and those other runtimes own. Driven by the server's own block
 * changes and chunk loads, never by scanning around the player, so an object stays known wherever its chunk is loaded.
 * <p>
 * Two kinds, as vanilla keeps terrain and block entities apart:
 * <ul>
 * <li><b>Objects</b>: blocks with a block entity or redstone behaviour (containers, machines, lamps), one
 * {@link ObjectState} each, with an identity interactions and signals refer to.</li>
 * <li><b>Plain blocks</b> ({@link #plain}: ores, plants, building blocks): per chunk, all at once, as one
 * {@link ChunkBlocks}; a change in the chunk sends the chunk's again. Generation places hundreds per chunk, which as
 * objects flooded the link and the primary.</li>
 * </ul>
 * Changes are only recorded where they happen (inside {@code setBlockState} and while a chunk is being promoted, where
 * querying the level could wait for that very chunk) and published on the next server tick. Mutated on the server
 * thread only; read from the render thread (ray tests, interactions) through concurrent maps.
 */
public final class Boundary {
    private Boundary() {}
    /** This world's part of Boundary: made when the world first needs it, gone when it closes ({@link Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        final Map<At,ObjectState> local=new ConcurrentHashMap<>();
        public final Map<At,ObjectState> remote=new ConcurrentHashMap<>();
        final Map<String,At> remoteIds=new ConcurrentHashMap<>();
        final Map<At,ServerLevel> dirty=new LinkedHashMap<>();
        final List<LevelChunk> loadedChunks=new ArrayList<>();
        final Map<String,Published> plainChunks=new ConcurrentHashMap<>();
        final Map<String,ServerLevel> dirtyChunks=new LinkedHashMap<>();
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("boundary",State::new);
    /** The open world's state; with none open, a throwaway one: what is written to it reaches no world. */
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }

    /** Where a block is: dimension and position. */
    public record At(String dimension,BlockPos pos) {
        static At of(ServerLevel level,BlockPos pos) { return new At(level.dimension().location().toString(),pos.immutable()); }
    }
    /** Positions changed since the last tick, and chunks loaded since, with the level they belong to. */
    /** This runtime's plain blocks as last published, per chunk ("dimension@x,z"), and chunks to publish again. */
    private record Published(ChunkBlocks blocks,LongSet positions) {}
    /** Unique per placement for this process; seeded by time so a restarted runtime never reuses an id. */
    private static final AtomicLong generations=new AtomicLong(System.currentTimeMillis()*1000);
    /** Object property naming a block that holds items another runtime may insert into or take from. */
    static final String CONTAINER="_container";

    static ObjectState local(String dimension,BlockPos pos) { return state().local.get(new At(dimension,pos)); }
    static Collection<ObjectState> localObjects() { return state().local.values(); }
    static boolean remoteAt(String dimension,BlockPos pos) { return state().remote.containsKey(new At(dimension,pos)); }
    public static ObjectState remote(String dimension,BlockPos pos) { return state().remote.get(new At(dimension,pos)); }
    public static Collection<ObjectState> remoteObjects() { return state().remote.values(); }
    /** Whether one of this runtime's published plain blocks is at the position (a ray hit or interaction on it). */
    static boolean localPlain(String dimension,BlockPos pos) {
        Published published=state().plainChunks.get(chunkKey(dimension,pos.getX()>>4,pos.getZ()>>4));
        return published!=null && published.positions().contains(pos.asLong());
    }

    /** Whether this runtime owns what is at the position: its mod blocks, and the vanilla blocks it placed itself. */
    static boolean owns(ServerLevel level,BlockState state,BlockPos pos) {
        if(state.isAir() || StandInBlock.is(state)) return false;
        var key=BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key!=null && (!key.getNamespace().equals("minecraft") || Ownership.ownsVanilla(level,pos));
    }
    static boolean vanilla(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft"); }
    /** Published with its chunk rather than as an object: nothing refers to it but its position. */
    static boolean plain(BlockState state) { return !state.hasBlockEntity() && !state.isSignalSource() && !state.hasAnalogOutputSignal(); }
    private static String chunkKey(String dimension,int x,int z) { return dimension+"@"+x+","+z; }
    private static String chunkKey(ServerLevel level,BlockPos pos) { return chunkKey(level.dimension().location().toString(),pos.getX()>>4,pos.getZ()>>4); }

    /** Server thread, inside {@code LevelChunk.setBlockState}: remembered, published next tick. */
    public static void changed(ServerLevel level,BlockPos pos,BlockState state) {
        At at=At.of(level,pos);
        boolean owned=owns(level,state,pos);
        if(owned) { NeighborMirror.drop(level,pos); Ownership.placedOwn(level,pos); }
        if(owned && !plain(state) || state().local.containsKey(at)) state().dirty.put(at,level);
        if(owned && plain(state) || state().plainChunks.containsKey(chunkKey(level,pos))) state().dirtyChunks.put(chunkKey(level,pos),level);
    }
    /** Server thread, inside the chunk's promotion or unload. */
    public static void chunk(LevelChunk chunk,boolean loaded) {
        if(!(chunk.getLevel() instanceof ServerLevel level)) return;
        NeighborMirror.chunk(chunk,loaded);
        Occupancy.chunk(chunk,loaded);
        if(loaded) { state().loadedChunks.add(chunk); return; }
        state().loadedChunks.remove(chunk);
        leave(level,chunk.getPos().x,chunk.getPos().z);
    }
    /** The chunk's objects and plain blocks leave the boundary. */
    private static void leave(ServerLevel level,int chunkX,int chunkZ) {
        String dimension=level.dimension().location().toString();
        for(var entry:new ArrayList<>(state().local.entrySet())) {
            At at=entry.getKey();
            if(at.dimension().equals(dimension) && (at.pos().getX()>>4)==chunkX && (at.pos().getZ()>>4)==chunkZ) { state().dirty.remove(at); remove(at,entry.getValue()); }
        }
        String key=chunkKey(dimension,chunkX,chunkZ);
        state().dirtyChunks.remove(key);
        if(state().plainChunks.remove(key)!=null) SessionLink.send("blocks","*",new ChunkBlocks(SessionLink.runtimeId(),dimension,chunkX,chunkZ,List.of(),new int[0][]));
    }
    /** Server thread: a vanilla block this runtime placed for its own scene or through a native use is now its own. */
    static void claim(ServerLevel level,BlockPos pos) { state().dirty.put(At.of(level,pos),level); state().dirtyChunks.put(chunkKey(level,pos),level); }

    /** Server thread, once a tick: indexes chunks loaded since the last tick and publishes what changed. */
    static void tick(MinecraftServer server) {
        if(!state().loadedChunks.isEmpty()) {
            List<LevelChunk> chunks=new ArrayList<>(state().loadedChunks); state().loadedChunks.clear();
            for(LevelChunk chunk:chunks) scan(chunk);
        }
        if(!state().dirty.isEmpty()) {
            var changes=new ArrayList<>(state().dirty.entrySet()); state().dirty.clear();
            for(var entry:changes) {
                At at=entry.getKey(); ServerLevel level=entry.getValue();
                if(!level.isLoaded(at.pos())) continue;
                BlockState state=level.getChunkAt(at.pos()).getBlockState(at.pos());
                ObjectState old=state().local.get(at);
                boolean owned=owns(level,state,at.pos());
                if(owned) ChunkFollow.own(at.dimension(),at.pos().getX()>>4,at.pos().getZ()>>4,true);
                // A chunk the primary does not have loaded keeps its objects out of the boundary (ChunkFollow).
                if(owned && !plain(state) && !ChunkFollow.dormant(at.dimension(),at.pos().getX()>>4,at.pos().getZ()>>4)) publish(level,at,state,old,true); else if(old!=null) remove(at,old);
            }
        }
        if(!state().dirtyChunks.isEmpty()) {
            var chunks=new ArrayList<>(state().dirtyChunks.entrySet()); state().dirtyChunks.clear();
            for(var entry:chunks) {
                var at=parseChunk(entry.getKey()); if(at==null) continue;
                LevelChunk chunk=entry.getValue().getChunkSource().getChunkNow(at[0],at[1]);
                if(chunk!=null) scan(chunk);
            }
        }
    }
    private static int[] parseChunk(String key) {
        int at=key.lastIndexOf('@'), comma=key.lastIndexOf(',');
        try { return new int[]{Integer.parseInt(key.substring(at+1,comma)),Integer.parseInt(key.substring(comma+1))}; }
        catch(RuntimeException e) { return null; }
    }
    /** The chunk's own blocks: objects published one by one, plain blocks together. */
    private static void scan(LevelChunk chunk) {
        if(!(chunk.getLevel() instanceof ServerLevel level)) return;
        var chunkPos=chunk.getPos(); var sections=chunk.getSections();
        String dimension=level.dimension().location().toString();
        boolean dormant=ChunkFollow.dormant(dimension,chunkPos.x,chunkPos.z);
        int owned=0;
        Map<BlockState,List<BlockPos>> plainBlocks=new LinkedHashMap<>();
        for(int index=0;index<sections.length;index++) {
            var section=sections[index];
            // Only sections whose palette may hold something other than vanilla blocks are walked block by block.
            if(section.hasOnlyAir() || !section.maybeHas(s->!s.isAir() && !vanilla(s))) continue;
            int baseY=chunk.getSectionYFromSectionIndex(index)<<4;
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                BlockState state=section.getBlockState(x,y,z);
                if(state.isAir() || vanilla(state)) continue;
                BlockPos pos=new BlockPos(chunkPos.getMinBlockX()+x,baseY+y,chunkPos.getMinBlockZ()+z);
                if(!owns(level,state,pos)) continue;
                owned++;
                if(dormant) continue;
                if(plain(state)) plainBlocks.computeIfAbsent(state,s->new ArrayList<>()).add(pos);
                else publish(level,At.of(level,pos),state,state().local.get(At.of(level,pos)),false);
            }
        }
        // Vanilla blocks this runtime placed itself are few and known by position.
        for(BlockPos pos:Ownership.ownedVanillaPositions(level)) if((pos.getX()>>4)==chunkPos.x && (pos.getZ()>>4)==chunkPos.z) {
            BlockState state=chunk.getBlockState(pos); At at=At.of(level,pos);
            if(!owns(level,state,pos)) continue;
            owned++;
            if(dormant) continue;
            if(plain(state)) plainBlocks.computeIfAbsent(state,s->new ArrayList<>()).add(pos.immutable());
            else publish(level,at,state,state().local.get(at),false);
        }
        ChunkFollow.own(dimension,chunkPos.x,chunkPos.z,owned>0);
        if(!dormant) publishPlain(level,dimension,chunkPos.x,chunkPos.z,plainBlocks);
    }
    private static long plainPublished, plainSent, plainRevision;
    private static void publishPlain(ServerLevel level,String dimension,int chunkX,int chunkZ,Map<BlockState,List<BlockPos>> blocks) {
        String key=chunkKey(dimension,chunkX,chunkZ);
        Published before=state().plainChunks.get(key);
        if(blocks.isEmpty()) {
            if(before!=null) { state().plainChunks.remove(key); SessionLink.send("blocks","*",new ChunkBlocks(SessionLink.runtimeId(),dimension,chunkX,chunkZ,List.of(),new int[0][])); }
            return;
        }
        List<BlockKind> kinds=new ArrayList<>(); int[][] positions=new int[blocks.size()][]; LongSet all=new LongOpenHashSet();
        int i=0;
        for(var entry:blocks.entrySet()) {
            BlockState state=entry.getKey(); List<BlockPos> at=entry.getValue();
            kinds.add(new BlockKind(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),properties(level,state,at.get(0),false)));
            int[] packed=new int[at.size()];
            for(int j=0;j<packed.length;j++) { BlockPos pos=at.get(j); packed[j]=Messages.packInChunk(pos.getX(),pos.getY(),pos.getZ()); all.add(pos.asLong()); }
            Arrays.sort(packed);
            positions[i++]=packed;
        }
        // Unchanged: nothing is sent and the revision stays, so a claim against the last report still matches it.
        if(before!=null && before.blocks().kinds().equals(kinds) && Arrays.deepEquals(before.blocks().positions(),positions)) return;
        var next=new ChunkBlocks(SessionLink.runtimeId(),dimension,chunkX,chunkZ,kinds,positions,++plainRevision);
        state().plainChunks.put(key,new Published(next,all));
        dev.mmlf.bridge.platform.FramePacing.activity();
        SessionLink.send("blocks","*",next);
        plainPublished+=all.size(); plainSent++;
        MmlfLog.sampled("world","plain_blocks_published",5000,Map.of("chunks",state().plainChunks.size(),"messages",plainSent,"blocks",plainPublished,"last",key,"lastBlocks",all.size()));
    }

    /** Server thread ({@link ChunkFollow}): the primary does not have this chunk loaded, so its objects leave the boundary now. */
    static void dormant(ServerLevel level,int chunkX,int chunkZ) { leave(level,chunkX,chunkZ); }
    /** Server thread ({@link ChunkFollow}): the primary has the chunk again; if it is still loaded here it is indexed anew. */
    static void awaken(ServerLevel level,int chunkX,int chunkZ) {
        LevelChunk chunk=level.getChunkSource().getChunkNow(chunkX,chunkZ);
        if(chunk!=null) state().loadedChunks.add(chunk);
    }

    /** What the primary's stand-in for this block carries ({@link StandInBlock}): occlusion, light, held vanilla fluid, redstone conduction. */
    static StandInSpec standInSpec(net.minecraft.world.level.BlockGetter level,BlockState state,BlockPos pos) {
        var fluid=state.getFluidState();
        String held=!fluid.isSource() ? StandInSpec.NONE
            : fluid.is(net.minecraft.tags.FluidTags.WATER) ? StandInSpec.WATER : fluid.is(net.minecraft.tags.FluidTags.LAVA) ? StandInSpec.LAVA : StandInSpec.NONE;
        return new StandInSpec(state.isSolidRender(level,pos),state.getLightEmission(),held,state.isRedstoneConductor(level,pos));
    }
    /** What another runtime needs to know of the space the block takes ({@link Occupancy}): collision, light, occlusion, fluid, conduction. */
    private static Map<String,String> properties(ServerLevel level,BlockState state,BlockPos pos,boolean object) {
        Map<String,String> properties=new TreeMap<>();
        state.getValues().forEach((property,value)->properties.put(property.getName(),value.toString()));
        properties.put("_state",net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(state));
        var collision=state.getCollisionShape(level,pos).toAabbs().stream().map(b->new double[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ}).toList();
        properties.put("_collision",Wire.JSON.toJson(collision));
        var spec=standInSpec(level,state,pos);
        if(!spec.solid()) properties.put("_occlusion","partial");
        if(spec.light()>0) properties.put("_light",Integer.toString(spec.light()));
        if(!StandInSpec.NONE.equals(spec.fluid())) properties.put("_fluid",spec.fluid());
        if(spec.conductor()) properties.put("_conductor","true");
        StandInFeel.describe(state,properties);
        if(object && ItemAccess.has(level,pos)) properties.put(CONTAINER,"true");
        return properties;
    }
    private static void publish(ServerLevel level,At at,BlockState state,ObjectState old,boolean changed) {
        var key=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Map<String,String> properties=properties(level,state,at.pos(),true);
        if(!changed && old!=null && old.type().equals(key) && old.state().equals(properties)) return;
        String self=SessionLink.runtimeId(); var pos=at.pos();
        ObjectState next;
        if(old!=null && old.type().equals(key)) next=new ObjectState(old.id(),self,at.dimension(),pos.getX(),pos.getY(),pos.getZ(),key,old.revision()+1,properties,old.generation());
        else {
            if(old!=null) remove(at,old);
            long generation=generations.incrementAndGet();
            next=new ObjectState(Messages.objectId(self,at.dimension(),pos.getX(),pos.getY(),pos.getZ(),generation),self,at.dimension(),pos.getX(),pos.getY(),pos.getZ(),key,1,properties,generation);
        }
        state().local.put(at,next);
        dev.mmlf.bridge.platform.FramePacing.activity();
        SessionLink.send("object","*",next);
    }
    /** Checks a conflict against the block and report that actually caused it. Server thread only. */
    static boolean matchesClaim(ServerLevel level,BlockPos pos,String id,long revision) {
        At at=At.of(level,pos);
        if(id!=null) {
            ObjectState state=state().local.get(at);
            return !state().dirty.containsKey(at) && state!=null && id.equals(state.id()) && revision==state.revision();
        }
        String key=chunkKey(level,pos);
        Published published=state().plainChunks.get(key);
        return !state().dirtyChunks.containsKey(key) && published!=null && revision==published.blocks().revision()
            && published.positions().contains(pos.asLong());
    }

    private static void remove(At at,ObjectState old) {
        state().local.remove(at);
        SessionLink.send("object","*",new ObjectState(old.id(),old.owner(),old.dimension(),old.x(),old.y(),old.z(),"minecraft:air",old.revision()+1,Map.of(),old.generation()));
    }
    /** Server thread, after (re)registering: the Coordinator forgot this runtime's objects when it left, so all go out again. */
    public static void republish() {
        String self=SessionLink.runtimeId();
        for(var entry:state().local.entrySet()) {
            ObjectState o=entry.getValue();
            // Chunks loaded before this runtime joined its session were indexed under a placeholder owner.
            String id=o.owner().equals(self) ? o.id() : Messages.objectId(self,o.dimension(),o.x(),o.y(),o.z(),o.generation());
            ObjectState again=new ObjectState(id,self,o.dimension(),o.x(),o.y(),o.z(),o.type(),o.revision()+1,o.state(),o.generation());
            entry.setValue(again); SessionLink.send("object","*",again);
        }
        for(var entry:state().plainChunks.entrySet()) {
            ChunkBlocks b=entry.getValue().blocks();
            ChunkBlocks again=new ChunkBlocks(self,b.dimension(),b.x(),b.z(),b.kinds(),b.positions(),++plainRevision);
            entry.setValue(new Published(again,entry.getValue().positions()));
            SessionLink.send("blocks","*",again);
        }
    }

    /** Network thread: another runtime's object appeared, changed or went away. */
    public static void remoteChanged(ObjectState object) {
        if(object.type().equals("minecraft:air")) { At at=state().remoteIds.remove(object.id()); if(at!=null) state().remote.remove(at); return; }
        At at=new At(object.dimension(),new BlockPos(object.x(),object.y(),object.z()));
        At previous=state().remoteIds.put(object.id(),at);
        if(previous!=null && !previous.equals(at)) state().remote.remove(previous);
        state().remote.put(at,object);
    }
    public static void forgetRemote(String owner) {
        state().remote.values().removeIf(o->o.owner().equals(owner));
        state().remoteIds.keySet().retainAll(state().remote.values().stream().map(ObjectState::id).toList());
    }
    public static void forgetAllRemote() { state().remote.clear(); state().remoteIds.clear(); }
}
