package dev.mmlf.bridge.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.bridge.Experiments;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.RenderDynamicCodec;
import dev.mmlf.common.RenderTextureCodec;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Submitter side of the unified render layer, dynamic content. Once per frame the submitting runtime runs the vanilla
 * render dispatchers itself for the block entities and entities it owns, into a recording buffer source instead of its
 * framebuffer, and publishes the recorded batches. This needs neither the runtime's own world pass nor its frustum:
 * whatever renderer is installed, block-entity and entity renderers are called through these dispatchers, and objects
 * behind the camera are included, so the render host's shadow pass and fast turns see them too.
 * <p>
 * Each batch keeps the render type it was recorded with (by name), the texture that type binds (by resource
 * location, or GL name when unregistered) and its vertex format, so the render host can draw it with its own
 * equivalent render type; textures are published once per scene generation, small ones refreshed periodically.
 */
public final class DynamicCapture {
    private DynamicCapture() {}
    private static final String BLOCK_ATLAS="minecraft:textures/atlas/blocks.png", PARTICLE_ATLAS="minecraft:textures/atlas/particles.png";
    private static final Map<String,BufferBuilder> particleBuilders=new LinkedHashMap<>();
    private static final Map<String,ByteBufferBuilder> particlePool=new HashMap<>();

    /** The name a standard particle sheet travels under, or null for sheets with their own rendering. */
    public static String particleSheet(net.minecraft.client.particle.ParticleRenderType type) {
        if(type==net.minecraft.client.particle.ParticleRenderType.TERRAIN_SHEET) return "terrain_sheet";
        if(type==net.minecraft.client.particle.ParticleRenderType.PARTICLE_SHEET_OPAQUE) return "particle_sheet_opaque";
        if(type==net.minecraft.client.particle.ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT) return "particle_sheet_translucent";
        if(type==net.minecraft.client.particle.ParticleRenderType.PARTICLE_SHEET_LIT) return "particle_sheet_lit";
        return null;
    }
    /**
     * The particles this runtime owns: those of its mods' particle classes, and block particles (breaking, hitting),
     * which in this runtime's world come only from its own blocks being broken, since it never breaks the render
     * host's; and vanilla particles that appeared on its own entities. Other vanilla particles (weather, ambience)
     * belong to the world the render host shows itself.
     */
    private static boolean ownedParticle(net.minecraft.client.particle.Particle particle) {
        return particle instanceof net.minecraft.client.particle.TerrainParticle || !particle.getClass().getName().startsWith("net.minecraft.")
            || dev.mmlf.bridge.RemoteEntities.entityParticle(particle);
    }
    private static void recordParticles(Minecraft mc,Camera camera,float partial) {
        var all=((dev.mmlf.bridge.mixin.ParticleEngineAccessor)mc.particleEngine).mmlf$particles();
        for(var entry:all.entrySet()) {
            String sheet=particleSheet(entry.getKey()); if(sheet==null) continue;
            BufferBuilder builder=null;
            for(var particle:entry.getValue()) {
                if(!ownedParticle(particle)) continue;
                if(builder==null) builder=particleBuilders.computeIfAbsent(sheet,s->new BufferBuilder(particlePool.computeIfAbsent(s,k->new ByteBufferBuilder(4096)),VertexFormat.Mode.QUADS,DefaultVertexFormat.PARTICLE));
                try { particle.render(builder,camera,partial); }
                catch(RuntimeException | LinkageError e) { MmlfLog.sampled("render.submission","dynamic_particle_failed",5000,Map.of("particle",particle.getClass().getName(),"exception",e.toString())); }
            }
        }
    }
    private static final long FILE_INTERVAL_NANOS=33_000_000L, TEXTURE_REFRESH_NANOS=10_000_000_000L;
    private static final int REFRESH_MAX_PIXELS=1024*1024;
    static final Map<String,VertexFormat> FORMATS=new LinkedHashMap<>();
    static {
        FORMATS.put("BLOCK",DefaultVertexFormat.BLOCK); FORMATS.put("NEW_ENTITY",DefaultVertexFormat.NEW_ENTITY);
        FORMATS.put("PARTICLE",DefaultVertexFormat.PARTICLE); FORMATS.put("POSITION",DefaultVertexFormat.POSITION);
        FORMATS.put("POSITION_COLOR",DefaultVertexFormat.POSITION_COLOR); FORMATS.put("POSITION_COLOR_NORMAL",DefaultVertexFormat.POSITION_COLOR_NORMAL);
        FORMATS.put("POSITION_COLOR_LIGHTMAP",DefaultVertexFormat.POSITION_COLOR_LIGHTMAP); FORMATS.put("POSITION_TEX",DefaultVertexFormat.POSITION_TEX);
        FORMATS.put("POSITION_TEX_COLOR",DefaultVertexFormat.POSITION_TEX_COLOR); FORMATS.put("POSITION_COLOR_TEX_LIGHTMAP",DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
        FORMATS.put("POSITION_TEX_LIGHTMAP_COLOR",DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR); FORMATS.put("POSITION_TEX_COLOR_NORMAL",DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
    }

    private static boolean capturing;
    private static final Map<RenderType,ByteBufferBuilder> pool=new HashMap<>();
    private static final Map<RenderType,BufferBuilder> active=new LinkedHashMap<>();
    private static final MultiBufferSource source=type->active.computeIfAbsent(type,t->new BufferBuilder(pool.computeIfAbsent(t,k->new ByteBufferBuilder(4096)),t.mode(),t.format()));
    private static final Map<RenderType,String> textureOf=new HashMap<>();
    private static final Map<Integer,String> textureNames=new HashMap<>();
    private static final Map<String,Long> publishedTextures=new HashMap<>();
    private static final Set<String> unsupported=new HashSet<>();
    private static long generation=-1, frame, lastPublish, lastStats, bytesSinceStats, framesSinceStats;
    private static int lastObjects;

    /** True while this class drives the dispatchers; this runtime's own world pass is not affected by hiding then. */
    public static boolean capturing() { return capturing; }

    /** The owner rule for dynamic content, the same namespace rule as for blocks. Players belong to their own runtime. */
    public static boolean owned(Entity entity) {
        if(entity instanceof RemoteProxy || entity instanceof Player) return false;
        if(dev.mmlf.bridge.RemoteEntities.claimed(entity.getUUID())) return true;
        // Claimed before the world was saved: its server copy carries the tag (the client copy learns it from the set).
        if(!entity.level().isClientSide() && entity.getTags().contains(dev.mmlf.bridge.RemoteEntities.OWNED_TAG)) { dev.mmlf.bridge.RemoteEntities.claim(entity.getUUID()); return true; }
        if(!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace().equals("minecraft")) return true;
        // Vanilla carriers of this runtime's content.
        if(entity instanceof ItemEntity item) return foreign(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).getNamespace());
        if(entity instanceof ItemFrame frame) return foreign(BuiltInRegistries.ITEM.getKey(frame.getItem().getItem()).getNamespace());
        if(entity instanceof FallingBlockEntity falling) return BridgeCore.renderOwned(falling.getBlockState(),falling.blockPosition());
        return false;
    }
    static boolean owned(BlockEntity blockEntity) { return BridgeCore.renderOwned(blockEntity.getBlockState(),blockEntity.getBlockPos()); }
    private static boolean foreign(String namespace) { return !namespace.equals("minecraft"); }

    /** Render thread, before each frame of the submitter ({@code MinecraftMixin}), whether or not it renders its world. */
    public static void frame(Minecraft mc,DeltaTracker delta) {
        if(!Experiments.GEOMETRY_REPLAY || mc.level==null || BridgeCore.session()==null || !BridgeCore.connected() || GeometryLayer.isRenderHost()) return;
        long scene=GeometryLayer.sceneGeneration(); if(scene<0) return;
        if(scene!=generation) { generation=scene; publishedTextures.clear(); textureNames.clear(); }
        GeometryLayer.submitterFrame(mc);
        long now=System.nanoTime();
        if(!BridgeCore.session().inJvm() && now-lastPublish<FILE_INTERVAL_NANOS) return;
        lastPublish=now;
        float partial=delta.getGameTimeDeltaPartialTick(false);
        Camera camera=mc.gameRenderer.getMainCamera();
        Entity viewer=mc.getCameraEntity()!=null?mc.getCameraEntity():mc.player;
        // Without its own world pass this runtime no longer sets up the camera (which follows the primary's pose).
        // The camera is set up from the newest primary pose here, so the capture follows the same pose it reports.
        BridgeCore.pollSharedPose();
        long cameraSequence=BridgeCore.receivedPoseSequence();
        if(viewer!=null) camera.setup(mc.level,viewer,false,false,partial);
        if(!camera.isInitialized()) return;
        var cam=camera.getPosition();
        var blockEntities=mc.getBlockEntityRenderDispatcher(); blockEntities.prepare(mc.level,camera,mc.hitResult);
        var entities=mc.getEntityRenderDispatcher(); entities.prepare(mc.level,camera,mc.crosshairPickEntity);
        var pose=new PoseStack();
        int distance=mc.options.getEffectiveRenderDistance(); double maxSq=Math.pow((distance+1)*16.0,2);
        int objects=0;
        capturing=true; active.clear();
        try {
            for(Entity entity:mc.level.entitiesForRendering()) {
                if(!owned(entity) || entity.distanceToSqr(cam)>maxSq) continue;
                double x=Mth.lerp(partial,entity.xOld,entity.getX())-cam.x,y=Mth.lerp(partial,entity.yOld,entity.getY())-cam.y,z=Mth.lerp(partial,entity.zOld,entity.getZ())-cam.z;
                try { entities.render(entity,x,y,z,Mth.lerp(partial,entity.yRotO,entity.getYRot()),partial,pose,source,entities.getPackedLightCoords(entity,partial)); objects++; }
                catch(RuntimeException | LinkageError e) { MmlfLog.sampled("render.submission","dynamic_entity_failed",5000,Map.of("entity",String.valueOf(entity.getType()),"exception",e.toString())); }
                pose.setIdentity();
            }
            recordParticles(mc,camera,partial);
            int cx=SectionPos.blockToSectionCoord(cam.x),cz=SectionPos.blockToSectionCoord(cam.z);
            for(int x=cx-distance;x<=cx+distance;x++) for(int z=cz-distance;z<=cz+distance;z++) {
                LevelChunk chunk=mc.level.getChunkSource().getChunk(x,z,ChunkStatus.FULL,false);
                if(chunk==null) continue;
                for(BlockEntity blockEntity:chunk.getBlockEntities().values()) {
                    if(!owned(blockEntity)) continue;
                    // As the level renderer does: the dispatcher draws at the pose stack's origin, so place it first.
                    var at=blockEntity.getBlockPos();
                    pose.translate(at.getX()-cam.x,at.getY()-cam.y,at.getZ()-cam.z);
                    try { blockEntities.render(blockEntity,partial,pose,source); objects++; }
                    catch(RuntimeException | LinkageError e) { MmlfLog.sampled("render.submission","dynamic_block_entity_failed",5000,Map.of("type",String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType())),"exception",e.toString())); }
                    pose.setIdentity();
                }
            }
        } finally { capturing=false; }
        var batches=collect();
        for(var entry:particleBuilders.entrySet()) {
            try(MeshData mesh=entry.getValue().build()) {
                if(mesh==null) continue;
                var state=mesh.drawState();
                byte[] vertices=new byte[state.vertexCount()*DefaultVertexFormat.PARTICLE.getVertexSize()];
                mesh.vertexBuffer().duplicate().get(vertices);
                batches.add(new RenderDynamicCodec.Batch(entry.getKey(),entry.getKey().equals("terrain_sheet")?BLOCK_ATLAS:PARTICLE_ATLAS,"PARTICLE","QUADS",state.vertexCount(),vertices));
            }
        }
        particleBuilders.clear();
        for(var batch:batches) publishTexture(mc,batch.texture(),now);
        try {
            byte[] bytes=RenderDynamicCodec.encode(new RenderDynamicCodec.Frame(BridgeCore.runtimeId(),mc.level.dimension().location().toString(),generation,++frame,cameraSequence,cam.x,cam.y,cam.z,batches));
            RenderChannel.publish("dynamic",bytes);
            bytesSinceStats+=bytes.length; framesSinceStats++;
        } catch(java.io.IOException e) { MmlfLog.sampled("render.submission","dynamic_publish_failed",5000,Map.of("exception",e.toString())); }
        dev.mmlf.bridge.ProxyItems.ownerFrame(mc,generation);
        if(objects!=lastObjects || now-lastStats>10_000_000_000L) {
            MmlfLog.info("render.submission","dynamic_capture",Map.of("objects",objects,"batches",batches.size(),"frames",framesSinceStats,"avgBytes",framesSinceStats==0?0:bytesSinceStats/framesSinceStats,
                "cpuMicros",(System.nanoTime()-now)/1000,"nativeWorld",NativeWorld.skipping()?"skipped":"rendered"));
            lastObjects=objects; lastStats=now; bytesSinceStats=framesSinceStats=0;
        }
    }

    /** The batches recorded into {@link #source} since the last collection. */
    private static List<RenderDynamicCodec.Batch> collect() {
        var batches=new ArrayList<RenderDynamicCodec.Batch>();
        for(var entry:active.entrySet()) {
            RenderType type=entry.getKey();
            try(MeshData mesh=entry.getValue().build()) {
                if(mesh==null) continue;
                String format=formatName(type.format());
                if(format==null) { if(unsupported.add(type.toString())) MmlfLog.info("render.submission","dynamic_format_unsupported",Map.of("renderType",type.toString())); continue; }
                var state=mesh.drawState();
                byte[] vertices=new byte[state.vertexCount()*type.format().getVertexSize()];
                mesh.vertexBuffer().duplicate().get(vertices);
                batches.add(new RenderDynamicCodec.Batch(name(type),textureKey(type),format,state.mode().name(),state.vertexCount(),vertices));
            }
        }
        active.clear();
        return batches;
    }
    /**
     * Records what {@code body} draws into a buffer source as separate batches (used for item meshes), and publishes
     * the textures they bind. Render thread, outside the frame's own capture.
     */
    public static List<RenderDynamicCodec.Batch> recordBatches(Minecraft mc,java.util.function.Consumer<MultiBufferSource> body) {
        active.clear(); capturing=true;
        try { body.accept(source); }
        finally { capturing=false; }
        var batches=collect();
        long now=System.nanoTime();
        for(var batch:batches) publishTexture(mc,batch.texture(),now);
        return batches;
    }

    static String formatName(VertexFormat format) {
        for(var entry:FORMATS.entrySet()) if(entry.getValue()==format) return entry.getKey();
        return null;
    }
    private static String name(RenderType type) {
        String name=((dev.mmlf.bridge.mixin.RenderStateShardAccessor)type).mmlf$name();
        return name==null || name.isBlank() ? type.toString() : name;
    }
    /** The texture a render type binds, found by setting its state once; empty for untextured types. */
    private static String textureKey(RenderType type) {
        return textureOf.computeIfAbsent(type,t->{
            RenderSystem.setShaderTexture(0,0);
            int id;
            try { t.setupRenderState(); id=RenderSystem.getShaderTexture(0); }
            finally { t.clearRenderState(); }
            if(id==0) return "";
            String known=textureNames.get(id);
            if(known!=null) return known;
            var byPath=((dev.mmlf.bridge.mixin.TextureManagerAccessor)Minecraft.getInstance().getTextureManager()).mmlf$byPath();
            for(var entry:byPath.entrySet()) if(entry.getValue().getId()==id) { textureNames.put(id,entry.getKey().toString()); return entry.getKey().toString(); }
            return "gl:"+id;
        });
    }
    /** Channel name of a texture key; the key itself travels inside the texture record. */
    static String textureChannel(String key) {
        long hash=0xcbf29ce484222325L;
        for(int i=0;i<key.length();i++) { hash^=key.charAt(i); hash*=0x100000001b3L; }
        return "texture/"+Long.toHexString(hash);
    }
    private static void publishTexture(Minecraft mc,String key,long now) {
        if(key.isEmpty() || key.equals(BLOCK_ATLAS)) return;
        Long published=publishedTextures.get(key);
        if(published!=null && now-published<TEXTURE_REFRESH_NANOS) return;
        int id=-1;
        if(key.startsWith("gl:")) id=Integer.parseInt(key.substring(3));
        else {
            var texture=mc.getTextureManager().getTexture(net.minecraft.resources.ResourceLocation.parse(key));
            if(texture!=null) id=texture.getId();
        }
        if(id<=0) return;
        var transfer=new dev.mmlf.bridge.render.GlPixelState();
        try {
        RenderSystem.bindTexture(id);
        int width=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),height=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
        if(width<=0 || height<=0 || width>8192 || height>8192 || (long)width*height*4>RenderTextureCodec.MAX_BYTES) { publishedTextures.put(key,Long.MAX_VALUE/2); return; }
        // Large textures (atlases) are sent once per scene; small ones are refreshed, since glyph pages and maps grow.
        if(published!=null && (long)width*height>REFRESH_MAX_PIXELS) { publishedTextures.put(key,now); return; }
        ByteBuffer pixels=MemoryUtil.memAlloc(width*height*4);
        try {
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
            byte[] data=new byte[width*height*4]; pixels.get(data);
            RenderChannel.publish(textureChannel(key),RenderTextureCodec.encode(new RenderTextureCodec.Texture(BridgeCore.runtimeId(),mc.level.dimension().location().toString(),generation,key,"rgba8",width,height,data)));
            publishedTextures.put(key,now);
            if(published==null) MmlfLog.info("render.submission","dynamic_texture",Map.of("texture",key,"width",width,"height",height));
        } catch(java.io.IOException e) { MmlfLog.sampled("render.submission","dynamic_texture_failed",5000,Map.of("texture",key,"exception",e.toString())); }
        finally { MemoryUtil.memFree(pixels); }
        } finally { transfer.close(); }
    }
}
