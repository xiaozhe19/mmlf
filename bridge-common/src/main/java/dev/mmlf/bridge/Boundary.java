package dev.mmlf.bridge;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.ObjectState;
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
 *
 * Changes are only recorded where they happen (inside {@code setBlockState} and while a chunk is being promoted, where
 * querying the level could wait for that very chunk) and published on the next server tick. Mutated on the server
 * thread only; read from the render thread (ray tests, interactions) through concurrent maps.
 */
public final class Boundary {
    private Boundary() {}
    /** Where a block is: dimension and position. */
    public record At(String dimension,BlockPos pos) {
        static At of(ServerLevel level,BlockPos pos) { return new At(level.dimension().location().toString(),pos.immutable()); }
    }
    private static final Map<At,ObjectState> local=new ConcurrentHashMap<>();
    private static final Map<At,ObjectState> remote=new ConcurrentHashMap<>();
    private static final Map<String,At> remoteIds=new ConcurrentHashMap<>();
    /** Positions changed since the last tick, and chunks loaded since, with the level they belong to. */
    private static final Map<At,ServerLevel> dirty=new LinkedHashMap<>();
    private static final List<LevelChunk> loadedChunks=new ArrayList<>();
    /** Unique per placement for this process; seeded by time so a restarted runtime never reuses an id. */
    private static final AtomicLong generations=new AtomicLong(System.currentTimeMillis()*1000);
    /** Object property naming a block that holds items another runtime may insert into or take from. */
    static final String CONTAINER="_container";

    static ObjectState local(String dimension,BlockPos pos) { return local.get(new At(dimension,pos)); }
    static Collection<ObjectState> localObjects() { return local.values(); }
    static boolean remoteAt(String dimension,BlockPos pos) { return remote.containsKey(new At(dimension,pos)); }
    static ObjectState remote(String dimension,BlockPos pos) { return remote.get(new At(dimension,pos)); }
    static Collection<ObjectState> remoteObjects() { return remote.values(); }

    /** Whether this runtime owns what is at the position: its mod blocks, and the vanilla blocks it placed itself. */
    static boolean owns(BlockState state,BlockPos pos) {
        if(state.isAir() || StandInBlock.is(state)) return false;
        var key=BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return key!=null && (!key.getNamespace().equals("minecraft") || BridgeCore.ownsVanilla(pos));
    }
    static boolean vanilla(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft"); }

    /** Server thread, inside {@code LevelChunk.setBlockState}: remembered, published next tick. */
    public static void changed(ServerLevel level,BlockPos pos,BlockState state) {
        At at=At.of(level,pos);
        if(owns(state,pos)) { NeighborMirror.drop(level,pos); dirty.put(at,level); }
        else if(local.containsKey(at)) dirty.put(at,level);
    }
    /** Server thread, inside the chunk's promotion or unload. */
    public static void chunk(LevelChunk chunk,boolean loaded) {
        if(!(chunk.getLevel() instanceof ServerLevel level)) return;
        NeighborMirror.chunk(chunk,loaded);
        Occupancy.chunk(chunk,loaded);
        if(loaded) { loadedChunks.add(chunk); return; }
        String dimension=level.dimension().location().toString(); var chunkPos=chunk.getPos();
        loadedChunks.remove(chunk);
        for(var entry:new ArrayList<>(local.entrySet())) {
            At at=entry.getKey();
            if(at.dimension().equals(dimension) && (at.pos().getX()>>4)==chunkPos.x && (at.pos().getZ()>>4)==chunkPos.z) { dirty.remove(at); remove(at,entry.getValue()); }
        }
    }
    /** Server thread: a vanilla block this runtime placed for its own scene or through a native use is now its own. */
    static void claim(ServerLevel level,BlockPos pos) { dirty.put(At.of(level,pos),level); }

    /** Server thread, once a tick: indexes chunks loaded since the last tick and publishes what changed. */
    static void tick(MinecraftServer server) {
        if(!loadedChunks.isEmpty()) {
            List<LevelChunk> chunks=new ArrayList<>(loadedChunks); loadedChunks.clear();
            for(LevelChunk chunk:chunks) scan(chunk);
        }
        if(dirty.isEmpty()) return;
        var changes=new ArrayList<>(dirty.entrySet()); dirty.clear();
        for(var entry:changes) {
            At at=entry.getKey(); ServerLevel level=entry.getValue();
            if(!level.isLoaded(at.pos())) continue;
            BlockState state=level.getChunkAt(at.pos()).getBlockState(at.pos());
            ObjectState old=local.get(at);
            boolean owned=owns(state,at.pos());
            if(owned) ChunkFollow.own(at.dimension(),at.pos().getX()>>4,at.pos().getZ()>>4,true);
            // A chunk the primary does not have loaded keeps its objects out of the boundary (ChunkFollow).
            if(owned && !ChunkFollow.dormant(at.dimension(),at.pos().getX()>>4,at.pos().getZ()>>4)) publish(level,at,state,old); else if(old!=null) remove(at,old);
        }
    }
    private static void scan(LevelChunk chunk) {
        if(!(chunk.getLevel() instanceof ServerLevel level)) return;
        var chunkPos=chunk.getPos(); var sections=chunk.getSections();
        String dimension=level.dimension().location().toString();
        boolean dormant=ChunkFollow.dormant(dimension,chunkPos.x,chunkPos.z);
        int owned=0;
        for(int index=0;index<sections.length;index++) {
            var section=sections[index];
            // Only sections whose palette may hold something other than vanilla blocks are walked block by block.
            if(section.hasOnlyAir() || !section.maybeHas(s->!s.isAir() && !vanilla(s))) continue;
            int baseY=chunk.getSectionYFromSectionIndex(index)<<4;
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                BlockState state=section.getBlockState(x,y,z);
                BlockPos pos=new BlockPos(chunkPos.getMinBlockX()+x,baseY+y,chunkPos.getMinBlockZ()+z);
                if(owns(state,pos)) { owned++; if(!dormant) publish(level,At.of(level,pos),state,local.get(At.of(level,pos))); }
            }
        }
        // Vanilla blocks this runtime placed itself are few and known by position.
        for(BlockPos pos:BridgeCore.ownedVanilla()) if((pos.getX()>>4)==chunkPos.x && (pos.getZ()>>4)==chunkPos.z) {
            BlockState state=chunk.getBlockState(pos); At at=At.of(level,pos);
            if(owns(state,pos)) { owned++; if(!dormant) publish(level,at,state,local.get(at)); }
        }
        ChunkFollow.own(dimension,chunkPos.x,chunkPos.z,owned>0);
    }

    /** Server thread ({@link ChunkFollow}): the primary does not have this chunk loaded, so its objects leave the boundary now. */
    static void dormant(ServerLevel level,int chunkX,int chunkZ) {
        String dimension=level.dimension().location().toString();
        for(var entry:new ArrayList<>(local.entrySet())) {
            At at=entry.getKey();
            if(at.dimension().equals(dimension) && (at.pos().getX()>>4)==chunkX && (at.pos().getZ()>>4)==chunkZ) { dirty.remove(at); remove(at,entry.getValue()); }
        }
    }
    /** Server thread ({@link ChunkFollow}): the primary has the chunk again; if it is still loaded here it is indexed anew. */
    static void awaken(ServerLevel level,int chunkX,int chunkZ) {
        LevelChunk chunk=level.getChunkSource().getChunkNow(chunkX,chunkZ);
        if(chunk!=null) loadedChunks.add(chunk);
    }

    private static void publish(ServerLevel level,At at,BlockState state,ObjectState old) {
        var key=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Map<String,String> properties=new TreeMap<>();
        state.getValues().forEach((property,value)->properties.put(property.getName(),value.toString()));
        var collision=state.getCollisionShape(level,at.pos()).toAabbs().stream().map(b->new double[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ}).toList();
        properties.put("_collision",Wire.JSON.toJson(collision));
        // What the primary's world needs to know of the space it takes (Occupancy): light and occlusion.
        if(!state.isSolidRender(level,at.pos())) properties.put("_occlusion","partial");
        if(state.getLightEmission()>0) properties.put("_light",Integer.toString(state.getLightEmission()));
        if(ItemAccess.has(level,at.pos())) properties.put(CONTAINER,"true");
        if(old!=null && old.type().equals(key) && old.state().equals(properties)) return;
        String self=BridgeCore.runtimeId(); var pos=at.pos();
        ObjectState next;
        if(old!=null && old.type().equals(key)) next=new ObjectState(old.id(),self,at.dimension(),pos.getX(),pos.getY(),pos.getZ(),key,old.revision()+1,properties,old.generation());
        else {
            if(old!=null) remove(at,old);
            long generation=generations.incrementAndGet();
            next=new ObjectState(Messages.objectId(self,at.dimension(),pos.getX(),pos.getY(),pos.getZ(),generation),self,at.dimension(),pos.getX(),pos.getY(),pos.getZ(),key,1,properties,generation);
        }
        local.put(at,next);
        BridgeCore.localObjectChanged();
        BridgeCore.send("object","*",next);
    }
    private static void remove(At at,ObjectState old) {
        local.remove(at);
        BridgeCore.send("object","*",new ObjectState(old.id(),old.owner(),old.dimension(),old.x(),old.y(),old.z(),"minecraft:air",old.revision()+1,Map.of(),old.generation()));
    }
    /** Server thread, after (re)registering: the Coordinator forgot this runtime's objects when it left, so all go out again. */
    static void republish() {
        String self=BridgeCore.runtimeId();
        for(var entry:local.entrySet()) {
            ObjectState o=entry.getValue();
            // Chunks loaded before this runtime joined its session were indexed under a placeholder owner.
            String id=o.owner().equals(self) ? o.id() : Messages.objectId(self,o.dimension(),o.x(),o.y(),o.z(),o.generation());
            ObjectState again=new ObjectState(id,self,o.dimension(),o.x(),o.y(),o.z(),o.type(),o.revision()+1,o.state(),o.generation());
            entry.setValue(again); BridgeCore.send("object","*",again);
        }
    }

    /** Network thread: another runtime's object appeared, changed or went away. */
    static void remoteChanged(ObjectState object) {
        if(object.type().equals("minecraft:air")) { At at=remoteIds.remove(object.id()); if(at!=null) remote.remove(at); return; }
        At at=new At(object.dimension(),new BlockPos(object.x(),object.y(),object.z()));
        At previous=remoteIds.put(object.id(),at);
        if(previous!=null && !previous.equals(at)) remote.remove(previous);
        remote.put(at,object);
    }
    static void forgetRemote(String owner) {
        remote.values().removeIf(o->o.owner().equals(owner));
        remoteIds.keySet().retainAll(remote.values().stream().map(ObjectState::id).toList());
    }
    static void forgetAllRemote() { remote.clear(); remoteIds.clear(); }
}
