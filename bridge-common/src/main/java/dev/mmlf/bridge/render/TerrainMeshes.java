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

/** Immutable batches for terrain model compilation. Published only after a complete snapshot is built. */
public final class TerrainMeshes {
    private TerrainMeshes() {}
    private static final ResourceLocation RESERVE=ResourceLocation.fromNamespaceAndPath("mmlf_bridge","remote_reserve");
    private record State(long version,Map<Long,Map<String,List<BakedQuad>>> cells,Set<Long> sections) {}
    private static volatile State state=new State(0,Map.of(),Set.of());
    private static volatile boolean dirty;
    public static void invalidate() { dirty=true; }
    static void refresh(Minecraft mc,RenderSectionStore.Snapshot snapshot,int width,int height) {
        if(!TerrainModels.enabled() || !dirty || snapshot==null) return;
        dirty=false;
        if(!TerrainAtlas.ready || !rebuild(mc,snapshot,width,height)) clear();
    }
    public static Map<String,List<BakedQuad>> at(BlockPos pos) { return state.cells().getOrDefault(pos.asLong(),Map.of()); }
    private static final java.util.concurrent.ConcurrentMap<Long,Long> uploaded=new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Object,Upload> sodiumOutputs=Collections.synchronizedMap(new WeakHashMap<>());
    private record Upload(long section,long version) {}
    public static long version() { return state.version(); }
    public static boolean hasSection(long section) { State s=state; return s.sections().contains(section) && Objects.equals(uploaded.get(section),s.version()); }
    public static void uploaded(long section,long version) {
        if(state.version()==version && state.sections().contains(section) && !Objects.equals(uploaded.put(section,version),version))
            dev.mmlf.common.MmlfLog.info("render.submission","terrain_uploaded",Map.of("section",section,"version",version));
    }
    public static void sodiumOutput(Object output,long section,long version) { if(output!=null) sodiumOutputs.put(output,new Upload(section,version)); }
    public static void sodiumUploaded(Collection<?> outputs) { for(Object output:outputs) { Upload u=sodiumOutputs.remove(output); if(u!=null) uploaded(u.section(),u.version()); } }
    public static void clear() {
        var old=state.sections(); state=new State(state.version()+1,Map.of(),Set.of()); uploaded.clear();
        var mc=Minecraft.getInstance();
        if(mc.level!=null) for(long key:old) mc.levelRenderer.setSectionDirty(SectionPos.x(key),SectionPos.y(key),SectionPos.z(key));
    }

    /** Render thread; returns false without changing the published batches when the atlas cannot fit. */
    static boolean rebuild(Minecraft mc,RenderSectionStore.Snapshot snapshot,int width,int height) {
        ClientLevel level=mc.level;
        if(level==null) return false;
        var texture=mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if(!(texture instanceof TextureAtlas atlas)) return false;
        var sprite=((dev.mmlf.bridge.mixin.TextureAtlasAccessor)atlas).mmlf$sprites().get(RESERVE);
        if(sprite==null || width>sprite.contents().width() || height>sprite.contents().height()) return false;
        var out=new HashMap<Long,Map<String,List<BakedQuad>>>();
        var complete=new HashSet<Long>();
        var grouped=new HashMap<Long,List<RenderSectionStore.Layer>>();
        for(var e:snapshot.sections().entrySet()) grouped.computeIfAbsent(e.getKey().section(),k->new ArrayList<>()).addAll(e.getValue().layers());
        for(var section:grouped.entrySet()) {
            long key=section.getKey(); int sx=SectionPos.x(key),sy=SectionPos.y(key),sz=SectionPos.z(key);
            if(!level.hasChunk(sx,sz)) continue;
            int ox=sx*16,oy=sy*16,oz=sz*16;
            var occupied=new ArrayList<TerrainQuadPartition.Cell>();
            var pos=new BlockPos.MutableBlockPos();
            for(int y=0;y<16;y++) for(int z=0;z<16;z++) for(int x=0;x<16;x++)
                if(StandInBlock.is(level.getBlockState(pos.set(ox+x,oy+y,oz+z)))) occupied.add(new TerrainQuadPartition.Cell(x,y,z));
            if(occupied.isEmpty()) continue;
            boolean supported=section.getValue().stream().allMatch(l->l.vertexFormat().equals("minecraft:block-v1") && l.vertexStride()==32
                && l.topology().equals("quads") && l.material().equals("minecraft:block-atlas") && l.meshId().isEmpty()
                && l.indices().length==0 && Set.of("solid","cutout_mipped","cutout","translucent","tripwire").contains(l.renderType()));
            if(!supported) continue;
            var local=new HashMap<Long,Map<String,List<BakedQuad>>>();
            try {
                for(var layer:section.getValue()) for(var e:TerrainQuadPartition.partition(layer.vertices(),occupied).entrySet()) {
                    var c=e.getKey(); long block=BlockPos.asLong(ox+c.x(),oy+c.y(),oz+c.z());
                    var quads=local.computeIfAbsent(block,k->new HashMap<>()).computeIfAbsent(layer.renderType(),k->new ArrayList<>());
                    for(byte[] quad:e.getValue()) quads.add(convert(quad,c,sprite,width,height));
                }
            } catch(IllegalArgumentException e) { continue; }
            out.putAll(local); complete.add(key);
        }
        var frozen=new HashMap<Long,Map<String,List<BakedQuad>>>();
        out.forEach((pos,passes)->{ var p=new HashMap<String,List<BakedQuad>>(); passes.forEach((pass,quads)->p.put(pass,List.copyOf(quads))); frozen.put(pos,Map.copyOf(p)); });
        var old=state.sections();
        state=new State(state.version()+1,Map.copyOf(frozen),Set.copyOf(complete));
        dev.mmlf.common.MmlfLog.info("render.submission","terrain_meshes",Map.of("sections",complete.size(),"cells",frozen.size(),"version",state.version()));
        var dirty=new HashSet<>(old); dirty.addAll(complete);
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
