package dev.mmlf.bridge.render;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.mmlf.bridge.platform.Experiments;
import dev.mmlf.common.MmlfLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Submitter side of the unified render layer. The runtime that does not draw the scene meshes the sections holding
 * its own content with the vanilla {@link SectionCompiler}, straight from world data, whatever renderer it has
 * installed (Sodium and similar replace the vanilla scheduling, not the compiler). Recompiles follow world-data
 * changes on {@link ClientLevel} rather than renderer callbacks, which such renderers override.
 * <p>
 * Each block is drawn by exactly one runtime. While the render host draws this runtime's submissions
 * ({@link #replayLive()}), a section whose latest submission the host can draw in full ({@link #replayed}) leaves its
 * blocks out of this runtime's own section meshes; a section that could not be submitted in full stays native, and
 * the host draws nothing of it. The decision is per section: a change marks only that section for the native renderer
 * ({@code LevelRenderer.setSectionDirty}, which vanilla and replacements such as Sodium both honour), and nothing
 * ever falls back as a whole.
 */
public final class OwnedSectionCompiler {
    private OwnedSectionCompiler() {}
    private static final String PRODUCER="minecraft:sections";
    private static final int MAX_STARTS_PER_TICK=16, MAX_IN_FLIGHT=8;
    private static final long ACK_FRESH_NANOS=3_000_000_000L, MIN_SWITCH_NANOS=2_000_000_000L;

    // Main thread.
    private static final Set<Long> dirty=new LinkedHashSet<>();
    private static final Set<Long> submitted=new HashSet<>(), failed=new HashSet<>();
    private static Object scene;
    private static long sceneGeneration=-1, lastSwitch;
    private static boolean active;
    private static long compiled, removed, lastStats;
    // Any thread.
    private static final AtomicLong revisions=new AtomicLong();
    private static final AtomicInteger inFlight=new AtomicInteger();
    private static volatile boolean live;
    /** Sections whose latest submission the host draws in full (compile worker and main thread). */
    private static final Set<Long> replayed=ConcurrentHashMap.newKeySet();
    /** Sections whose membership in {@link #replayed} changed; their native meshes follow on the main thread. */
    private static final Queue<Long> nativeDirty=new ConcurrentLinkedQueue<>();
    private static long ackGeneration=-1, ackRevision=-1, ackNanos;
    private static boolean ackCovered;
    // Worker thread.
    private static ExecutorService worker;
    private static volatile Thread workerThread;
    private static SectionBufferBuilderPack pack;
    private static SectionCompiler compiler;

    /** Whether the current thread is this compiler's worker, whose meshes keep this runtime's own blocks. */
    public static boolean ownCompile() { return Thread.currentThread()==workerThread; }
    /** The render host currently draws this runtime's submissions (its scene generation, recently confirmed). */
    public static boolean replayLive() { return live; }
    /** Whether a block at {@code pos} is left out of this runtime's own section meshes: the host draws its section. */
    public static boolean hiddenInSection(net.minecraft.core.BlockPos pos) {
        return live && Thread.currentThread()!=workerThread && replayed.contains(SectionPos.asLong(pos));
    }
    /** Compile worker, {@code GeometryLayer.captured}: a submission of {@code section} was accepted. */
    static void submissionAccepted(long section,boolean complete) {
        if(complete ? replayed.add(section) : replayed.remove(section)) nativeDirty.add(section);
    }

    /** The render host confirms it draws this runtime's meshes of {@code generation}. */
    public static void acknowledged(long generation,long revision,boolean covered) {
        if(generation!=sceneGeneration || generation==ackGeneration && revision<ackRevision) return;
        ackGeneration=generation; ackRevision=revision; ackCovered=covered; ackNanos=System.nanoTime();
    }

    private static boolean wanted(Minecraft mc) {
        return Experiments.GEOMETRY && mc.level!=null && dev.mmlf.bridge.transport.SessionLink.session()!=null && dev.mmlf.bridge.transport.SessionLink.connected() && !GeometryLayer.isRenderHost();
    }

    /** Client tick, after {@link GeometryLayer} has settled the scene for this tick. */
    static void tick(Minecraft mc) {
        if(!wanted(mc)) { if(active) deactivate(mc); return; }
        long generation=GeometryLayer.sceneGeneration();
        if(generation<0) return;
        if(!active || scene!=mc.level || generation!=sceneGeneration) activate(mc,generation);
        updateNativeVisibility(mc,generation);
        for(Long key;(key=nativeDirty.poll())!=null;) if(live) markNative(mc,key);
        start(mc);
        long now=System.nanoTime();
        if(now-lastStats>10_000_000_000L) {
            lastStats=now;
            MmlfLog.info("render.submission","owned_sections",Map.of("submitted",submitted.size(),"replayed",replayed.size(),"failed",failed.size(),"pending",dirty.size(),"compiled",compiled,"removed",removed,"live",live));
        }
    }

    private static void activate(Minecraft mc,long generation) {
        if(worker==null) {
            worker=Executors.newSingleThreadExecutor(r->{ Thread t=new Thread(r,"mmlf-owned-sections"); t.setDaemon(true); workerThread=t; return t; });
            compiler=new SectionCompiler(mc.getBlockRenderer(),mc.getBlockEntityRenderDispatcher());
        }
        boolean newScene=scene!=mc.level;
        scene=mc.level; sceneGeneration=generation; active=true;
        dirty.clear(); submitted.clear(); failed.clear(); nativeDirty.clear();
        if(live) setLive(mc,false,newScene?"scene_changed":"generation_changed");
        replayed.clear();
        // Chunks already loaded before activation, or kept across a scene reset, produce no load event.
        int radius=mc.options.getEffectiveRenderDistance()+1;
        var center=mc.gameRenderer.getMainCamera().getBlockPosition();
        int cx=SectionPos.blockToSectionCoord(center.getX()),cz=SectionPos.blockToSectionCoord(center.getZ());
        for(int x=cx-radius;x<=cx+radius;x++) for(int z=cz-radius;z<=cz+radius;z++) scanChunk(mc.level,x,z);
        MmlfLog.info("render.submission","owned_sections_scan",Map.of("generation",generation,"sections",dirty.size(),"radius",radius));
    }

    private static void deactivate(Minecraft mc) {
        active=false; scene=null; sceneGeneration=-1; dirty.clear(); submitted.clear(); failed.clear(); nativeDirty.clear();
        if(live) setLive(mc,false,"inactive");
        replayed.clear();
    }

    private static void updateNativeVisibility(Minecraft mc,long generation) {
        long now=System.nanoTime();
        // Only whether the host draws this scene at all: which sections it draws is known here, per section.
        boolean confirmed=Experiments.GEOMETRY_REPLAY && ackCovered && ackGeneration==generation && now-ackNanos<ACK_FRESH_NANOS
            && !dev.mmlf.bridge.world.Worlds.backgroundLeaving();
        // Losing it brings the replayed sections back at once; taking them out again waits, so a flapping link
        // cannot rebuild them over and over.
        if(confirmed==live || confirmed && now-lastSwitch<MIN_SWITCH_NANOS) return;
        setLive(mc,confirmed,confirmed?"render_host_drawing":"render_host_lost");
    }
    private static void setLive(Minecraft mc,boolean value,String reason) {
        live=value; lastSwitch=System.nanoTime();
        if(!value) DynamicCapture.forgetExported();
        MmlfLog.info("render.submission","native_sections",Map.of("live",value,"replayed",replayed.size(),"reason",reason));
        // Only the sections the host draws change; the others keep their native meshes as they are.
        if(mc.level!=null) for(long key:replayed) markNative(mc,key);
    }
    /** A generic renderer API: vanilla and replacements such as Sodium rebuild their own mesh of this section. */
    private static void markNative(Minecraft mc,long key) {
        if(mc.level!=null) mc.levelRenderer.setSectionDirty(SectionPos.x(key),SectionPos.y(key),SectionPos.z(key));
    }

    // ---- world-data hooks (main thread, from ClientLevelMixin) ----

    public static void chunkLoaded(ClientLevel level,ChunkPos pos) {
        if(!active || level!=scene) return;
        scanChunk(level,pos.x,pos.z);
        // Faces at the border towards a newly loaded chunk may now be culled.
        for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) markSubmittedColumn(level,pos.x+d[0],pos.z+d[1]);
    }
    public static void chunkUnloaded(ClientLevel level,LevelChunk chunk) {
        if(!active || level!=scene) return;
        ChunkPos pos=chunk.getPos();
        for(int i=0;i<level.getSectionsCount();i++) {
            long key=SectionPos.asLong(pos.x,level.getSectionYFromSectionIndex(i),pos.z);
            dirty.remove(key); removeSubmission(key);
        }
    }
    public static void blockChanged(ClientLevel level,BlockPos pos) {
        if(!active || level!=scene) return;
        // As vanilla's setBlockDirty: the neighbouring sections' culling and smooth lighting may change too.
        for(int x=-1;x<=1;x++) for(int y=-1;y<=1;y++) for(int z=-1;z<=1;z++)
            dirty.add(SectionPos.asLong(SectionPos.blockToSectionCoord(pos.getX()+x),SectionPos.blockToSectionCoord(pos.getY()+y),SectionPos.blockToSectionCoord(pos.getZ()+z)));
    }
    public static void sectionChanged(ClientLevel level,int x,int y,int z) {
        if(!active || level!=scene) return;
        for(int dx=-1;dx<=1;dx++) for(int dy=-1;dy<=1;dy++) for(int dz=-1;dz<=1;dz++) dirty.add(SectionPos.asLong(x+dx,y+dy,z+dz));
    }
    /** Render thread, each frame in one JVM: compiles start as soon as a change is known, not at the next client tick. */
    static void frame(Minecraft mc) { if(active && mc.level==scene) start(mc); }
    /** A submission was rejected because the scene was being reset; compile it again in the current scene. */
    static void recompile(long key) { if(active) dirty.add(key); }

    private static void scanChunk(ClientLevel level,int x,int z) {
        LevelChunk chunk=level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
        if(chunk==null) return;
        for(int i=0;i<level.getSectionsCount();i++) {
            int y=level.getSectionYFromSectionIndex(i);
            if(hasOwned(level,chunk.getSection(i),x,y,z)) dirty.add(SectionPos.asLong(x,y,z));
        }
    }
    private static void markSubmittedColumn(ClientLevel level,int x,int z) {
        for(int i=0;i<level.getSectionsCount();i++) {
            long key=SectionPos.asLong(x,level.getSectionYFromSectionIndex(i),z);
            if(submitted.contains(key)) dirty.add(key);
        }
    }

    /** Cheap palette test with the same rule as {@link BridgeEntry#renderOwned}; false positives only cost a compile. */
    static boolean hasOwned(ClientLevel level,LevelChunkSection section,int x,int y,int z) {
        if(section.hasOnlyAir()) return false;
        if(!RenderOwnership.filterBlocks()) return true;
        if(section.maybeHas(OwnedSectionCompiler::foreign)) return true;
        for(BlockPos pos:dev.mmlf.bridge.collab.RenderInputs.ownedVanillaPositions(level))
            if(SectionPos.blockToSectionCoord(pos.getX())==x && SectionPos.blockToSectionCoord(pos.getY())==y && SectionPos.blockToSectionCoord(pos.getZ())==z) return true;
        return false;
    }
    private static boolean foreign(BlockState state) {
        if(state.isAir()) return false;
        if(!BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft")) return true;
        var fluid=state.getFluidState();
        return !fluid.isEmpty() && !BuiltInRegistries.FLUID.getKey(fluid.getType()).getNamespace().equals("minecraft");
    }

    private static void removeSubmission(long key) {
        failed.remove(key);
        if(replayed.remove(key)) nativeDirty.add(key);
        if(!submitted.remove(key)) return;
        if(GeometryLayer.removeVersioned(sceneGeneration,PRODUCER,key,revisions.incrementAndGet())) removed++;
    }

    private static void start(Minecraft mc) {
        if(dirty.isEmpty()) return;
        ClientLevel level=mc.level;
        var cache=new RenderRegionCache();
        var camera=mc.gameRenderer.getMainCamera().getPosition();
        int started=0;
        for(var it=dirty.iterator();it.hasNext() && started<MAX_STARTS_PER_TICK && inFlight.get()<MAX_IN_FLIGHT;) {
            long key=it.next(); it.remove();
            int x=SectionPos.x(key),y=SectionPos.y(key),z=SectionPos.z(key);
            int index=level.getSectionIndexFromSectionY(y);
            LevelChunk chunk=index<0 || index>=level.getSectionsCount() ? null : level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
            if(chunk==null || !hasOwned(level,chunk.getSection(index),x,y,z)) { removeSubmission(key); continue; }
            SectionPos section=SectionPos.of(x,y,z);
            RenderChunkRegion region=cache.createRegion(level,section);
            if(region==null) { removeSubmission(key); continue; }
            long revision=revisions.incrementAndGet(), generation=sceneGeneration;
            var sorting=VertexSorting.byDistance((float)(camera.x-section.minBlockX()),(float)(camera.y-section.minBlockY()),(float)(camera.z-section.minBlockZ()));
            submitted.add(key); started++; compiled++;
            inFlight.incrementAndGet();
            Object source=level;
            try {
                worker.execute(()->{
                    try {
                        if(pack==null) pack=new SectionBufferBuilderPack();
                        var results=compiler.compile(section,region,sorting,pack);
                        try {
                            GeometryLayer.captured(source,generation,section,revision,results.renderedLayers);
                            mc.execute(()->{ if(scene==source && sceneGeneration==generation) failed.remove(key); });
                        }
                        finally { results.release(); }
                    } catch(RuntimeException | LinkageError e) {
                        MmlfLog.sampled("render.submission","owned_compile_failed",5000,Map.of("section",section.toString(),"exception",e.toString()));
                        mc.execute(()->{
                            if(scene==source && sceneGeneration==generation) {
                                failed.add(key);
                                if(replayed.remove(key)) nativeDirty.add(key);
                                dirty.add(key);
                            }
                        });
                    } finally { inFlight.decrementAndGet(); }
                });
            } catch(RejectedExecutionException e) {
                inFlight.decrementAndGet();
                failed.add(key);
                mc.execute(()->{ if(scene==source && sceneGeneration==generation) dirty.add(key); });
            }
        }
    }
}
