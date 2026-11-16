package dev.mmlf.bridge.render;

import dev.mmlf.common.RenderSectionStore;
import dev.mmlf.common.TerrainQuadPartition;
import java.nio.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable batches for terrain model compilation, published only after a complete snapshot is built. Sections are
 * rebuilt and marked dirty only when their received layers, their stand-in cells or the reserved atlas area change, so
 * one remote block change recompiles one host section.
 */
public final class TerrainMeshes {
    private TerrainMeshes() {}
    private static final ResourceLocation RESERVE=ResourceLocation.fromNamespaceAndPath("mmlf_bridge","remote_reserve");
    /** Where the received atlas lands in the local one; any change invalidates every converted quad. */
    private record AtlasKey(float u0,float v0,float u1,float v1,int spriteWidth,int spriteHeight,int width,int height) {}
    /** One section's inputs and converted quads; {@code version} is the state version in which its content last changed. */
    private record Section(List<RenderSectionStore.Layer> layers,List<TerrainQuadPartition.Cell> occupied,AtlasKey atlas,
                           Map<Long,Map<String,List<BakedQuad>>> cells,long version) {}
    private record State(long version,Map<Long,Section> sections) {}
    private static volatile State state=new State(0,Map.of());
    private static volatile boolean dirty;
    /** Sections whose received layers or stand-in cells changed since the last refresh; the others are kept as they are. */
    private static final Set<Long> dirtySections=java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Everything is rebuilt (a new scene, a changed atlas). */
    public static void invalidate() { dirty=true; }
    /** Only these sections ({@link SectionPos#asLong}) are rebuilt. */
    public static void invalidateSections(Collection<Long> sections) { dirtySections.addAll(sections); }
    /** The section holding this block is rebuilt (a stand-in written or removed there). */
    public static void invalidateBlock(BlockPos pos) { dirtySections.add(SectionPos.asLong(pos)); }
    /** A client chunk arrived with its stand-ins: its sections are rebuilt. */
    public static void chunkLoaded(ClientLevel level,net.minecraft.world.level.ChunkPos pos) {
        for(int y=level.getMinSection();y<level.getMaxSection();y++) dirtySections.add(SectionPos.asLong(pos.x,y,pos.z));
    }
    static void refresh(Minecraft mc,RenderSectionStore.Snapshot snapshot,int width,int height) {
        if(!TerrainModels.enabled() || snapshot==null) return;
        confirmPredictions(mc);
        Set<Long> only=null;
        if(dirty) { dirty=false; dirtySections.clear(); }
        else {
            if(dirtySections.isEmpty()) return;
            only=new HashSet<>();
            for(var it=dirtySections.iterator();it.hasNext();) { only.add(it.next()); it.remove(); }
        }
        if(!TerrainAtlas.ready || !rebuild(mc,snapshot,width,height,only)) clear();
    }
    /**
     * Diagnosis (moire on remote glass): quads at the same corners twice in a section's received layers, which would
     * fight for the same depth. Logged per section with the cells they fall in.
     */
    private static void reportDuplicates(int ox,int oy,int oz,List<RenderSectionStore.Layer> layers,List<TerrainQuadPartition.Cell> occupied) {
        var seen=new HashMap<String,String>(); var where=new java.util.TreeSet<String>(); int duplicates=0;
        for(var layer:layers) {
            byte[] v=layer.vertices(); if(v.length%128!=0) continue;
            var data=java.nio.ByteBuffer.wrap(v).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for(int offset=0;offset<v.length;offset+=128) {
                float[][] corners=new float[4][];
                for(int i=0;i<4;i++) { int at=offset+i*32; corners[i]=new float[]{Math.round(data.getFloat(at)*1000)/1000f,Math.round(data.getFloat(at+4)*1000)/1000f,Math.round(data.getFloat(at+8)*1000)/1000f}; }
                java.util.Arrays.sort(corners,(a,b)->a[0]!=b[0]?Float.compare(a[0],b[0]):a[1]!=b[1]?Float.compare(a[1],b[1]):Float.compare(a[2],b[2]));
                String key=java.util.Arrays.deepToString(corners);
                String before=seen.putIfAbsent(key,layer.renderType());
                if(before!=null) {
                    duplicates++;
                    where.add((ox+(int)Math.floor(corners[0][0]))+","+(oy+(int)Math.floor(corners[0][1]))+","+(oz+(int)Math.floor(corners[0][2]))+" "+before+"/"+layer.renderType());
                }
            }
        }
        if(duplicates>0) dev.mmlf.common.MmlfLog.info("render.submission","terrain_duplicate_quads",Map.of("section",ox+","+oy+","+oz,"duplicates",duplicates,
            "at",where.stream().limit(12).toList(),"layers",layers.stream().map(l->l.renderType()+":"+l.vertices().length/128).toList()));
    }
    /** Diagnosis (remote glass not blending): the layers each remote section arrives with, once per change. */
    private static final Map<Long,String> reportedLayers=new java.util.concurrent.ConcurrentHashMap<>();
    private static void reportLayers(long key,int ox,int oy,int oz,List<RenderSectionStore.Layer> layers) {
        String summary=layers.stream().map(l->l.renderType()+":"+l.vertices().length/128).sorted().toList().toString();
        if(summary.equals(reportedLayers.put(key,summary))) return;
        dev.mmlf.common.MmlfLog.info("render.submission","terrain_layers",Map.of("section",ox+","+oy+","+oz,"layers",summary));
    }
    public static Map<String,List<BakedQuad>> at(BlockPos pos) {
        Section section=state.sections().get(SectionPos.asLong(SectionPos.blockToSectionCoord(pos.getX()),SectionPos.blockToSectionCoord(pos.getY()),SectionPos.blockToSectionCoord(pos.getZ())));
        return section==null ? Map.of() : section.cells().getOrDefault(pos.asLong(),Map.of());
    }
    /** Section -> content version whose compile result the host has uploaded. */
    private static final java.util.concurrent.ConcurrentMap<Long,Long> uploaded=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Object,Upload> sodiumOutputs=Collections.synchronizedMap(new WeakHashMap<>());
    private record Upload(long section,long version) {}
    /** Taken by a compile task before it reads {@link #at}; passed back to {@link #uploaded} with its result. */
    public static long version() { return state.version(); }
    /**
     * Whether the section's remote blocks are drawn as terrain: some compile of it is uploaded. While a newer content
     * version is being compiled the previous upload keeps showing; drawing the fallback meshes on top of it as well
     * made every remote block of the section flicker.
     */
    public static boolean hasSection(long section) { return state.sections().containsKey(section) && uploaded.containsKey(section); }
    /**
     * A terrain particle at a remote block (its stand-in at {@code pos}, or below it: landing and running particles
     * name the position above the ground): the atlas bounds {u0,v0,u1,v1} of one of its converted quads, preferring the
     * top face; null where none is known.
     */
    public static float[] particleBounds(ClientLevel level,BlockPos pos) {
        for(BlockPos at:new BlockPos[]{pos,pos.below()}) {
            if(!dev.mmlf.bridge.collab.RenderInputs.isStandIn(level.getBlockState(at))) continue;
            BakedQuad chosen=null;
            for(var quads:at(at).values()) for(BakedQuad quad:quads) if(chosen==null || quad.getDirection()==Direction.UP && chosen.getDirection()!=Direction.UP) chosen=quad;
            if(chosen==null) return null;
            int[] data=chosen.getVertices();
            float u0=Float.MAX_VALUE,v0=Float.MAX_VALUE,u1=-Float.MAX_VALUE,v1=-Float.MAX_VALUE;
            for(int i=0;i<4;i++) {
                float u=Float.intBitsToFloat(data[i*8+4]),v=Float.intBitsToFloat(data[i*8+5]);
                u0=Math.min(u0,u); u1=Math.max(u1,u); v0=Math.min(v0,v); v1=Math.max(v1,v);
            }
            return new float[]{u0,v0,u1,v1};
        }
        return null;
    }
    /** A compile that started at {@code compiledAt} holds the section's current content when that content is not newer. */
    public static void uploaded(long section,long compiledAt) {
        Section s=state.sections().get(section);
        if(s!=null && compiledAt>=s.version() && !Objects.equals(uploaded.put(section,s.version()),s.version()))
            dev.mmlf.common.MmlfLog.info("render.submission","terrain_uploaded",Map.of("section",section,"version",s.version()));
    }
    public static void sodiumOutput(Object output,long section,long version) { if(output!=null) sodiumOutputs.put(output,new Upload(section,version)); }
    public static void sodiumUploaded(Collection<?> outputs) { for(Object output:outputs) { Upload u=sodiumOutputs.remove(output); if(u!=null) uploaded(u.section(),u.version()); } }
    public static void clear() {
        var old=state.sections().keySet(); state=new State(state.version()+1,Map.of()); uploaded.clear();
        var mc=Minecraft.getInstance();
        if(mc.level!=null) for(long key:old) mc.levelRenderer.setSectionDirty(SectionPos.x(key),SectionPos.y(key),SectionPos.z(key));
    }

    /**
     * Render thread; returns false without changing the published batches when the atlas cannot fit. {@code only}: the
     * sections to rebuild, the others kept; null rebuilds all.
     */
    static boolean rebuild(Minecraft mc,RenderSectionStore.Snapshot snapshot,int width,int height,Set<Long> only) {
        long started=System.nanoTime();
        ClientLevel level=mc.level;
        if(level==null) return false;
        var texture=mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if(!(texture instanceof TextureAtlas atlas)) return false;
        var sprite=((dev.mmlf.bridge.mixin.TextureAtlasAccessor)atlas).mmlf$sprites().get(RESERVE);
        if(sprite==null || width>sprite.contents().width() || height>sprite.contents().height()) return false;
        var atlasKey=new AtlasKey(sprite.getU0(),sprite.getV0(),sprite.getU1(),sprite.getV1(),sprite.contents().width(),sprite.contents().height(),width,height);
        State previous=state;
        long version=previous.version()+1;
        // A section kept as it is must have been converted for this atlas placement.
        if(only!=null) for(Section kept:previous.sections().values()) if(!kept.atlas().equals(atlasKey)) { only=null; break; }
        final Set<Long> selected=only;
        // Producers in a fixed order, so an unchanged section compares equal to its previous layers.
        var grouped=new HashMap<Long,List<RenderSectionStore.Layer>>();
        snapshot.sections().entrySet().stream().filter(e->selected==null || selected.contains(e.getKey().section()))
                .sorted(Comparator.comparing(e->e.getKey().producer()))
                .forEach(e->grouped.computeIfAbsent(e.getKey().section(),k->new ArrayList<>()).addAll(e.getValue().layers()));
        var next=new HashMap<Long,Section>();
        if(selected!=null) { next.putAll(previous.sections()); next.keySet().removeAll(selected); }
        var changed=new HashSet<Long>();
        int reused=0;
        net.minecraft.world.phys.Vec3 camera=mc.gameRenderer.getMainCamera().getPosition();
        var predicted=new ArrayList<BlockPos>();
        // A few sections changing is a block placed or broken; a full rebuild or a batch predicts no removals.
        boolean incremental=selected!=null && selected.size()<=URGENT_SECTIONS;
        var vanished=new ArrayList<BlockPos>();
        for(var entry:grouped.entrySet()) {
            long key=entry.getKey(); int sx=SectionPos.x(key),sy=SectionPos.y(key),sz=SectionPos.z(key);
            if(!level.hasChunk(sx,sz)) continue;
            int ox=sx*16,oy=sy*16,oz=sz*16;
            var occupied=new ArrayList<TerrainQuadPartition.Cell>();
            var pos=new BlockPos.MutableBlockPos();
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++)
                if(dev.mmlf.bridge.collab.RenderInputs.isStandIn(level.getBlockState(pos.set(ox+x,oy+y,oz+z)))) occupied.add(new TerrainQuadPartition.Cell(x,y,z));
            var layers=entry.getValue();
            Section old=previous.sections().get(key);
            predict(level,camera,ox,oy,oz,layers,old,occupied,predicted);
            if(incremental) predictRemoved(level,ox,oy,oz,layers,old,occupied,vanished);
            if(occupied.isEmpty()) continue;
            if(old!=null && old.atlas().equals(atlasKey) && old.occupied().equals(occupied) && RenderSectionStore.sameLayers(old.layers(),layers)) {
                next.put(key,old); reused++; continue;
            }
            boolean supported=layers.stream().allMatch(l->l.vertexFormat().equals("minecraft:block-v1") && l.vertexStride()==32
                && l.topology().equals("quads") && l.material().equals("minecraft:block-atlas") && l.meshId().isEmpty()
                && l.indices().length==0 && Set.of("solid","cutout_mipped","cutout","translucent","tripwire").contains(l.renderType()));
            if(!supported) continue;
            var local=new HashMap<Long,Map<String,List<BakedQuad>>>();
            try {
                for(var layer:layers) for(var e:TerrainQuadPartition.partition(layer.vertices(),occupied).entrySet()) {
                    var c=e.getKey(); long block=BlockPos.asLong(ox+c.x(),oy+c.y(),oz+c.z());
                    var quads=local.computeIfAbsent(block,k->new HashMap<>()).computeIfAbsent(layer.renderType(),k->new ArrayList<>());
                    for(byte[] quad:e.getValue()) quads.add(convert(quad,c,sprite,width,height));
                }
            } catch(IllegalArgumentException e) { continue; }
            reportDuplicates(ox,oy,oz,layers,occupied);
            reportLayers(key,ox,oy,oz,layers);
            var frozen=new HashMap<Long,Map<String,List<BakedQuad>>>();
            local.forEach((block,passes)->{ var p=new HashMap<String,List<BakedQuad>>(); passes.forEach((pass,quads)->p.put(pass,List.copyOf(quads))); frozen.put(block,Map.copyOf(p)); });
            next.put(key,new Section(List.copyOf(layers),List.copyOf(occupied),atlasKey,Map.copyOf(frozen),version));
            changed.add(key);
        }
        var removed=new HashSet<>(previous.sections().keySet()); removed.removeAll(next.keySet());
        if(selected!=null) removed.retainAll(selected);
        // A section whose last remote block went has no geometry left at all.
        if(incremental) for(long key:removed) if(!grouped.containsKey(key))
            predictRemoved(level,SectionPos.x(key)*16,SectionPos.y(key)*16,SectionPos.z(key)*16,List.of(),previous.sections().get(key),new ArrayList<>(),vanished);
        if(changed.isEmpty() && removed.isEmpty()) return true;
        state=new State(version,Map.copyOf(next));
        for(long key:removed) uploaded.remove(key);
        int cells=next.values().stream().mapToInt(s->s.cells().size()).sum();
        dev.mmlf.common.MmlfLog.sampled("render.submission","terrain_meshes",1000,Map.of("sections",next.size(),"cells",cells,"version",version,"partial",selected!=null,
                "considered",grouped.size(),"changed",changed.size(),"removed",removed.size(),"reused",reused,"rebuildMicros",(System.nanoTime()-started)/1000));
        var dirty=new HashSet<>(removed); dirty.addAll(changed);
        // A few sections changed (a block placed or broken, not a batch arriving): compiled before the next frame when
        // near the camera, as vanilla does for the player's own changes, instead of some frames later on a worker.
        boolean urgent=selected!=null && dirty.size()<=URGENT_SECTIONS;
        for(long key:dirty) {
            int x=SectionPos.x(key),y=SectionPos.y(key),z=SectionPos.z(key);
            boolean near=urgent && camera.distanceToSqr(x*16+8,y*16+8,z*16+8)<NEAR_SQR;
            if(near) ((dev.mmlf.bridge.mixin.LevelRendererInvoker)mc.levelRenderer).mmlf$setSectionDirty(x,y,z,true);
            else mc.levelRenderer.setSectionDirty(x,y,z);
        }
        placePredicted(level,predicted);
        placeRemoved(level,vanished);
        return true;
    }

    // ---- render stand-ins: what shows a remote block is its geometry, on every renderer ----

    /** Within this distance a predicted stand-in is compiled before the next frame, as vanilla's own placements are. */
    private static final double IMMEDIATE_RANGE_SQR=24*24;
    /** A predicted stand-in the primary's server has not confirmed by then is replaced by what the server holds. */
    private static final long PREDICT_CONFIRM_NANOS=1_000_000_000L;
    /** {@code removal}: a stand-in taken out ahead of the server, else one put in. */
    private record Prediction(BlockPos pos,String dimension,long nanos,boolean removal) {}
    private static final List<Prediction> predictions=new ArrayList<>();
    /**
     * Whether a remote block shows depends on its geometry alone; the server's stand-in is there for collision, light
     * and the other runtimes' logic. A block another runtime has just placed arrives here as geometry before its
     * stand-in arrives through the primary's server (the owner's report, the primary's check, the block update): the
     * client world still has air there, and renderers (vanilla, Sodium, shader packs) only compile blocks, so the
     * client world takes a stand-in there at once, drawn as any other through {@link TerrainModels}, wherever it is
     * in the loaded area. Only cells new in this section's geometry: a cell that was already there and is now air is
     * a block being removed. The server's stand-in replaces the prediction when it comes; one it never confirms is
     * undone ({@link #confirmPredictions}).
     */
    private static void predict(ClientLevel level,net.minecraft.world.phys.Vec3 camera,int ox,int oy,int oz,List<RenderSectionStore.Layer> layers,
                                Section old,List<TerrainQuadPartition.Cell> occupied,List<BlockPos> predicted) {
        var cells=new HashSet<TerrainQuadPartition.Cell>();
        try { for(var layer:layers) cells.addAll(TerrainQuadPartition.cellsOf(layer.vertices())); }
        catch(IllegalArgumentException e) { return; }
        boolean added=false;
        var pos=new BlockPos.MutableBlockPos();
        for(var cell:cells) {
            pos.set(ox+cell.x(),oy+cell.y(),oz+cell.z());
            if(old!=null && old.cells().containsKey(pos.asLong())) continue;
            if(!level.getBlockState(pos).isAir() || occupied.contains(cell)) continue;
            occupied.add(cell); predicted.add(pos.immutable()); added=true;
        }
        // The order the stand-in scan gives, so the section compares equal once the predicted cells are stand-ins.
        if(added) occupied.sort(Comparator.comparingInt(TerrainQuadPartition.Cell::y).thenComparingInt(TerrainQuadPartition.Cell::z).thenComparingInt(TerrainQuadPartition.Cell::x));
    }
    private static void placePredicted(ClientLevel level,List<BlockPos> predicted) {
        if(predicted.isEmpty()) return;
        String dimension=level.dimension().location().toString();
        long now=System.nanoTime();
        var camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        for(BlockPos at:predicted) {
            if(!level.getBlockState(at).isAir()) continue;
            var standIn=dev.mmlf.bridge.collab.RenderInputs.reportedStandIn(dimension,at);
            // Not reported yet: occluding nothing until the owner's report says otherwise. A guessed solid stand-in
            // would cull the faces next to it (the top of the block below a remote block just placed, or below the part
            // of a model reaching into a neighbouring cell, such as a sliding door's panel) with nothing drawn there.
            if(standIn==null) standIn=dev.mmlf.bridge.collab.RenderInputs.standIn(false,0);
            if(standIn==null) return; // not registered
            // Near the player as a predicted placement, compiled before the next frame; farther away on the workers.
            boolean near=camera.distanceToSqr(at.getX()+.5,at.getY()+.5,at.getZ()+.5)<IMMEDIATE_RANGE_SQR;
            level.setBlock(at,standIn,near ? net.minecraft.world.level.block.Block.UPDATE_ALL_IMMEDIATE : net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            predictions.add(new Prediction(at,dimension,now,false));
        }
        dev.mmlf.common.MmlfLog.sampled("render.submission","terrain_predicted",1000,Map.of("cells",predicted.size(),"waiting",predictions.size()));
    }
    /**
     * The other direction: a remote block that had faces in this section's geometry has none now, and the client world
     * still holds its stand-in. The stand-in shows nothing any more but still occludes, so the faces of the blocks next
     * to it (the primary's block below a broken remote one) stay culled until the owner's report, the primary's check
     * and the block update have gone through. The client world takes air there at once; one the server does not
     * confirm is undone ({@link #confirmPredictions}). Cells without faces before (enclosed blocks) are never touched.
     */
    private static void predictRemoved(ClientLevel level,int ox,int oy,int oz,List<RenderSectionStore.Layer> layers,
                                       Section old,List<TerrainQuadPartition.Cell> occupied,List<BlockPos> vanished) {
        if(old==null || old.cells().isEmpty()) return;
        var cells=new HashSet<TerrainQuadPartition.Cell>();
        try { for(var layer:layers) cells.addAll(TerrainQuadPartition.cellsOf(layer.vertices())); }
        catch(IllegalArgumentException e) { return; }
        var pos=new BlockPos.MutableBlockPos();
        for(long packed:old.cells().keySet()) {
            pos.set(packed);
            var cell=new TerrainQuadPartition.Cell(pos.getX()-ox,pos.getY()-oy,pos.getZ()-oz);
            if(cells.contains(cell) || !dev.mmlf.bridge.collab.RenderInputs.isStandIn(level.getBlockState(pos))) continue;
            occupied.remove(cell); vanished.add(pos.immutable());
        }
    }
    private static void placeRemoved(ClientLevel level,List<BlockPos> vanished) {
        if(vanished.isEmpty()) return;
        String dimension=level.dimension().location().toString();
        long now=System.nanoTime();
        var camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        var air=net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for(BlockPos at:vanished) {
            boolean near=camera.distanceToSqr(at.getX()+.5,at.getY()+.5,at.getZ()+.5)<IMMEDIATE_RANGE_SQR;
            level.setBlock(at,air,near ? net.minecraft.world.level.block.Block.UPDATE_ALL_IMMEDIATE : net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            predictions.add(new Prediction(at,dimension,now,true));
        }
        dev.mmlf.common.MmlfLog.sampled("render.submission","terrain_removal_predicted",1000,Map.of("cells",vanished.size(),"waiting",predictions.size()));
    }
    /**
     * Render thread: predictions older than {@link #PREDICT_CONFIRM_NANOS} are checked against the primary's server,
     * in this JVM; where it holds no stand-in, it sends the client what it holds, which undoes the prediction.
     */
    static void confirmPredictions(Minecraft mc) {
        if(predictions.isEmpty()) return;
        long now=System.nanoTime();
        var server=mc.getSingleplayerServer();
        var due=new ArrayList<Prediction>();
        for(var it=predictions.iterator();it.hasNext();) {
            Prediction p=it.next();
            if(now-p.nanos()<PREDICT_CONFIRM_NANOS) continue;
            it.remove();
            if(server==null || mc.level==null) continue;
            // Still as predicted here: the server is asked whether it agrees.
            boolean standIn=dev.mmlf.bridge.collab.RenderInputs.isStandIn(mc.level.getBlockState(p.pos()));
            if(p.removal() ? mc.level.getBlockState(p.pos()).isAir() : standIn) due.add(p);
        }
        if(due.isEmpty()) return;
        // One server task for all of them.
        server.execute(()->{
            int undone=0;
            for(Prediction p:due) {
                var level=dev.mmlf.bridge.collab.RenderInputs.serverLevel(server,p.dimension());
                if(level==null || !level.isLoaded(p.pos())) continue;
                // Agreed: a placement it holds a stand-in for, a removal it holds none for.
                if(dev.mmlf.bridge.collab.RenderInputs.isStandIn(level.getBlockState(p.pos()))!=p.removal()) continue;
                level.getChunkSource().blockChanged(p.pos());
                undone++;
            }
            if(undone>0) dev.mmlf.common.MmlfLog.info("render.submission","terrain_prediction_undone",Map.of("cells",undone,"checked",due.size()));
        });
    }
    /** The host left its world: predictions of it are dropped (the client world they were made in is gone). */
    public static void forgetPredictions() { predictions.clear(); }
    /** At most this many changed sections count as one change the player is waiting to see. */
    private static final int URGENT_SECTIONS=8;
    /** Vanilla's distance (squared, camera to section centre) within which a player's change compiles at once. */
    private static final double NEAR_SQR=768;
    private static BakedQuad convert(byte[] bytes,TerrainQuadPartition.Cell cell,TextureAtlasSprite sprite,int width,int height) {
        var in=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int[] data=new int[32];
        float uScale=(sprite.getU1()-sprite.getU0())*width/sprite.contents().width();
        float vScale=(sprite.getV1()-sprite.getV0())*height/sprite.contents().height();
        for(int i=0;i<4;i++) {
            int o=i*32,j=i*8;
            data[j]=Float.floatToRawIntBits(in.getFloat(o)-cell.x());
            data[j+1]=Float.floatToRawIntBits(in.getFloat(o+4)-cell.y());
            data[j+2]=Float.floatToRawIntBits(in.getFloat(o+8)-cell.z());
            data[j+3]=in.getInt(o+12);
            data[j+4]=Float.floatToRawIntBits(sprite.getU0()+in.getFloat(o+16)*uScale);
            data[j+5]=Float.floatToRawIntBits(sprite.getV0()+in.getFloat(o+20)*vScale);
            data[j+6]=0; data[j+7]=in.getInt(o+28);
        }
        float nx=in.get(28),ny=in.get(29),nz=in.get(30);
        Direction direction=nx==0&&ny==0&&nz==0 ? Direction.UP : Direction.getNearest(nx,ny,nz);
        return new BakedQuad(data,-1,direction,sprite,false);
    }
}
