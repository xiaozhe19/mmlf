package dev.mmlf.bridge;

import dev.mmlf.common.GenerationInstructions;
import dev.mmlf.common.InJvm;
import dev.mmlf.common.MmlfLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Co-generation: the other runtime's mods' decoration is added to the primary's map after the primary has decorated it.
 * Neither side waits for the other inside a generation step.
 * <ol>
 * <li>Primary: generates as vanilla does. A chunk it decorates while co-generation is on is <em>fresh</em> (persisted)
 * until the background has decorated it too; chunks decorated before are never decorated again (no retrogen).</li>
 * <li>Background (a hollow world, {@link SharedWorld#hollow}): in place of its terrain step, a chunk copies the
 * primary's blocks and biomes once the primary has decorated that chunk ({@link #fill}); polled, never blocking a
 * worker. A chunk the primary has not decorated is asked for: the primary holds it at its decoration step meanwhile.
 * Its decoration step then runs only its own mods' decoration ({@code HollowFeatureFilterMixin}), seeing the primary's
 * trees and ores, and records every block written ({@link Recording}).</li>
 * <li>The writes become instructions ({@link GenerationInstructions}) delivered to the primary (kept and saved here
 * until delivered; never while frozen for a save, so both saves agree).</li>
 * <li>Primary, server thread, a few milliseconds per tick ({@link #primaryTick}): per complete loaded chunk, each
 * instruction is carried out where the block is still the one the background saw; a clear it rejects is undone in the
 * background. Instructions for chunks not loaded wait, saved with the primary's save points.</li>
 * </ol>
 * A background chunk still waiting for the primary's terrain when its server stops is not saved, so it is generated
 * again next time; a chunk whose neighbourhood had such a chunk is left undecorated. {@code MMLF_COGEN=off} (or
 * {@code -Dmmlf.cogen=off}): the background generates nothing, as before.
 */
public final class CoGeneration {
    private CoGeneration() {}
    private static final boolean ENABLED=!"off".equalsIgnoreCase(System.getProperty("mmlf.cogen",System.getenv().getOrDefault("MMLF_COGEN","on")));

    public static boolean active() {
        var session=BridgeCore.session();
        return ENABLED && SharedWorld.enabled() && session!=null && WorldFollow.enabled(session);
    }
    /** Background: its hollow world runs its own mods' decoration against the primary's map. */
    public static boolean backgroundDecorates() { return active() && SharedWorld.hollow(); }

    private static String primaryKey(String identity) { return "cogen-primary:"+identity; }
    private static String backgroundKey(String identity) { return "cogen-background:"+identity; }
    private static String key(String dimension,int x,int z) { return dimension+"@"+x+","+z; }
    private static String dimension(net.minecraft.world.level.Level level) { return level.dimension().location().toString(); }
    /** "dimension@x,z" back to its dimension and chunk, or null. */
    private static Map.Entry<String,ChunkPos> parseKey(String key) {
        int at=key.lastIndexOf('@'), comma=key.lastIndexOf(',');
        if(at<0 || comma<at) return null;
        try { return Map.entry(key.substring(0,at),new ChunkPos(Integer.parseInt(key.substring(at+1,comma)),Integer.parseInt(key.substring(comma+1)))); }
        catch(NumberFormatException e) { return null; }
    }

    private static final TicketType<ChunkPos> TICKET=TicketType.create("mmlf_cogen",Comparator.comparingLong(ChunkPos::toLong));
    /** A region ticket's "radius" is counted from a complete chunk: this negative one holds a chunk at its decoration step. */
    private static final int FEATURES_RADIUS=net.minecraft.server.level.ChunkLevel.byStatus(net.minecraft.server.level.FullChunkStatus.FULL)
        -net.minecraft.server.level.ChunkLevel.byStatus(ChunkStatus.FEATURES);
    private static final ScheduledExecutorService poller=Executors.newSingleThreadScheduledExecutor(r->{ Thread t=new Thread(r,"MMLF co-generation"); t.setDaemon(true); return t; });
    /** Copies and chunk-cache requests run here in parallel; the poller only keeps time. */
    private static final ExecutorService copiers=Executors.newFixedThreadPool(Math.max(2,Runtime.getRuntime().availableProcessors()/2),r->{ Thread t=new Thread(r,"MMLF co-generation copy"); t.setDaemon(true); return t; });

    /**
     * Runs on the level's server thread from the chunk cache's own queue: asked from another thread, the chunk cache
     * answers a lookup there (also while the server thread waits for a chunk), and what follows the answer runs there.
     */
    private static void onChunkThread(ServerLevel level,Runnable task) {
        copiers.execute(()->level.getChunkSource().getChunkFuture(0,0,ChunkStatus.EMPTY,false).whenComplete((r,e)->{
            try { task.run(); } catch(RuntimeException x) { MmlfLog.warn("cogen","chunk_task_failed",Map.of("exception",x.toString())); }
        }));
    }

    // ================= primary =================

    /** Primary: chunks decorated with co-generation on, not yet decorated by the background ("dimension@x,z"). */
    private static final Set<String> fresh=ConcurrentHashMap.newKeySet();
    /** Primary: instructions per target chunk, in arrival order, until that chunk is complete and loaded. */
    private static final Map<String,List<String>> queued=new HashMap<>();
    private static final ConcurrentLinkedQueue<String> ready=new ConcurrentLinkedQueue<>();
    /** Primary: complete chunks its server has loaded. */
    private static final Set<String> loaded=ConcurrentHashMap.newKeySet();
    /** Primary: chunks held at their decoration step for the background. */
    private static final Set<String> wanted=ConcurrentHashMap.newKeySet();
    private static long applied, rejected, received, applyNanos, appliedChunks, appliedInstructions;

    /** Primary, at joining its session: what the background asks of it. */
    static void publish(String identity) {
        if(!ENABLED || identity==null) return;
        Map<String,Object> api=new HashMap<>();
        api.put("terrainIn",(Function<String,LongFunction<Object[]>>)CoGeneration::terrainIn);
        api.put("fresh",(Function<String,LongPredicate>)dimension->chunk->{ primaryServer(); return fresh.contains(key(dimension,ChunkPos.getX(chunk),ChunkPos.getZ(chunk))); });
        api.put("want",(BiConsumer<String,Long>)(dimension,chunk)->hold(dimension,chunk,true));
        api.put("release",(BiConsumer<String,Long>)(dimension,chunk)->hold(dimension,chunk,false));
        api.put("decorated",(Consumer<Object[]>)CoGeneration::receive);
        api.put("status",(Function<String,LongFunction<String>>)CoGeneration::statusIn);
        InJvm.registry().put(primaryKey(identity),Map.copyOf(api));
    }
    static void withdraw(String identity) { if(identity!=null) InJvm.registry().remove(primaryKey(identity)); }

    /** {@code SharedWorld.loaded}, primary, server thread: a complete chunk loaded or unloaded. */
    static void primaryLoaded(LevelChunk chunk,boolean isLoaded) {
        if(!ENABLED || !(chunk.getLevel() instanceof ServerLevel level)) return;
        String key=key(dimension(level),chunk.getPos().x,chunk.getPos().z);
        if(!isLoaded) { loaded.remove(key); return; }
        ensurePrimaryLoaded(level.getServer());
        loaded.add(key);
        boolean waiting; synchronized(queued) { waiting=queued.containsKey(key); }
        if(waiting) ready.add(key);
    }

    /** {@code ChunkStatusTasks.generateFeatures}, both roles, around {@code applyBiomeDecoration}. */
    public static void decorate(WorldGenLevel region,ChunkAccess chunk,Runnable original) {
        if(!active()) { original.run(); return; }
        if(BridgeCore.isPrimary()) {
            original.run();
            ServerLevel level=region.getLevel();
            ensurePrimaryLoaded(level.getServer());
            fresh.add(key(dimension(level),chunk.getPos().x,chunk.getPos().z));
        }
        else if(SharedWorld.hollow()) backgroundDecorate(region,chunk,original);
        else original.run();
    }

    /**
     * For the background, any thread: a chunk the primary has decorated, as {@code SharedWorld.describe} gives it, or
     * null. Read while the primary may still decorate a neighbour into it; a torn read fails and is asked again.
     */
    private static LongFunction<Object[]> terrainIn(String dimension) {
        return chunk->{
            MinecraftServer server=primaryServer();
            ServerLevel level=server==null?null:RedstoneBridge.level(server,dimension);
            if(level==null) return null;
            var holder=((dev.mmlf.bridge.mixin.ChunkMapAccessor)level.getChunkSource().chunkMap).mmlf$visible(chunk);
            ChunkAccess access=holder==null?null:holder.getLatestChunk();
            if(access==null || !access.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) return null;
            try { return SharedWorld.describe(access); } catch(RuntimeException e) { return null; }
        };
    }
    /** For the background's reports: what the primary has of a chunk (holder, its ticket level and statuses). */
    private static LongFunction<String> statusIn(String dimension) {
        return chunk->{
            MinecraftServer server=Minecraft.getInstance().getSingleplayerServer();
            ServerLevel level=server==null?null:RedstoneBridge.level(server,dimension);
            if(level==null) return "no level";
            var holder=((dev.mmlf.bridge.mixin.ChunkMapAccessor)level.getChunkSource().chunkMap).mmlf$visible(chunk);
            String key=key(dimension,ChunkPos.getX(chunk),ChunkPos.getZ(chunk));
            if(holder==null) return "no holder, held="+wanted.contains(key);
            ChunkAccess latest=holder.getLatestChunk();
            return "level="+holder.getTicketLevel()+" latestStatus="+holder.getLatestStatus()+" persisted="+(latest==null?"none":latest.getPersistedStatus())+" held="+wanted.contains(key)
                +" featuresLevel="+net.minecraft.server.level.ChunkLevel.byStatus(ChunkStatus.FEATURES);
        };
    }
    /** For the background: holds a chunk at its decoration step (generating it so far) until released. */
    private static void hold(String dimension,long chunk,boolean want) {
        String key=key(dimension,ChunkPos.getX(chunk),ChunkPos.getZ(chunk));
        if(want ? !wanted.add(key) : !wanted.remove(key)) return;
        MinecraftServer server=Minecraft.getInstance().getSingleplayerServer();
        ServerLevel level=server==null?null:RedstoneBridge.level(server,dimension);
        if(level==null) return;
        ChunkPos pos=new ChunkPos(chunk);
        onChunkThread(level,()->{
            if(!want) { level.getChunkSource().removeRegionTicket(TICKET,pos,FEATURES_RADIUS,pos); return; }
            level.getChunkSource().addRegionTicket(TICKET,pos,FEATURES_RADIUS,pos);
            // A ticket only keeps a chunk; generation up to a status starts when someone asks for it. Asked from another
            // thread: on the server thread the chunk cache would wait for the answer in place.
            copiers.execute(()->level.getChunkSource().getChunkFuture(pos.x,pos.z,ChunkStatus.FEATURES,true));
        });
    }
    /** From the background, any thread: {dimension, chunk, instructions} of one chunk it decorated. */
    private static void receive(Object[] decoration) {
        primaryServer();
        String dimension=(String)decoration[0]; long chunk=(Long)decoration[1]; String[] instructions=(String[])decoration[2];
        fresh.remove(key(dimension,ChunkPos.getX(chunk),ChunkPos.getZ(chunk)));
        enqueue(dimension,instructions);
    }
    private static void enqueue(String dimension,String[] instructions) {
        Set<String> touched=new LinkedHashSet<>();
        synchronized(queued) {
            for(String text:instructions) {
                var i=GenerationInstructions.parse(text); if(i==null) continue;
                String key=key(dimension,BlockPos.getX(i.pos())>>4,BlockPos.getZ(i.pos())>>4);
                queued.computeIfAbsent(key,k->new ArrayList<>()).add(text);
                touched.add(key);
                received++;
            }
        }
        for(String key:touched) if(loaded.contains(key)) ready.add(key);
    }

    private static final long TICK_BUDGET_NANOS=3_000_000L;
    /** Primary, server thread, every tick: instructions of loaded complete chunks, for a few milliseconds. */
    static void primaryTick(MinecraftServer server) {
        if(!ENABLED || !BridgeCore.isPrimary()) return;
        long start=System.nanoTime();
        String key; List<String> notYet=null;
        while(System.nanoTime()-start<TICK_BUDGET_NANOS && (key=ready.poll())!=null) {
            var at=parseKey(key); if(at==null) continue;
            ServerLevel level=RedstoneBridge.level(server,at.getKey());
            LevelChunk chunk=level==null?null:level.getChunkSource().getChunkNow(at.getValue().x,at.getValue().z);
            if(chunk==null) {
                // Loaded (its promotion has run) but not yet answered as complete: tried again next tick. Unloaded: ready again when it loads.
                if(loaded.contains(key)) (notYet==null?notYet=new ArrayList<>():notYet).add(key);
                continue;
            }
            List<String> instructions; synchronized(queued) { instructions=queued.remove(key); }
            if(instructions==null) continue;
            long began=System.nanoTime();
            try { apply(level,chunk,at.getKey(),instructions); }
            catch(RuntimeException e) { MmlfLog.warn("cogen","apply_failed",Map.of("chunk",key,"exception",e.toString())); }
            applyNanos+=System.nanoTime()-began; appliedChunks++; appliedInstructions+=instructions.size();
        }
        if(notYet!=null) ready.addAll(notYet);
        report();
    }
    private static final Map<String,BlockState> parsed=new ConcurrentHashMap<>();
    private static void apply(ServerLevel level,LevelChunk chunk,String dimension,List<String> instructions) {
        // The chunk's own blocks: where the background holds a block, the primary's chunk reads as a stand-in.
        var outcome=GenerationInstructions.decide(instructions,p->{
            BlockPos pos=BlockPos.of(p); int index=chunk.getSectionIndex(pos.getY());
            if(index<0 || index>=chunk.getSections().length) return GenerationInstructions.FOREIGN;
            BlockState raw=chunk.getSection(index).getBlockState(pos.getX()&15,pos.getY()&15,pos.getZ()&15);
            return StandInBlock.is(raw) ? GenerationInstructions.OWNED : BlockStateParser.serialize(raw);
        });
        for(var entry:outcome.apply().entrySet()) {
            BlockState state=parsed.computeIfAbsent(entry.getValue(),name->{
                if(name.startsWith(GenerationInstructions.STAND_IN)) {
                    // The background's block: its stand-in records the position as the background's in this map.
                    BlockState standIn=StandInBlock.of(dev.mmlf.common.StandInSpec.parse(name.substring(GenerationInstructions.STAND_IN.length())));
                    return standIn!=null ? standIn : Blocks.VOID_AIR.defaultBlockState();
                }
                try { return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),name,false).blockState(); }
                catch(Exception e) { return Blocks.VOID_AIR.defaultBlockState(); }
            });
            if(state.is(Blocks.VOID_AIR)) { rejected++; continue; }
            BlockPos pos=BlockPos.of(entry.getKey());
            // As generation places blocks: no neighbour updates, no shape updates (nothing pops off), no drops.
            if(!level.setBlock(pos,state,Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE)) { if(level.getBlockState(pos)!=state) { rejected++; continue; } }
            if(!state.getFluidState().isEmpty()) level.scheduleTick(pos,state.getFluidState().getType(),0);
            applied++;
        }
        rejected+=outcome.rejected();
        // What the background reported for the chunk is checked again now its instructions are carried out.
        Occupancy.check(dimension,chunk.getPos().toLong());
        if(outcome.rejectedClears().isEmpty()) return;
        long first=outcome.rejectedClears().get(0); BlockPos at=BlockPos.of(first);
        String expected=instructions.stream().map(GenerationInstructions::parse).filter(i->i!=null && i.clear() && i.pos()==first).map(GenerationInstructions.Instruction::expected).findFirst().orElse("?");
        MmlfLog.sampled("cogen","clear_rejected",2000,Map.of("chunk",dimension+"@"+chunk.getPos().x+","+chunk.getPos().z,"count",outcome.rejectedClears().size(),
            "position",at.toShortString(),"expected",expected,"actual",BlockStateParser.serialize(chunk.getBlockState(at))));
        revertOwnerBlocks(dimension,outcome.rejectedClears().stream().mapToLong(Long::longValue).toArray());
    }
    /** Primary: the background removes its blocks at these positions (the primary's own blocks are there). */
    static void revertOwnerBlocks(String dimension,long[] positions) {
        var session=BridgeCore.session();
        if(session!=null && InJvm.registry().get(backgroundKey(session.identity())) instanceof Map<?,?> api && api.get("reverts") instanceof BiConsumer<?,?> reverts) {
            @SuppressWarnings("unchecked") var r=(BiConsumer<String,long[]>)reverts;
            try { r.accept(dimension,positions); } catch(RuntimeException e) { MmlfLog.warn("cogen","revert_failed",Map.of("exception",e.toString())); }
        } else MmlfLog.sampled("cogen","revert_unavailable",5000,Map.of("positions",positions.length));
    }
    /** Primary: whether generation instructions for this chunk are still to be carried out here. */
    static boolean instructionsWaiting(String dimension,int chunkX,int chunkZ) {
        synchronized(queued) { return queued.containsKey(key(dimension,chunkX,chunkZ)); }
    }
    private static long lastReport, lastReported=-1;
    private static void report() {
        long now=System.nanoTime(); if(now-lastReport<10_000_000_000L) return;
        lastReport=now;
        if(received+applied+rejected==lastReported) return;
        lastReported=received+applied+rejected;
        int waiting; synchronized(queued) { waiting=queued.size(); }
        MmlfLog.info("cogen","primary",Map.of("received",received,"applied",applied,"rejected",rejected,"chunksWaiting",waiting,"fresh",fresh.size(),"held",wanted.size(),
            "appliedChunks",appliedChunks,"microsPerInstruction",appliedInstructions==0?0:applyNanos/1000/appliedInstructions,"applyMs",applyNanos/1_000_000));
    }

    // ---- primary persistence (with each of its saves) ----

    private static volatile MinecraftServer primaryLoadedFor;
    /** Primary: its server, with its co-generation state loaded (the background may ask before any chunk loads). */
    private static MinecraftServer primaryServer() {
        MinecraftServer server=Minecraft.getInstance().getSingleplayerServer();
        ensurePrimaryLoaded(server);
        return server;
    }
    private static synchronized void ensurePrimaryLoaded(MinecraftServer server) {
        if(server==null || primaryLoadedFor==server) return;
        primaryLoadedFor=server;
        fresh.clear(); synchronized(queued) { queued.clear(); } ready.clear(); loaded.clear(); wanted.clear();
        var json=readState(server); if(json==null) return;
        if(json.has("fresh")) for(var k:json.getAsJsonArray("fresh")) fresh.add(k.getAsString());
        if(json.has("queued")) synchronized(queued) {
            for(var e:json.getAsJsonObject("queued").entrySet()) {
                List<String> list=new ArrayList<>(); for(var s:e.getValue().getAsJsonArray()) list.add(s.getAsString());
                queued.put(e.getKey(),list);
            }
        }
        MmlfLog.info("cogen","primary_state_loaded",Map.of("fresh",fresh.size(),"chunksWaiting",queued.size()));
    }
    /** {@code ServerSaveMixin}, primary, after each save of its own. */
    public static void primarySaved(MinecraftServer server) {
        if(!ENABLED || !BridgeCore.isPrimary() || primaryLoadedFor!=server) return;
        var json=new com.google.gson.JsonObject();
        var f=new com.google.gson.JsonArray(); fresh.forEach(f::add); json.add("fresh",f);
        var q=new com.google.gson.JsonObject();
        synchronized(queued) { queued.forEach((k,v)->{ var a=new com.google.gson.JsonArray(); v.forEach(a::add); q.add(k,a); }); }
        json.add("queued",q);
        writeState(server,json);
    }

    // ================= background =================

    /** Instructions not yet delivered to the primary, by decorated chunk. */
    private static final Map<String,String[]> pending=new ConcurrentHashMap<>();
    /** Per dimension, own blocks to remove (a clear the primary rejected), applied as their chunk is loaded. */
    private static final Map<String,Set<Long>> reverts=new ConcurrentHashMap<>();
    private record Fill(ChunkAccess chunk,String key) {}
    private static final Map<CompletableFuture<ChunkAccess>,Fill> fills=new ConcurrentHashMap<>();
    /** Chunks whose fill ended without the primary's terrain (the server stopped): never saved. */
    private static final Set<String> unfilled=ConcurrentHashMap.newKeySet();
    private static final Map<String,Long> fillWaits=new ConcurrentHashMap<>();
    private static long copied, own, skippedOld, skippedUnfilled;
    /** Background: its own blocks placed by decoration, per block type (what the decoration actually adds). */
    private static final Map<String,Long> ownByType=new ConcurrentHashMap<>();
    private static String topTypes() {
        return ownByType.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(12)
            .map(e->e.getKey()+"="+e.getValue()).reduce((a,b)->a+";"+b).orElse("");
    }
    private static volatile boolean delivering;

    /** Background, at joining its session: what the primary asks of it. */
    static void publishBackground(String identity) {
        if(!ENABLED || identity==null) return;
        InJvm.registry().put(backgroundKey(identity),Map.of("reverts",(BiConsumer<String,long[]>)CoGeneration::revert));
        if(!delivering) { delivering=true; poller.scheduleWithFixedDelay(CoGeneration::deliver,1,1,TimeUnit.SECONDS); }
    }
    static void withdrawBackground(String identity) { if(identity!=null) InJvm.registry().remove(backgroundKey(identity)); }

    @SuppressWarnings("unchecked")
    private static <T> T primaryApi(String name) {
        var session=BridgeCore.session(); if(session==null) return null;
        return InJvm.registry().get(primaryKey(session.identity())) instanceof Map<?,?> api ? (T)api.get(name) : null;
    }

    /**
     * Background, in place of its terrain step: the primary's blocks and biomes of this chunk, once the primary has
     * decorated it. Polled, so no generation worker waits; until then the primary holds the chunk for it.
     */
    public static CompletableFuture<ChunkAccess> fill(ServerLevel level,ChunkAccess chunk) {
        CompletableFuture<ChunkAccess> result=new CompletableFuture<>();
        String dimension=dimension(level); long pos=chunk.getPos().toLong(); String key=key(dimension,chunk.getPos().x,chunk.getPos().z);
        fills.put(result,new Fill(chunk,key));
        boolean[] held={false};
        result.whenComplete((c,e)->{
            fills.remove(result); fillWaits.remove(key);
            if(held[0]) { BiConsumer<String,Long> release=primaryApi("release"); if(release!=null) try { release.accept(dimension,pos); } catch(RuntimeException ignored) { } }
        });
        Runnable[] attempt=new Runnable[1];
        attempt[0]=()->{
            if(result.isDone()) return;
            Object[] terrain=null;
            Function<String,LongFunction<Object[]>> terrainIn=primaryApi("terrainIn");
            if(terrainIn!=null && SharedWorld.primaryState(0)!=null) {
                try { terrain=terrainIn.apply(dimension).apply(pos); } catch(RuntimeException e) { terrain=null; }
            }
            if(terrain==null) {
                if(!held[0]) { BiConsumer<String,Long> want=primaryApi("want"); if(want!=null) try { want.accept(dimension,pos); held[0]=true; } catch(RuntimeException ignored) { } }
                fillWaits.putIfAbsent(key,System.nanoTime());
                reportWaits();
                poller.schedule(()->copiers.execute(attempt[0]),100,TimeUnit.MILLISECONDS);
                return;
            }
            try { copy(level,chunk,terrain); copied++; } catch(RuntimeException e) { MmlfLog.warn("cogen","fill_failed",Map.of("chunk",chunk.getPos().toString(),"exception",e.toString())); }
            result.complete(chunk);
        };
        attempt[0].run();
        return result;
    }
    private static void copy(ServerLevel level,ChunkAccess chunk,Object[] terrain) {
        int primaryMin=(Integer)terrain[0]; int[][] ids=(int[][])terrain[1]; String[] biomes=(String[])terrain[2];
        var sections=chunk.getSections();
        for(int i=0;i<sections.length;i++) {
            int index=chunk.getMinSection()+i-primaryMin;
            if(index<0 || index>=ids.length || ids[index]==null) continue;
            int[] section=ids[index]; LevelChunkSection target=sections[i];
            int lastId=-1; BlockState last=null;
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
                int id=section[(y<<8)|(z<<4)|x];
                if(id!=lastId) { lastId=id; last=SharedWorld.primaryState(id); }
                if(last==null || last.isAir()) continue;
                target.setBlockState(x,y,z,last,false);
            }
        }
        if(biomes!=null) SharedWorld.applyBiomeNames(chunk,biomes,level);
    }
    private static long lastWaitReport;
    /** Background: chunks waiting for the primary's terrain, every ten seconds while any are. */
    private static void reportWaits() {
        long now=System.nanoTime(); if(now-lastWaitReport<10_000_000_000L) return; lastWaitReport=now;
        if(fillWaits.isEmpty()) return;
        var first=fillWaits.entrySet().stream().min(Map.Entry.comparingByValue()).orElse(null);
        String oldest=first==null?"none":first.getKey()+" "+(now-first.getValue())/1_000_000+"ms";
        String primary="";
        Function<String,LongFunction<String>> status=primaryApi("status");
        var at=first==null?null:parseKey(first.getKey());
        if(status!=null && at!=null) try { primary=status.apply(at.getKey()).apply(at.getValue().toLong()); } catch(RuntimeException e) { primary=e.toString(); }
        MmlfLog.info("cogen","background_waiting",Map.of("terrainWaits",fillWaits.size(),"oldestTerrainWait",oldest,"oldestAtPrimary",primary,"copied",copied,"undelivered",pending.size()));
    }

    private static void backgroundDecorate(WorldGenLevel region,ChunkAccess chunk,Runnable original) {
        ServerLevel level=region.getLevel();
        ensureLoaded(level.getServer());
        String dimension=dimension(level); ChunkPos pos=chunk.getPos(); String key=key(dimension,pos.x,pos.z);
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) if(unfilled.contains(key(dimension,pos.x+dx,pos.z+dz))) {
            // Its neighbourhood has no terrain (the server is stopping): left undecorated; the primary stops expecting it.
            skippedUnfilled++;
            pending.put(key,new String[0]);
            return;
        }
        Function<String,LongPredicate> freshIn=primaryApi("fresh");
        boolean isFresh;
        try { isFresh=freshIn!=null && freshIn.apply(dimension).test(pos.toLong()); } catch(RuntimeException e) { isFresh=false; }
        if(!isFresh) { skippedOld++; return; }
        Recording recording=new Recording();
        for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++) {
            ChunkAccess near=region.getChunk(pos.x+dx,pos.z+dz);
            var sections=near.getSections();
            for(int i=0;i<sections.length;i++) recording.sections.put(sections[i],new int[]{near.getPos().getMinBlockX(),near.getSectionYFromSectionIndex(i)<<4,near.getPos().getMinBlockZ()});
        }
        RECORDING.set(recording);
        try { original.run(); } finally { RECORDING.remove(); }
        List<GenerationInstructions.Write> writes=new ArrayList<>();
        for(var entry:recording.writes.entrySet()) {
            BlockState before=entry.getValue()[0], after=entry.getValue()[1];
            boolean ownAfter=!after.isAir() && !StandInBlock.is(after) && !BuiltInRegistries.BLOCK.getKey(after.getBlock()).getNamespace().equals("minecraft");
            String standIn=null;
            if(ownAfter) {
                own++; ownByType.merge(BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString(),1L,Long::sum);
                standIn=Boundary.standInSpec(region,after,BlockPos.of(entry.getKey())).encode();
            }
            writes.add(new GenerationInstructions.Write(entry.getKey(),StandInBlock.is(before)?GenerationInstructions.FOREIGN:BlockStateParser.serialize(before),BlockStateParser.serialize(after),ownAfter,standIn));
        }
        pending.put(key,GenerationInstructions.build(writes).toArray(String[]::new));
        MmlfLog.sampled("cogen","background",5000,Map.of("copied",copied,"ownPlaced",own,"skippedOld",skippedOld,"skippedUnfilled",skippedUnfilled,"undelivered",pending.size(),"types",ownByType.size(),"top",topTypes()));
        poller.execute(CoGeneration::deliver);
    }

    /**
     * Background, poller: decorations to the primary, once it is there. Not while frozen for a save: what is delivered
     * is in the primary's save, what is not is in this runtime's, at every save point.
     */
    private static void deliver() {
        if(pending.isEmpty() || SaveSync.frozen()) return;
        Consumer<Object[]> decorated=primaryApi("decorated"); if(decorated==null) return;
        for(String key:List.copyOf(pending.keySet())) {
            var at=parseKey(key); String[] instructions=pending.get(key);
            if(at==null || instructions==null) { pending.remove(key); continue; }
            try { decorated.accept(new Object[]{at.getKey(),at.getValue().toLong(),instructions}); pending.remove(key); }
            catch(RuntimeException e) { MmlfLog.warn("cogen","deliver_failed",Map.of("exception",e.toString())); return; }
        }
    }

    /** Primary's server thread, through the registry: clears it rejected; this runtime's blocks there go. */
    private static void revert(String dimension,long[] positions) {
        Set<Long> set=reverts.computeIfAbsent(dimension,d->ConcurrentHashMap.newKeySet());
        for(long p:positions) set.add(p);
        MinecraftServer server=Minecraft.getInstance().getSingleplayerServer();
        if(server!=null) server.execute(()->applyLoadedReverts(server,dimension));
    }
    private static long revertsDone, revertsDeferred;
    private static void applyLoadedReverts(MinecraftServer server,String dimension) {
        ServerLevel level=RedstoneBridge.level(server,dimension); Set<Long> set=reverts.get(dimension);
        if(level==null || set==null) return;
        int done=0, deferred=0;
        for(var it=set.iterator();it.hasNext();) {
            BlockPos pos=BlockPos.of(it.next());
            if(!(level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4) instanceof LevelChunk chunk)) { deferred++; continue; }
            // Through the level, as a block change: its boundary, its chunk's plain blocks and the clients follow.
            BlockState own=chunk.getBlockState(pos);
            if(SharedWorld.ownBlock(own,pos)) {
                // A block holding something (a machine, a chest) is broken with its contents dropped, not erased.
                if(own.hasBlockEntity()) level.destroyBlock(pos,true); else level.setBlock(pos,Blocks.AIR.defaultBlockState(),Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE);
                done++;
            }
            it.remove();
        }
        revertsDone+=done; revertsDeferred=deferred;
        if(done>0 || deferred>0) MmlfLog.sampled("cogen","reverts",2000,Map.of("removed",revertsDone,"waitingForChunks",deferred));
    }
    private static int backgroundTicks;
    /**
     * Background, server thread, every tick: once a second, rejected clears whose chunk was loaded but not yet answered
     * as complete when they arrived (its load had passed, so {@link #chunkLoaded} will not see them) are undone now.
     */
    static void backgroundTick(MinecraftServer server) {
        if(BridgeCore.isPrimary() || ++backgroundTicks%20!=0 || reverts.isEmpty()) return;
        for(var entry:reverts.entrySet()) if(!entry.getValue().isEmpty()) applyLoadedReverts(server,entry.getKey());
    }
    /** Background, server thread ({@code SharedWorld.loaded}): a chunk of this world loaded; rejected clears in it are undone. */
    static void chunkLoaded(LevelChunk chunk) {
        if(!backgroundDecorates()) return;
        ServerLevel level=(ServerLevel)chunk.getLevel(); ensureLoaded(level.getServer());
        Set<Long> set=reverts.get(dimension(level));
        if(set==null || set.isEmpty()) return;
        ChunkPos pos=chunk.getPos();
        for(var it=set.iterator();it.hasNext();) {
            BlockPos at=BlockPos.of(it.next());
            if((at.getX()>>4)!=pos.x || (at.getZ()>>4)!=pos.z) continue;
            if(SharedWorld.ownBlock(chunk.getBlockState(at),at)) removeOwn(chunk,at);
            it.remove();
        }
    }
    /** A chunk just loaded (not yet sent to clients): through the chunk, so the boundary records the change. */
    private static void removeOwn(LevelChunk chunk,BlockPos at) {
        chunk.setBlockState(at,Blocks.AIR.defaultBlockState(),false);
        chunk.setUnsaved(true);
    }

    /**
     * {@code ServerSaveMixin}, background, as its server stops: chunks still waiting for the primary's terrain go on
     * empty (the shutdown waits for every chunk in generation) and are never saved ({@link #unsaved}).
     */
    public static void serverStopping(MinecraftServer server) {
        if(BridgeCore.isPrimary()) return;
        for(var fill:Map.copyOf(fills).entrySet()) {
            unfilled.add(fill.getValue().key());
            fill.getKey().complete(fill.getValue().chunk());
        }
    }
    /** {@code ChunkMapMixin}: a background chunk without the primary's terrain is not saved, so it is generated again. */
    public static boolean unsaved(ServerLevel level,ChunkAccess chunk) {
        return !unfilled.isEmpty() && unfilled.contains(key(dimension(level),chunk.getPos().x,chunk.getPos().z));
    }

    // ---- recording ----

    /** One decoration's writes: per position its state before the first write and after the last. */
    private static final class Recording {
        final IdentityHashMap<LevelChunkSection,int[]> sections=new IdentityHashMap<>();
        final LinkedHashMap<Long,BlockState[]> writes=new LinkedHashMap<>();
    }
    private static final ThreadLocal<Recording> RECORDING=new ThreadLocal<>();
    /** {@code SectionWriteMixin}: a section is about to change; recorded while this thread decorates for the primary. */
    public static void sectionWrite(LevelChunkSection section,int x,int y,int z,BlockState state) {
        Recording recording=RECORDING.get(); if(recording==null) return;
        int[] base=recording.sections.get(section); if(base==null) return;
        long pos=BlockPos.asLong(base[0]+x,base[1]+y,base[2]+z);
        BlockState[] change=recording.writes.get(pos);
        if(change==null) recording.writes.put(pos,new BlockState[]{section.getBlockState(x,y,z),state});
        else change[1]=state;
    }
    /** Background: only its own mods' decoration runs; vanilla's is the primary's. */
    public static boolean skipsVanillaDecoration() { return RECORDING.get()!=null; }

    // ---- background persistence (with each save point) ----

    private static final String FILE="mmlf-cogen.json";
    private static com.google.gson.JsonObject readState(MinecraftServer server) {
        Path path=server.getWorldPath(LevelResource.ROOT).resolve(FILE);
        if(!Files.isRegularFile(path)) return null;
        try { return com.google.gson.JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch(Exception e) { MmlfLog.warn("cogen","state_load_failed",Map.of("exception",e.toString())); return null; }
    }
    private static void writeState(MinecraftServer server,com.google.gson.JsonObject json) {
        try {
            Path path=server.getWorldPath(LevelResource.ROOT).resolve(FILE);
            Path temporary=path.resolveSibling(FILE+".tmp");
            Files.writeString(temporary,json.toString(),StandardCharsets.UTF_8);
            Files.move(temporary,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch(Exception e) { MmlfLog.warn("cogen","state_save_failed",Map.of("exception",e.toString())); }
    }
    private static volatile MinecraftServer loadedFor;
    private static synchronized void ensureLoaded(MinecraftServer server) {
        if(server==null || loadedFor==server) return;
        loadedFor=server;
        var json=readState(server); if(json==null) return;
        if(json.has("pending")) for(var e:json.getAsJsonObject("pending").entrySet()) {
            var list=e.getValue().getAsJsonArray(); String[] out=new String[list.size()];
            for(int i=0;i<out.length;i++) out[i]=list.get(i).getAsString();
            pending.putIfAbsent(e.getKey(),out);
        }
        if(json.has("reverts")) for(var e:json.getAsJsonObject("reverts").entrySet()) {
            Set<Long> set=reverts.computeIfAbsent(e.getKey(),d->ConcurrentHashMap.newKeySet());
            for(var p:e.getValue().getAsJsonArray()) set.add(p.getAsLong());
        }
        MmlfLog.info("cogen","state_loaded",Map.of("undelivered",pending.size()));
    }
    /** Background, with each save point ({@code SaveSync}). */
    static void save(MinecraftServer server) {
        if(server==null || BridgeCore.isPrimary() || !backgroundDecorates()) return;
        var json=new com.google.gson.JsonObject();
        var p=new com.google.gson.JsonObject();
        pending.forEach((k,v)->{ var a=new com.google.gson.JsonArray(); for(String s:v) a.add(s); p.add(k,a); });
        json.add("pending",p);
        var r=new com.google.gson.JsonObject();
        reverts.forEach((k,v)->{ if(v.isEmpty()) return; var a=new com.google.gson.JsonArray(); v.forEach(a::add); r.add(k,a); });
        json.add("reverts",r);
        writeState(server,json);
    }
    /** Background: its world closed; the next world loads its own state. */
    static void worldClosed() {
        for(var fill:Map.copyOf(fills).entrySet()) fill.getKey().complete(fill.getValue().chunk());
        pending.clear(); reverts.clear(); unfilled.clear(); fillWaits.clear();
        synchronized(CoGeneration.class) { loadedFor=null; }
    }
}
