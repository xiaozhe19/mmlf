package dev.mmlf.bridge;

import dev.mmlf.common.InJvm;
import dev.mmlf.common.MmlfLog;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.LongToIntFunction;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One map for both runtimes. The primary holds the shared world; the other runtime does not keep a copy of it but reads
 * the primary's directly, in this JVM, through functions the primary publishes in {@link InJvm#registry()} (JDK types
 * only, so no object of one runtime reaches the other):
 * <ul>
 * <li>{@code block}: packed position to the primary's block state id there, or -1 where it has nothing to say (chunk not
 * loaded, or a stand-in for another runtime's block);</li>
 * <li>{@code name} and {@code shape}: a state id's full name ({@code minecraft:oak_stairs[facing=north,...]}) and its
 * collision boxes, asked once per id;</li>
 * <li>{@code light}: packed position to {@code block | sky << 4} light, or -1;</li>
 * <li>{@code dimension}: the dimension those answers are for;</li>
 * <li>{@code blockIn} and {@code covers}: the same block answers for any dimension (the client world for its own, the
 * held copies below for the others), and per chunk whether the primary can answer there at all;</li>
 * <li>{@code biomesIn}: a chunk's biomes in one dimension, by name, or null where the primary cannot answer.</li>
 * </ul>
 * The other runtime's chunk reads ({@code LevelChunkMixin}) answer its own blocks as before and the primary's map
 * everywhere else: vanilla states by name, blocks it does not have as a {@link StandInBlock} with the primary's
 * collision. Its light reads ({@code LevelLightMixin}) answer the primary's light. The primary reads its client world,
 * which other threads may read (the section compilers do); its server world belongs to its server thread.
 * <p>
 * Writes: the primary is the map's only writer. What the other runtime's own things change in the map (its entities,
 * its block entities and blocks, its copy of the player acting for the primary; {@link #begin}) goes to the primary
 * ({@code write}), which applies it on its server thread, usually within milliseconds. Until the primary's map shows it,
 * reads there answer the other runtime's own storage, so its logic sees what it just did. Writes outside those scopes
 * are that runtime simulating its own copy of the vanilla world; reads never show them and they go nowhere.
 * <p>
 * Range: the other runtime names the chunks its own things are in ({@code interest}); the primary keeps them loaded and,
 * where its client world does not have them, answers from a copy of those chunks kept by its server thread.
 * <p>
 * Hollow worlds ({@link #hollow}): a background that follows the primary's saves generates no terrain of its own; its
 * dimensions are the save's, but it computes nothing the primary has: its chunks hold only its own blocks, and their
 * biomes are copied from the primary's as each chunk loads ({@link #biomes}). Its things act only where the primary's
 * map answers ({@link #covered}); elsewhere they wait, as in an unloaded chunk, instead of falling into void.
 */
public final class SharedWorld {
    private SharedWorld() {}
    private static final boolean ENABLED=!"off".equalsIgnoreCase(System.getProperty("mmlf.sharedWorld",System.getenv().getOrDefault("MMLF_SHARED_WORLD","on")));
    /** Whether the other runtime reads the primary's map instead of its own world's terrain. */
    static boolean enabled() { return ENABLED; }

    private static volatile Object hollowFor;
    private static volatile boolean hollow;
    /**
     * Other runtime following the primary's saves ({@link WorldFollow}) with the shared world: its world generates no
     * terrain ({@code HollowGenerationMixin}). Demo scenes keep generating their own floor.
     */
    public static boolean hollow() {
        if(!ENABLED || BridgeCore.isPrimary()) return false;
        var session=BridgeCore.session(); if(session==null) return false;
        if(hollowFor!=session) { hollow=WorldFollow.enabled(session); hollowFor=session; }
        return hollow;
    }

    // ---- primary: publishing ----

    private static String key(String identity) { return "world:"+identity; }
    /** Primary, once it joined its session: publishes its map's read functions. */
    static void publish(String identity) {
        if(!ENABLED || identity==null) return;
        Map<String,Object> view=new HashMap<>();
        view.put("block",(LongToIntFunction)SharedWorld::primaryBlock);
        view.put("name",(IntFunction<String>)id->{ BlockState s=Block.BLOCK_STATE_REGISTRY.byId(id); return s==null?null:BlockStateParser.serialize(s); });
        view.put("shape",(IntFunction<double[]>)SharedWorld::primaryShape);
        view.put("emission",(IntFunction<Integer>)id->{ BlockState s=Block.BLOCK_STATE_REGISTRY.byId(id); return s==null?0:s.getLightEmission(); });
        view.put("light",(LongToIntFunction)SharedWorld::primaryLight);
        view.put("dimension",(Supplier<String>)()->{ var level=Minecraft.getInstance().level; return level==null?null:level.dimension().location().toString(); });
        view.put("blockIn",(Function<String,LongToIntFunction>)SharedWorld::primaryBlockIn);
        view.put("covers",(Function<String,LongPredicate>)SharedWorld::primaryCovers);
        view.put("biomesIn",(Function<String,LongFunction<String[]>>)SharedWorld::primaryBiomesIn);
        view.put("write",(java.util.function.Consumer<String[]>)SharedWorld::primaryWrite);
        view.put("ready",(Supplier<Boolean>)()->{ long since=readySince; return since!=0 && System.nanoTime()-since>=READY_NANOS; });
        InJvm.registry().put(key(identity),Map.copyOf(view));
        MmlfLog.info("world","shared_world_published",Map.of());
    }
    static void withdraw(String identity) { if(identity!=null) InJvm.registry().remove(key(identity)); }

    /**
     * When the primary's map may be read: its world is open and its client world (which the reads answer from) shows its
     * scene, if any, plus a second. Before that its map is still being made (a fresh world without the scene floor; the
     * server's placements reach the client seconds later while the world loads or is paused) and the other runtime's
     * mobs would fall through it; that runtime reads its own world meanwhile. Once ready it stays so while the world is
     * open: switching the other runtime back to its own copy would move the ground under its mobs again.
     */
    private static volatile long readySince;
    private static final long READY_NANOS=1_000_000_000L;
    /** Primary, client tick. */
    static void primaryTick(Minecraft mc,dev.mmlf.common.Session session) {
        if(!ENABLED) return;
        if(mc.level==null || mc.player==null) { readySince=0; return; }
        if(readySince==0 && DemoScene.shown(mc,session)) readySince=System.nanoTime();
    }

    private static int primaryBlock(long packed) {
        var level=Minecraft.getInstance().level; if(level==null) return -1;
        int x=BlockPos.getX(packed),y=BlockPos.getY(packed),z=BlockPos.getZ(packed);
        if(y<level.getMinBuildHeight() || y>=level.getMaxBuildHeight()) return -1;
        var chunk=level.getChunkSource().getChunk(x>>4,z>>4,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false);
        if(chunk==null) return snapshotBlock(level.dimension().location().toString(),x,y,z);
        var section=chunk.getSection(chunk.getSectionIndex(y));
        BlockState state=section.hasOnlyAir()?net.minecraft.world.level.block.Blocks.AIR.defaultBlockState():section.getBlockState(x&15,y&15,z&15);
        // Another runtime's block: that runtime reads its own there.
        if(StandInBlock.is(state)) return -1;
        return Block.BLOCK_STATE_REGISTRY.getId(state);
    }
    private static boolean clientDimension(net.minecraft.client.multiplayer.ClientLevel level,String dimension) {
        return level!=null && level.dimension().location().toString().equals(dimension);
    }
    /** Block answers for one dimension: the client world's where it is that dimension, else the held copies. */
    private static LongToIntFunction primaryBlockIn(String dimension) {
        return packed->{
            if(clientDimension(Minecraft.getInstance().level,dimension)) return primaryBlock(packed);
            return snapshotBlock(dimension,BlockPos.getX(packed),BlockPos.getY(packed),BlockPos.getZ(packed));
        };
    }
    /** Per packed chunk position: whether the primary can answer reads there, from its client world or a held copy. */
    private static LongPredicate primaryCovers(String dimension) {
        return chunk->{
            int x=net.minecraft.world.level.ChunkPos.getX(chunk),z=net.minecraft.world.level.ChunkPos.getZ(chunk);
            var level=Minecraft.getInstance().level;
            if(clientDimension(level,dimension) && level.getChunkSource().getChunk(x,z,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false)!=null) return true;
            return snapshots.containsKey(dimension+"@"+x+","+z);
        };
    }
    /**
     * A chunk's biomes, by packed chunk position: element 0 is the chunk's lowest section, then 64 names per section
     * (quart cells, index {@code y<<4|z<<2|x}); null where neither the client world nor a held copy has the chunk.
     */
    private static LongFunction<String[]> primaryBiomesIn(String dimension) {
        return chunk->{
            int x=net.minecraft.world.level.ChunkPos.getX(chunk),z=net.minecraft.world.level.ChunkPos.getZ(chunk);
            var level=Minecraft.getInstance().level;
            if(clientDimension(level,dimension) && level.getChunkSource().getChunk(x,z,net.minecraft.world.level.chunk.status.ChunkStatus.FULL,false) instanceof LevelChunk loaded) {
                String[] names=biomeNames(loaded.getSections());
                names[0]=Integer.toString(loaded.getMinSection());
                return names;
            }
            Snapshot snapshot=snapshots.get(dimension+"@"+x+","+z);
            return snapshot==null || snapshot.biomes()==null?null:snapshot.biomes();
        };
    }
    private static String[] biomeNames(net.minecraft.world.level.chunk.LevelChunkSection[] sections) {
        String[] out=new String[1+sections.length*64];
        for(int i=0;i<sections.length;i++)
            for(int y=0;y<4;y++) for(int z=0;z<4;z++) for(int x=0;x<4;x++)
                out[1+i*64+(y<<4|z<<2|x)]=sections[i].getNoiseBiome(x,y,z).unwrapKey().map(k->k.location().toString()).orElse(null);
        return out;
    }
    private static double[] primaryShape(int id) {
        BlockState state=Block.BLOCK_STATE_REGISTRY.byId(id); if(state==null) return new double[0];
        var boxes=state.getCollisionShape(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).toAabbs();
        double[] out=new double[boxes.size()*6]; int i=0;
        for(var b:boxes) { out[i++]=b.minX; out[i++]=b.minY; out[i++]=b.minZ; out[i++]=b.maxX; out[i++]=b.maxY; out[i++]=b.maxZ; }
        return out;
    }
    private static int primaryLight(long packed) {
        var level=Minecraft.getInstance().level; if(level==null) return -1;
        BlockPos pos=BlockPos.of(packed);
        if(!level.hasChunkAt(pos)) return -1;
        return level.getBrightness(LightLayer.BLOCK,pos) | level.getBrightness(LightLayer.SKY,pos)<<4;
    }

    /** Primary, any thread: the other runtime's own things changed the map; applied on this server thread. */
    private static void primaryWrite(String[] write) {
        var server=Minecraft.getInstance().getSingleplayerServer();
        if(server==null || write==null || write.length!=3) return;
        server.execute(()->{
            try {
                var level=RedstoneBridge.level(server,write[0]); if(level==null) return;
                BlockPos pos=BlockPos.of(Long.parseLong(write[1]));
                if(!level.isLoaded(pos)) return;
                BlockState state=BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),write[2],false).blockState();
                // Where the other runtime has its own block the primary holds only a stand-in: nothing to write.
                if(StandInBlock.is(level.getBlockState(pos))) return;
                level.setBlock(pos,state,Block.UPDATE_ALL);
                appliedOnPrimary++;
                MmlfLog.sampled("world","shared_write_applied",2000,Map.of("position",pos.toShortString(),"state",write[2],"applied",appliedOnPrimary));
            } catch(Exception e) { MmlfLog.sampled("world","shared_write_rejected",5000,Map.of("state",String.valueOf(write[2]),"exception",e.toString())); }
        });
    }

    private static final net.minecraft.server.level.TicketType<net.minecraft.world.level.ChunkPos> SHARED=
        net.minecraft.server.level.TicketType.create("mmlf_shared",Comparator.comparingLong(net.minecraft.world.level.ChunkPos::toLong));
    /** Primary: chunks it keeps loaded for the other runtime, and copies of their sections ("dimension@x,z" to section ids). */
    private static final Set<String> held=new HashSet<>();
    private static final Map<String,Snapshot> snapshots=new ConcurrentHashMap<>();
    /** A held chunk's section ids (null: only air), from its lowest section, and its biomes as {@link #primaryBiomesIn} answers them. */
    private record Snapshot(int minSection,int[][] sections,String[] biomes) {}
    private static int serverTicks;

    /** Server thread, every tick ({@code RedstoneBridge.tick}). */
    static void serverTick(net.minecraft.server.MinecraftServer server) {
        if(BridgeCore.isPrimary()) { CoGeneration.primaryTick(server); Occupancy.tick(server); }
        if(!ENABLED) return;
        if(!BridgeCore.isPrimary()) { resetScopes(); copyBiomes(); CoGeneration.backgroundTick(server); return; }
        if(++serverTicks%20!=0 || BridgeCore.session()==null) return;
        Set<String> wanted=new HashSet<>();
        if(InJvm.registry().get("interest:"+BridgeCore.session().identity()) instanceof Supplier<?> supplier && supplier.get() instanceof String[] keys)
            wanted.addAll(Arrays.asList(keys));
        for(String key:wanted) {
            if(held.contains(key)) continue;
            var at=parseKey(key); if(at==null) continue;
            var level=RedstoneBridge.level(server,at.getKey()); if(level==null) continue;
            level.getChunkSource().addRegionTicket(SHARED,at.getValue(),0,at.getValue());
            held.add(key);
        }
        for(var it=held.iterator();it.hasNext();) {
            String key=it.next();
            var at=parseKey(key); var level=at==null?null:RedstoneBridge.level(server,at.getKey());
            if(wanted.contains(key)) {
                if(level!=null && !snapshots.containsKey(key) && level.getChunkSource().getChunkNow(at.getValue().x,at.getValue().z) instanceof LevelChunk chunk) snapshots.put(key,copy(chunk));
                continue;
            }
            if(level!=null) level.getChunkSource().removeRegionTicket(SHARED,at.getValue(),0,at.getValue());
            snapshots.remove(key); it.remove();
        }
    }
    private static Map.Entry<String,net.minecraft.world.level.ChunkPos> parseKey(String key) {
        int at=key.lastIndexOf('@'), comma=key.lastIndexOf(',');
        if(at<0 || comma<at) return null;
        try { return Map.entry(key.substring(0,at),new net.minecraft.world.level.ChunkPos(Integer.parseInt(key.substring(at+1,comma)),Integer.parseInt(key.substring(comma+1)))); }
        catch(NumberFormatException e) { return null; }
    }
    private static Snapshot copy(LevelChunk chunk) {
        var sections=chunk.getSections(); int[][] out=new int[sections.length][];
        for(int i=0;i<sections.length;i++) {
            if(sections[i].hasOnlyAir()) continue;
            int[] ids=new int[4096];
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) ids[(y<<8)|(z<<4)|x]=Block.BLOCK_STATE_REGISTRY.getId(sections[i].getBlockState(x,y,z));
            out[i]=ids;
        }
        String[] biomes=biomeNames(sections);
        biomes[0]=Integer.toString(chunk.getMinSection());
        return new Snapshot(chunk.getMinSection(),out,biomes);
    }
    /** Primary, server thread ({@code LevelChunkMixin}): keeps the copies of held chunks current. */
    public static void primaryChanged(LevelChunk chunk,BlockPos pos,BlockState state) {
        if(snapshots.isEmpty()) return;
        String key=chunk.getLevel().dimension().location().toString()+"@"+(pos.getX()>>4)+","+(pos.getZ()>>4);
        Snapshot snapshot=snapshots.get(key); if(snapshot==null) return;
        int[][] sections=snapshot.sections();
        int index=chunk.getSectionIndex(pos.getY()); if(index<0 || index>=sections.length) return;
        if(sections[index]==null) sections[index]=new int[4096];
        sections[index][((pos.getY()&15)<<8)|((pos.getZ()&15)<<4)|(pos.getX()&15)]=Block.BLOCK_STATE_REGISTRY.getId(state);
    }
    private static int snapshotBlock(String dimension,int x,int y,int z) {
        if(snapshots.isEmpty()) return -1;
        Snapshot snapshot=snapshots.get(dimension+"@"+(x>>4)+","+(z>>4)); if(snapshot==null) return -1;
        int[][] sections=snapshot.sections();
        int index=(y>>4)-snapshot.minSection();
        if(index<0 || index>=sections.length) return -1;
        int[] ids=sections[index];
        if(ids==null) return Block.BLOCK_STATE_REGISTRY.getId(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        int id=ids[((y&15)<<8)|((z&15)<<4)|(x&15)];
        return StandInBlock.is(Block.BLOCK_STATE_REGISTRY.byId(id))?-1:id;
    }

    // ---- other runtime: reading ----

    private record View(LongToIntFunction block,IntFunction<String> name,IntFunction<double[]> shape,IntFunction<Integer> emission,LongToIntFunction light,Supplier<String> dimension,java.util.function.Consumer<String[]> write,Supplier<Boolean> ready,
                        Function<String,LongToIntFunction> blockIn,Function<String,LongPredicate> covers,Function<String,LongFunction<String[]>> biomesIn) {}
    /** The attached view's per-dimension answers, by dimension. */
    private static final Map<String,LongToIntFunction> blockIn=new ConcurrentHashMap<>();
    private static final Map<String,LongPredicate> covers=new ConcurrentHashMap<>();
    /** The primary's functions once found, and the same while the primary's map is ready to be read (else null). */
    private static volatile View attached, view;
    private static volatile String primaryDimension;
    private static long nextResolve;
    /** Primary state id to this runtime's state; ids are the primary's, so the table is valid for the whole session. */
    private static volatile BlockState[] states=new BlockState[0];
    private static final Map<Integer,VoxelShape> shapes=new ConcurrentHashMap<>();
    private static boolean reported;
    /**
     * Positions this runtime changed in its own world although the block is not its own (a farmer harvesting, a sheep
     * eating grass). Until such writes go to the primary, reads there answer this runtime's own storage, so its logic
     * sees what it did and never repeats it (harvesting the same crop again would duplicate items).
     */
    private static final Map<Long,Long> pending=new ConcurrentHashMap<>();
    private static final long PENDING_NANOS=2_000_000_000L;
    private static final Set<String> interest=ConcurrentHashMap.newKeySet();
    private static volatile Set<String> entityInterest=Set.of();
    private static final ThreadLocal<java.util.ArrayDeque<Boolean>> scopes=ThreadLocal.withInitial(java.util.ArrayDeque::new);
    private static final ThreadLocal<int[]> authoredDepth=ThreadLocal.withInitial(()->new int[1]);
    private static long forwarded, appliedOnPrimary, lastReport;

    /** Other runtime, client tick: finds the primary's view and notes which dimension it answers for. */
    @SuppressWarnings("unchecked")
    static void tick() {
        if(!ENABLED || BridgeCore.isPrimary() || BridgeCore.session()==null) { attached=view=null; return; }
        View v=attached;
        if(v==null && System.nanoTime()>=nextResolve) {
            nextResolve=System.nanoTime()+1_000_000_000L;
            if(InJvm.registry().get(key(BridgeCore.session().identity())) instanceof Map<?,?> m) {
                try {
                    v=new View((LongToIntFunction)m.get("block"),(IntFunction<String>)m.get("name"),(IntFunction<double[]>)m.get("shape"),
                        (IntFunction<Integer>)m.get("emission"),(LongToIntFunction)m.get("light"),(Supplier<String>)m.get("dimension"),
                        (java.util.function.Consumer<String[]>)m.get("write"),(Supplier<Boolean>)m.get("ready"),
                        (Function<String,LongToIntFunction>)m.get("blockIn"),(Function<String,LongPredicate>)m.get("covers"),
                        (Function<String,LongFunction<String[]>>)m.get("biomesIn"));
                    blockIn.clear(); covers.clear();
                    attached=v;
                    InJvm.registry().put("interest:"+BridgeCore.session().identity(),(Supplier<String[]>)SharedWorld::interestKeys);
                    MmlfLog.info("world","shared_world_attached",Map.of());
                } catch(ClassCastException e) { MmlfLog.warn("world","shared_world_incompatible",Map.of("exception",e.toString())); }
            }
        }
        boolean ready=false;
        if(v!=null) {
            try { ready=v.ready()==null || Boolean.TRUE.equals(v.ready().get()); primaryDimension=ready?v.dimension().get():null; }
            catch(RuntimeException e) { ready=false; primaryDimension=null; }
        }
        if(ready!=(view!=null)) MmlfLog.info("world",ready?"shared_world_ready":"shared_world_waiting",Map.of());
        view=ready?v:null;
        if(ready && !probed) { probed=true; probe(v); }
        long now=System.nanoTime();
        if(now-lastReport>30_000_000_000L && v!=null) { lastReport=now; MmlfLog.info("world","shared_world",Map.of("forwardedWrites",forwarded,"pending",pending.size(),"interestChunks",interest.size()+entityInterest.size())); }
    }
    /** Diagnostics: what the primary answers on the demo floor and at spawn, once its map becomes readable. */
    private static boolean probed;
    private static int holes;
    private static void probe(View v) {
        var level=Minecraft.getInstance().level;
        for(int[] p:new int[][]{{0,99,4},{-2,99,2},{3,99,2},{0,100,4},{0,-61,0}}) {
            BlockPos pos=new BlockPos(p[0],p[1],p[2]);
            Map<String,Object> fields=new LinkedHashMap<>();
            fields.put("position",pos.toShortString());
            try {
                int id=v.block().applyAsInt(pos.asLong());
                fields.put("id",id);
                if(id>=0) { fields.put("name",String.valueOf(v.name().apply(id))); fields.put("mapped",translate(v,id).toString()); }
            } catch(RuntimeException e) { fields.put("exception",e.toString()); }
            if(level!=null) fields.put("read",level.getBlockState(pos).toString());
            fields.put("dimension",String.valueOf(primaryDimension));
            MmlfLog.info("world","shared_world_probe",fields);
        }
    }
    static void detach() { biomesPending.clear(); biomesQueued.clear(); attached=view=null; primaryDimension=null; states=new BlockState[0]; shapes.clear(); pending.clear(); blockIn.clear(); covers.clear(); }

    /**
     * Other runtime, server thread: one of its own things starts acting ({@code authored}) or something that is not
     * starts; writes are forwarded only while the innermost scope is authored. Paired with {@link #end}.
     */
    public static void begin(boolean authored) {
        if(BridgeCore.isPrimary()) return;
        scopes.get().push(authored);
        if(authored) authoredDepth.get()[0]++;
    }
    public static void end() {
        if(BridgeCore.isPrimary()) return;
        Boolean authored=scopes.get().poll();
        if(Boolean.TRUE.equals(authored)) authoredDepth.get()[0]--;
    }
    public static boolean authored() { return authoredDepth.get()[0]>0; }
    /** Server thread, each tick: scopes an exception left open are closed. */
    private static void resetScopes() { scopes.get().clear(); authoredDepth.get()[0]=0; }
    /** Whether {@code state} at {@code pos} is a block of this (the other) runtime. */
    public static boolean ownBlock(BlockState state,BlockPos pos) { return own(state,pos); }

    /** {@code LevelChunkMixin}, other runtime's server: a block changed in its own world. */
    public static void changed(Level level,BlockPos pos,BlockState state) {
        View v=view;
        if(v==null || v.write()==null || BridgeCore.isPrimary() || !authored() || own(state,pos)) return;
        pending.put(pos.asLong(),System.nanoTime());
        forwarded++;
        try { v.write().accept(new String[]{level.dimension().location().toString(),Long.toString(pos.asLong()),BlockStateParser.serialize(state)}); }
        catch(RuntimeException e) { MmlfLog.sampled("world","shared_write_failed",5000,Map.of("exception",e.toString())); }
    }
    /**
     * Other runtime: the primary's map is read and written here, so a vanilla block this runtime places is forwarded to
     * the primary and is the primary's, never claimed as this runtime's own as well.
     */
    static boolean forwarding() { View v=view; return v!=null && v.write()!=null && !BridgeCore.isPrimary(); }
    /** Chunks holding this runtime's own blocks ({@code ChunkFollow.own}) and entities, as "dimension@x,z". */
    static void own(String dimension,int x,int z,boolean has) {
        if(BridgeCore.isPrimary()) return;
        if(has) interest.add(dimension+"@"+x+","+z); else interest.remove(dimension+"@"+x+","+z);
    }
    static void entityChunks(Set<String> keys) { entityInterest=Set.copyOf(keys); }
    private static String[] interestKeys() {
        var all=new HashSet<String>(interest); all.addAll(entityInterest);
        return all.toArray(String[]::new);
    }

    /** The primary's block answers for this level's dimension, or null. */
    private static LongToIntFunction blockFor(View v,Level level) {
        if(level==null) return null;
        if(v.blockIn()!=null) return blockIn.computeIfAbsent(level.dimension().location().toString(),v.blockIn());
        return viewFor(level)!=null?v.block():null;
    }
    /**
     * Whether this runtime's things in that chunk may act: always, unless its world is hollow ({@link #hollow}); then
     * only once the primary's map is ready and answers for the chunk. Server thread.
     */
    public static boolean covered(Level level,int chunkX,int chunkZ) {
        if(level==null || level.isClientSide() || !hollow()) return true;
        View v=view; if(v==null) return false;
        String dimension=level.dimension().location().toString();
        if(v.covers()==null) return dimension.equals(primaryDimension);
        try { return covers.computeIfAbsent(dimension,v.covers()).test(net.minecraft.world.level.ChunkPos.asLong(chunkX,chunkZ)); }
        catch(RuntimeException e) { return false; }
    }
    /** Hollow world, server thread: loaded chunks whose biomes are not yet the primary's. */
    private static final java.util.ArrayDeque<LevelChunk> biomesPending=new java.util.ArrayDeque<>();
    private static final Set<LevelChunk> biomesQueued=Collections.newSetFromMap(new IdentityHashMap<>());
    private static long biomesCopied;
    /** {@code LevelChunkMixin}: a chunk of this runtime's server loaded; a hollow world takes its biomes from the primary. */
    public static void loaded(LevelChunk chunk,boolean loaded) {
        if(!(chunk.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        if(BridgeCore.isPrimary()) { CoGeneration.primaryLoaded(chunk,loaded); return; }
        if(!loaded) return;
        // Which vanilla blocks are this runtime's must be known before any chunk is looked at.
        OwnedVanillaStore.ensureLoaded(level.getServer());
        if(!hollow()) return;
        CoGeneration.chunkLoaded(chunk);
        hollowOut(chunk);
        if(biomesQueued.add(chunk)) biomesPending.add(chunk);
    }
    private static long hollowedBlocks;
    /**
     * A hollow world holds only this runtime's own blocks. A world made before it was hollow (or before it moved into
     * the primary's save) still has the terrain it generated then: as each chunk loads, everything that is not its own
     * is cleared, with its block entities, so nothing of it is read, lit, saved or ticked again.
     */
    private static void hollowOut(LevelChunk chunk) {
        var sections=chunk.getSections(); int removed=0;
        var air=net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        int baseX=chunk.getPos().getMinBlockX(), baseZ=chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        for(int i=0;i<sections.length;i++) {
            var section=sections[i];
            if(section.hasOnlyAir() || !section.maybeHas(state->!state.isAir() && (StandInBlock.is(state) || BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")))) continue;
            int baseY=chunk.getSectionYFromSectionIndex(i)<<4;
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                BlockState state=section.getBlockState(x,y,z);
                if(state.isAir()) continue;
                pos.set(baseX+x,baseY+y,baseZ+z);
                if(own(state,pos) && !StandInBlock.is(state)) continue;
                section.setBlockState(x,y,z,air,false);
                if(state.hasBlockEntity()) chunk.removeBlockEntity(pos.immutable());
                removed++;
            }
        }
        if(removed==0) return;
        chunk.setUnsaved(true);
        hollowedBlocks+=removed;
        MmlfLog.sampled("world","hollowed_out",5000,Map.of("chunk",chunk.getPos().toString(),"removed",removed,"total",hollowedBlocks));
    }
    /**
     * Server thread, each tick: copies the primary's biomes into chunks it can answer for, a bounded number per tick;
     * the others wait for their turn again (their things are not acting meanwhile, {@link #covered}). Biomes are stored,
     * not only read, so this runtime's client (and the renderers reading its sections directly) sees them too.
     */
    private static void copyBiomes() {
        if(biomesPending.isEmpty()) return;
        View v=view; if(v==null || v.biomesIn()==null) return;
        Map<net.minecraft.server.level.ServerLevel,List<net.minecraft.world.level.chunk.ChunkAccess>> changed=new HashMap<>();
        for(int budget=Math.min(256,biomesPending.size());budget>0;budget--) {
            LevelChunk chunk=biomesPending.poll(); if(chunk==null) break;
            var level=(net.minecraft.server.level.ServerLevel)chunk.getLevel();
            var pos=chunk.getPos();
            if(level.getChunkSource().getChunkNow(pos.x,pos.z)!=chunk) { biomesQueued.remove(chunk); continue; }
            String[] answer;
            try { answer=v.biomesIn().apply(level.dimension().location().toString()).apply(pos.toLong()); } catch(RuntimeException e) { answer=null; }
            if(answer==null) { biomesPending.add(chunk); continue; }
            biomesQueued.remove(chunk);
            applyBiomeNames(chunk,answer,level);
            chunk.setUnsaved(true);
            changed.computeIfAbsent(level,l->new ArrayList<>()).add(chunk);
            biomesCopied++;
        }
        changed.forEach((level,chunks)->level.getChunkSource().chunkMap.resendBiomesForChunks(chunks));
        if(!changed.isEmpty()) MmlfLog.sampled("world","shared_biomes_copied",5000,Map.of("copied",biomesCopied,"pending",biomesPending.size()));
    }
    /**
     * Sets a chunk's biomes from the primary's answer ({@link #primaryBiomesIn}: element 0 the lowest section, then 64
     * names per section). A biome of the primary's mods this runtime does not have keeps this runtime's own cell.
     */
    static void applyBiomeNames(net.minecraft.world.level.chunk.ChunkAccess chunk,String[] names,Level level) {
        int primaryMin=Integer.parseInt(names[0]);
        var registry=level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        Map<String,Optional<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>>> resolved=new HashMap<>();
        chunk.fillBiomesFromNoise((qx,qy,qz,sampler)->{
            int index=1+((qy>>2)-primaryMin)*64+((qy&3)<<4|(qz&3)<<2|(qx&3));
            String name=index>0 && index<names.length?names[index]:null;
            var own=chunk.getNoiseBiome(qx,qy,qz);
            if(name==null) return own;
            return resolved.computeIfAbsent(name,n->{
                var id=net.minecraft.resources.ResourceLocation.tryParse(n);
                if(id==null) return Optional.empty();
                return registry.getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BIOME,id)).map(h->(net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>)h);
            }).orElse(own);
        },null);
    }
    /**
     * Primary: a chunk of one dimension as co-generation copies it ({@code CoGeneration}): its lowest section, the
     * state ids of each section (null where only air; another runtime's stand-in reads as air), and its biomes.
     */
    static Object[] describe(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        var sections=chunk.getSections(); int[][] ids=new int[sections.length][];
        int air=Block.BLOCK_STATE_REGISTRY.getId(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        for(int i=0;i<sections.length;i++) {
            if(sections[i].hasOnlyAir()) continue;
            int[] out=new int[4096];
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                BlockState state=sections[i].getBlockState(x,y,z);
                out[(y<<8)|(z<<4)|x]=StandInBlock.is(state)?air:Block.BLOCK_STATE_REGISTRY.getId(state);
            }
            ids[i]=out;
        }
        String[] biomes=biomeNames(sections);
        biomes[0]=Integer.toString(chunk.getMinSection());
        return new Object[]{chunk.getMinSection(),ids,biomes};
    }
    /** Other runtime: this runtime's state for one of the primary's state ids (a stand-in for its mods' blocks), or null before attaching. */
    static BlockState primaryState(int id) {
        View v=attached; if(v==null || id<0) return null;
        BlockState[] table=states;
        BlockState mapped=id<table.length?table[id]:null;
        return mapped!=null?mapped:translate(v,id);
    }
    private static View viewFor(Level level) {
        View v=view; String dimension=primaryDimension;
        if(v==null || dimension==null || level==null || !dimension.equals(level.dimension().location().toString())) return null;
        return v;
    }
    /** Whether this runtime's own block stands there: its mods' blocks and the vanilla blocks it owns. */
    private static boolean own(BlockState state,BlockPos pos) {
        return !state.isAir() && (!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft") || BridgeCore.ownsVanilla(pos));
    }

    /** {@code LevelChunkMixin}, other runtime: its own block, or the primary's map. */
    public static BlockState read(LevelChunk chunk,BlockPos pos,BlockState own) {
        View v=view; if(v==null || own(own,pos)) return own;
        LongToIntFunction block=blockFor(v,chunk.getLevel()); if(block==null) return own;
        int id;
        try { id=block.applyAsInt(pos.asLong()); } catch(RuntimeException e) { return own; }
        if(id<0) return own;
        BlockState[] table=states;
        BlockState mapped=id<table.length?table[id]:null;
        if(mapped==null) mapped=translate(v,id);
        if(holes<20 && mapped.isAir() && !own.isAir()) {
            holes++;
            String name; try { name=String.valueOf(v.name().apply(id)); } catch(RuntimeException e) { name=e.toString(); }
            MmlfLog.warn("world","shared_world_hole",Map.of("position",pos.toShortString(),"id",id,"name",name,"own",own.toString(),"thread",Thread.currentThread().getName()));
        }
        // A write of this runtime the primary's map does not show yet: what this runtime just did.
        if(!pending.isEmpty()) {
            Long since=pending.get(pos.asLong());
            if(since!=null) {
                if(mapped!=own && System.nanoTime()-since<PENDING_NANOS) return own;
                pending.remove(pos.asLong());
            }
        }
        return mapped;
    }
    private static synchronized BlockState translate(View v,int id) {
        BlockState[] table=states;
        if(id<table.length && table[id]!=null) return table[id];
        String name=v.name().apply(id);
        BlockState mapped=null;
        if(name!=null) {
            try { mapped=BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),name,false).blockState(); }
            catch(Exception e) { mapped=null; }
        }
        if(mapped==null) {
            // A block of the primary's mods: the stand-in, with the primary's collision and light.
            double[] boxes=v.shape().apply(id);
            boolean solid=boxes.length==6 && boxes[0]<=0 && boxes[1]<=0 && boxes[2]<=0 && boxes[3]>=1 && boxes[4]>=1 && boxes[5]>=1;
            mapped=StandInBlock.of(solid,v.emission().apply(id));
            if(mapped==null) mapped=net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            if(!reported) { reported=true; MmlfLog.info("world","shared_world_foreign_block",Map.of("name",String.valueOf(name))); }
        }
        if(id>=table.length) table=Arrays.copyOf(table,Math.max(id+1,table.length*2+64));
        else table=table.clone();
        table[id]=mapped;
        states=table;
        return mapped;
    }

    /** {@link StandInBlock}, other runtime: the collision of the primary's block at the position, or null. */
    static VoxelShape collision(BlockGetter getter,BlockPos pos) {
        View v=view; if(v==null) return null;
        LongToIntFunction block=getter instanceof Level level?blockFor(v,level):v.block(); if(block==null) return null;
        int id;
        try { id=block.applyAsInt(pos.asLong()); } catch(RuntimeException e) { return null; }
        if(id<0) return null;
        return shapes.computeIfAbsent(id,i->{
            double[] b=v.shape().apply(i); VoxelShape shape=Shapes.empty();
            for(int k=0;k+5<b.length;k+=6) shape=Shapes.or(shape,Shapes.box(b[k],b[k+1],b[k+2],b[k+3],b[k+4],b[k+5]));
            return shape.optimize();
        });
    }

    /** {@code LevelLightMixin}, other runtime: the primary's light at the position, or -1 to use its own. */
    public static int light(Level level,LightLayer layer,BlockPos pos) {
        View v=view; if(v==null) return -1;
        v=viewFor(level); if(v==null) return -1;
        int packed;
        try { packed=v.light().applyAsInt(pos.asLong()); } catch(RuntimeException e) { return -1; }
        if(packed<0) return -1;
        return layer==LightLayer.BLOCK ? packed&15 : (packed>>4)&15;
    }
}
