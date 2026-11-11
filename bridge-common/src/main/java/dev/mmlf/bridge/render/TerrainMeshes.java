package dev.mmlf.bridge.render;

import dev.mmlf.bridge.StandInBlock;
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
        Set<Long> only=null;
        if(dirty) { dirty=false; dirtySections.clear(); }
        else {
            if(dirtySections.isEmpty()) return;
            only=new HashSet<>();
            for(var it=dirtySections.iterator();it.hasNext();) { only.add(it.next()); it.remove(); }
        }
        if(!TerrainAtlas.ready || !rebuild(mc,snapshot,width,height,only)) clear();
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
            if(!StandInBlock.is(level.getBlockState(at))) continue;
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
        for(var entry:grouped.entrySet()) {
            long key=entry.getKey(); int sx=SectionPos.x(key),sy=SectionPos.y(key),sz=SectionPos.z(key);
            if(!level.hasChunk(sx,sz)) continue;
            int ox=sx*16,oy=sy*16,oz=sz*16;
            var occupied=new ArrayList<TerrainQuadPartition.Cell>();
            var pos=new BlockPos.MutableBlockPos();
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++)
                if(StandInBlock.is(level.getBlockState(pos.set(ox+x,oy+y,oz+z)))) occupied.add(new TerrainQuadPartition.Cell(x,y,z));
            if(occupied.isEmpty()) continue;
            var layers=entry.getValue();
            Section old=previous.sections().get(key);
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
            var frozen=new HashMap<Long,Map<String,List<BakedQuad>>>();
            local.forEach((block,passes)->{ var p=new HashMap<String,List<BakedQuad>>(); passes.forEach((pass,quads)->p.put(pass,List.copyOf(quads))); frozen.put(block,Map.copyOf(p)); });
            next.put(key,new Section(List.copyOf(layers),List.copyOf(occupied),atlasKey,Map.copyOf(frozen),version));
            changed.add(key);
        }
        var removed=new HashSet<>(previous.sections().keySet()); removed.removeAll(next.keySet());
        if(selected!=null) removed.retainAll(selected);
        if(changed.isEmpty() && removed.isEmpty()) return true;
        state=new State(version,Map.copyOf(next));
        for(long key:removed) uploaded.remove(key);
        int cells=next.values().stream().mapToInt(s->s.cells().size()).sum();
        dev.mmlf.common.MmlfLog.sampled("render.submission","terrain_meshes",1000,Map.of("sections",next.size(),"cells",cells,"version",version,"partial",selected!=null,
                "considered",grouped.size(),"changed",changed.size(),"removed",removed.size(),"reused",reused,"rebuildMicros",(System.nanoTime()-started)/1000));
        var dirty=new HashSet<>(removed); dirty.addAll(changed);
        for(long key:dirty) mc.levelRenderer.setSectionDirty(SectionPos.x(key),SectionPos.y(key),SectionPos.z(key));
        return true;
    }
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
