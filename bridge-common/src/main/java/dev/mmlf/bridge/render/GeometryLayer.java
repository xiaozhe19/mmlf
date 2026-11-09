package dev.mmlf.bridge.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.bridge.Experiments;
import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.RenderDynamicCodec;
import dev.mmlf.common.RenderSectionStore;
import dev.mmlf.common.RenderGeometryCodec;
import dev.mmlf.common.RenderMeshCapabilities;
import dev.mmlf.common.RenderTextureCodec;
import dev.mmlf.common.RenderHostPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Unified render layer (experiments {@code geometry} and {@code geometry_replay}): the render host draws the whole
 * scene; the other runtime only submits what it owns.
 * <ul>
 * <li>Static blocks: the submitter meshes the sections holding its own content ({@link OwnedSectionCompiler}) and
 * publishes them, all five chunk layers, with its block atlas.</li>
 * <li>Dynamic content: the submitter records its own block entities and entities each frame ({@link DynamicCapture}),
 * with the textures their render types bind.</li>
 * </ul>
 * The render host draws both through a client-only stand-in entity ({@link RemoteProxy}), so any renderer or shader
 * mod treats them as it treats an entity, in each of its passes. Static layers use render types that copy the vanilla
 * chunk layers with the source's atlas bound, so UVs need no remapping; translucent layers are sorted by the buffer
 * source like translucent entities. Dynamic batches use this runtime's own vanilla render type of the same name, with
 * the received texture registered under an {@code mmlf:remote/...} name.
 * <p>
 * Transport goes through {@link RenderChannel}: memory in one JVM, files across processes.
 */
public final class GeometryLayer {
 private GeometryLayer() {}
 /** Section meshes this runtime submits; only the non-host runtime exports them. */
 private static final RenderSectionStore submissions=new RenderSectionStore();
 private static final List<String> PASSES=List.of("solid","cutout_mipped","cutout","translucent","tripwire");
 private static final RenderMeshCapabilities RECEIVER_CAPABILITIES=new RenderMeshCapabilities(
  Set.of(new RenderMeshCapabilities.Layout("minecraft:block-v1","quads",32)),Set.copyOf(PASSES),Set.of("minecraft:block-atlas"));
 private static final String BLOCK_ATLAS=TextureAtlas.LOCATION_BLOCKS.toString();
 private static final Object sceneLock=new Object();
 private static Object currentScene;
 private static String previousRenderHost;
 private static volatile boolean assetsInvalidated;
 private static long exportedVersion,lastExport,exports,exportBytesTotal;
 private static boolean atlasWritten,unsupportedLogged;

 // ---- render host state (render thread) ----
 /** A received static mesh, little-endian {@code minecraft:block-v1} vertices relative to its section origin. */
 private record Mesh(long section,String pass,ByteBuffer vertices,int count) {}
 private record CachedSection(List<RenderSectionStore.Layer> layers,List<Mesh> meshes) {}
 private static final Map<RenderSectionStore.Key,CachedSection> cachedSections=new HashMap<>();
 private static final Map<String,List<Mesh>> staticByPass=new HashMap<>();
 private static final Map<String,RenderType> remoteTypes=new HashMap<>();
 /** A received dynamic batch, resolved to one of this runtime's render types of the same vertex format. */
 private record Draw(RenderType type,VertexFormat format,ByteBuffer vertices,int count,int[] offsets) {}
 private static List<Draw> dynamicDraws=List.of();
 private static double dynamicX,dynamicY,dynamicZ;
 private static long dynamicFrame=-1,dynamicGeneration=-1,dynamicCamera,alignFrames,alignMisses;
 /**
  * {@code MMLF_FRAME_ALIGN_MS} (or {@code -Dmmlf.frameAlignMs}): how long the render host may wait each frame for the
  * submitter's capture of its latest camera pose. 0 (default) never waits; waiting trades frame rate for alignment.
  */
 private static final long FRAME_ALIGN_NANOS=(long)(Double.parseDouble(System.getProperty("mmlf.frameAlignMs",System.getenv().getOrDefault("MMLF_FRAME_ALIGN_MS","0")))*1_000_000L);
 private static final Map<String,Optional<RenderType>> dynamicTypes=new HashMap<>();
 private static final Map<String,RemoteTexture> textures=new HashMap<>();
 private static final Map<String,ResourceLocation> textureLocations=new HashMap<>();
 private static final Map<String,RenderChannel.Cursor> textureCursors=new HashMap<>();
 private static final Map<String,Long> texturePolls=new HashMap<>();
 private static final RenderChannel.Cursor geometryCursor=new RenderChannel.Cursor(),atlasCursor=new RenderChannel.Cursor(),dynamicCursor=new RenderChannel.Cursor();
 private static long worldFrames,framesAtStats,lastPoll,drawNanos,drawVertices,drawCount,lastDrawNanos,lastConsumedSignal,lastDynamicNanos;
 private static long acceptedGeneration=-1,acceptedRevision=-1;
 private static volatile RenderSectionStore.Snapshot remoteSnapshot;
 private static RemoteTexture atlas;
 private static ResourceLocation atlasLocation;
 private static long atlasGeneration=-1;
 private static int atlasWidth,atlasHeight;
 private static boolean replayReported;

 /** Compile results rejected while a scene reset was pending; compiled again once the scene is current. */
 private static final Map<Long,Object> pendingRecompile=new java.util.concurrent.ConcurrentHashMap<>();
 /** The window owner draws the whole scene; the other runtime only submits. */
 public static String renderHost() { return BridgeCore.primaryId(); }
 public static boolean isRenderHost() { return BridgeCore.isPrimary(); }
 private static String background() { return RenderHostPolicy.other(renderHost()); }
 /** Render host: whether the submitter's dynamic content currently arrives, so the things it draws may be left to it. */
 public static boolean ownerSubmitting() {
  return Experiments.GEOMETRY_REPLAY && lastDynamicNanos!=0 && System.nanoTime()-lastDynamicNanos<1_000_000_000L && isRenderHost();
 }
 public static String submitter() { return background(); }

 /** Renderer-independent section submission. Producer identifiers keep contributors in the same section separate. */
 public static long sceneGeneration() { synchronized(sceneLock) { return currentScene==null ? -1 : submissions.generation(); } }
 /** Each snapshot belongs to its source runtime; resource IDs must be resolved in that source's namespace. */
 public static RenderSectionStore.Snapshot localSnapshot() { return submissions.snapshot(); }
 public static RenderSectionStore.Snapshot remoteSnapshot() { return remoteSnapshot; }
 public static void invalidateAssets() { synchronized(sceneLock) { assetsInvalidated=true; } }
 public static boolean removeVersioned(long generation,String producer,long section,long revision) {
  return submissions.removeVersioned(generation,producer,section,revision);
 }

 /** Called on a section compile worker with the compiler's results, before they are released. */
 public static void captured(Object sourceScene,long sourceGeneration,SectionPos section,long compileRevision,Map<RenderType,MeshData> layers) {
  long generation;
  synchronized(sceneLock) {
   if(assetsInvalidated || sourceScene==null || sourceScene!=currentScene || sourceGeneration!=submissions.generation()) {
    // The client tick may not have reset the scene yet; without a recompile this section would never be submitted.
    if(sourceScene!=null) pendingRecompile.put(section.asLong(),sourceScene);
    return;
   }
   generation=submissions.generation();
  }
  long started=System.nanoTime();
  var copies=new ArrayList<RenderSectionStore.Layer>();
  for(var entry:layers.entrySet()) {
   MeshData.DrawState state=entry.getValue().drawState();
   if(state.format()!=DefaultVertexFormat.BLOCK || state.mode()!=VertexFormat.Mode.QUADS) {
    if(!unsupportedLogged) { unsupportedLogged=true; MmlfLog.info("experiment","geometry_unsupported",Map.of("renderType",entry.getKey().toString(),"format",state.format().toString(),"mode",state.mode().toString())); }
    continue;
   }
   ByteBuffer source=entry.getValue().vertexBuffer();
   byte[] bytes=new byte[state.vertexCount()*DefaultVertexFormat.BLOCK.getVertexSize()];
   source.duplicate().get(bytes);
   copies.add(new RenderSectionStore.Layer(name(entry.getKey()),state.vertexCount(),bytes));
  }
  copies.sort(Comparator.comparing(RenderSectionStore.Layer::renderType));
  // Empty sections contribute nothing; remove an older mesh instead of repeatedly exporting empty entries.
  boolean accepted=copies.isEmpty() ? submissions.removeVersioned(generation,"minecraft:sections",section.asLong(),compileRevision) :
   submissions.submitVersioned(generation,"minecraft:sections",section.asLong(),compileRevision,System.currentTimeMillis(),copies);
  if(!accepted) return;
  MmlfLog.sampled("experiment","geometry_capture",2000,Map.of("section",section.toString(),"layers",copies.size(),"copyMicros",(System.nanoTime()-started)/1000));
 }
 private static String name(RenderType type) {
  return type==RenderType.solid()?"solid":type==RenderType.cutoutMipped()?"cutout_mipped":type==RenderType.cutout()?"cutout":type==RenderType.translucent()?"translucent":type==RenderType.tripwire()?"tripwire":type.toString();
 }
 private static RenderType chunkType(String name) {
  return switch(name) { case "solid"->RenderType.solid(); case "cutout_mipped"->RenderType.cutoutMipped(); case "cutout"->RenderType.cutout(); case "translucent","translucent_moving_block"->RenderType.translucent(); case "tripwire"->RenderType.tripwire(); default->null; };
 }

 /** Client tick on both sides: the submitter publishes, the render host polls. */
 public static void tick(Minecraft mc) {
  String selectedHost=renderHost();
  boolean hostChanged=previousRenderHost!=null && !Objects.equals(previousRenderHost,selectedHost);
  boolean hostInitialized=previousRenderHost==null && selectedHost!=null;
  synchronized(sceneLock) {
   if(currentScene!=mc.level || assetsInvalidated || hostChanged || hostInitialized) {
    currentScene=mc.level;
    previousRenderHost=selectedHost;
    RenderInstanceChannel.reset(submissions.resetScene());
    atlasWritten=false;
    exportedVersion=0;
    resetHost(mc);
    assetsInvalidated=false;
   }
  }
  // A new generation makes the submission compiler rescan and resubmit; no renderer rebuild is needed for that.
  if(hostChanged) MmlfLog.info("render.capability","render_host_changed",Map.of("runtime",BridgeCore.runtimeId(),"host",String.valueOf(selectedHost)));
  if(mc.level!=null && !pendingRecompile.isEmpty()) {
   Object scene; synchronized(sceneLock) { scene=currentScene; }
   for(var entry:pendingRecompile.entrySet()) {
    long key=entry.getKey(); Object source=entry.getValue();
    // remove(key,value) keeps a newer rejection that raced with this drain.
    if(!pendingRecompile.remove(key,source) || source!=scene) continue;
    OwnedSectionCompiler.recompile(key);
   }
  }
  OwnedSectionCompiler.tick(mc);
  if(BridgeCore.session()==null || mc.level==null || !BridgeCore.connected()) return;
  RenderShaderCapabilities.tick();
  RenderInstanceProbe.tick();
  RenderInstanceChannel.tick(mc,background());
  if(isRenderHost()) { poll(); confirmConsumption(mc); return; }
  if(!atlasWritten) writeAtlas(mc);
  else if(Experiments.GEOMETRY_REPLAY) AtlasAnimation.publish(mc,submissions.generation());
  exportSubmissions(mc);
 }

 /** In one JVM the channels are memory handovers: no reason to batch changes, so submissions follow every frame. */
 private static boolean sameJvm() { return BridgeCore.session()!=null && BridgeCore.session().inJvm(); }
 /** Submitter, render thread, each frame in one JVM: starts compiles of changed sections and exports finished ones at once. */
 public static void submitterFrame(Minecraft mc) {
  if(!sameJvm() || !atlasWritten || mc.level==null || isRenderHost()) return;
  OwnedSectionCompiler.frame(mc);
  exportSubmissions(mc);
 }
 private static void exportSubmissions(Minecraft mc) {
  long now=System.currentTimeMillis();
  if(now-lastExport<(sameJvm()?0:250)) return;
  var snapshot=submissions.snapshotIfChanged(exportedVersion);
  if(snapshot==null) return;
  long version=snapshot.revision();
  lastExport=now;
  long started=System.nanoTime();
  try {
   int sections=0,vertices=0; long newest=0;
   for(var section:snapshot.sections().values()) {
    if(section.layers().isEmpty()) continue;
    sections++;
    newest=Math.max(newest,section.capturedMs());
    for(var layer:section.layers()) vertices+=layer.vertexCount();
   }
   byte[] bytes=RenderGeometryCodec.encode(new RenderGeometryCodec.Batch(BridgeCore.runtimeId(),mc.level.dimension().location().toString(),now,snapshot));
   RenderChannel.publish("geometry",bytes);
   exportedVersion=version;
   exports++; exportBytesTotal+=bytes.length;
   MmlfLog.info("experiment","geometry_export",Map.of("sections",sections,"vertices",vertices,"bytes",bytes.length,"exportMicros",(System.nanoTime()-started)/1000,
    "newestCaptureAgeMs",newest==0?-1:now-newest,"exports",exports,"bytesTotal",exportBytesTotal));
  } catch(IOException e) { MmlfLog.sampled("experiment","geometry_export_failed",2000,Map.of("exception",e.toString())); }
 }

 /** Scene reset on the render host: everything received belongs to the previous scene. */
 private static void resetHost(Minecraft mc) {
  acceptedGeneration=acceptedRevision=-1;
  remoteSnapshot=null;
  TerrainMeshes.clear(); TerrainAtlas.ready=false;
  replayReported=false;
  cachedSections.clear(); staticByPass.clear(); lastDrawNanos=lastDynamicNanos=0;
  dynamicDraws=List.of(); dynamicFrame=dynamicGeneration=-1; dynamicCamera=0; dynamicTypes.clear();
  var manager=mc.getTextureManager();
  for(var location:textureLocations.values()) manager.release(location);
  textures.clear(); textureLocations.clear(); textureCursors.clear(); texturePolls.clear();
  if(atlasLocation!=null) manager.release(atlasLocation);
  atlas=null; atlasLocation=null; atlasGeneration=-1; atlasWidth=atlasHeight=0;
  AtlasAnimation.reset();
  remoteTypes.clear();
  geometryCursor.reset(); atlasCursor.reset(); dynamicCursor.reset();
  dev.mmlf.bridge.ProxyItems.reset();
  RemoteProxy.removeCurrent();
 }

 /** Raw RGBA of the submitter's block atlas, mip level 0. */
 private static void writeAtlas(Minecraft mc) {
  var texture=mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
  if(texture==null) return;
  RenderSystem.bindTexture(texture.getId());
  int width=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),height=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
  if(width<=0 || height<=0) return;
  if(width>8192 || height>8192 || (long)width*height*4>RenderTextureCodec.MAX_BYTES) {
   MmlfLog.sampled("render.submission","atlas_too_large",5000,Map.of("width",width,"height",height)); return;
  }
  ByteBuffer pixels=MemoryUtil.memAlloc(width*height*4);
  var transfer=new GlPixelState();
  try {
   long started=System.nanoTime();
   GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
   byte[] data=new byte[width*height*4]; pixels.get(data);
   RenderChannel.publish("atlas",RenderTextureCodec.encode(new RenderTextureCodec.Texture(BridgeCore.runtimeId(),mc.level.dimension().location().toString(),
    submissions.generation(),"minecraft:block-atlas","rgba8",width,height,data)));
   atlasWritten=true;
   MmlfLog.info("experiment","geometry_atlas",Map.of("width",width,"height",height,"bytes",data.length,"micros",(System.nanoTime()-started)/1000));
  } catch(IOException e) { MmlfLog.sampled("experiment","geometry_atlas_failed",2000,Map.of("exception",e.toString())); }
  finally { transfer.close(); MemoryUtil.memFree(pixels); }
 }

 private static void poll() {
  long now=System.currentTimeMillis();
  if(now-lastPoll<(sameJvm()?0:100)) return;
  lastPoll=now;
  String runtime=background();
  try {
   byte[] atlasBytes=RenderChannel.poll(runtime,"atlas",atlasCursor,RenderTextureCodec.MAX_BYTES+65536);
   if(atlasBytes!=null) {
    loadAtlas(atlasBytes);
    geometryCursor.reset(); // geometry may have been waiting for this texture generation
   }
   byte[] geometry=RenderChannel.poll(runtime,"geometry",geometryCursor,RenderGeometryCodec.MAX_FILE);
   if(geometry!=null) load(geometry);
  } catch(IOException | RuntimeException e) { MmlfLog.sampled("experiment","geometry_poll_failed",2000,Map.of("exception",e.toString())); }
 }
 private static void loadAtlas(byte[] bytes) throws IOException {
  var texture=RenderTextureCodec.decode(bytes); var mc=Minecraft.getInstance();
  if(!texture.runtime().equals(background()) || mc.level==null || !texture.dimension().equals(mc.level.dimension().location().toString())
    || !texture.resource().equals("minecraft:block-atlas") || !texture.format().equals("rgba8")) throw new IOException("texture resource unsupported or scene mismatch");
  byte[] data=texture.data();
  if((long)texture.width()*texture.height()*4!=data.length) throw new IOException("invalid rgba8 texture size");
  if(atlas==null) {
   atlas=new RemoteTexture(true);
   atlasLocation=ResourceLocation.fromNamespaceAndPath("mmlf","remote/"+background()+"/blocks");
   mc.getTextureManager().register(atlasLocation,atlas);
  }
  atlas.upload(texture.width(),texture.height(),data);
  TerrainAtlas.ensureCapacity(mc,texture.width(),texture.height());
  atlasGeneration=texture.generation(); atlasWidth=texture.width(); atlasHeight=texture.height();
  TerrainAtlas.copy(atlas,atlasWidth,atlasHeight);
  MmlfLog.info("experiment","geometry_atlas_loaded",Map.of("width",texture.width(),"height",texture.height()));
 }
 private static void load(byte[] data) throws IOException {
  long started=System.nanoTime();
  var batch=RenderGeometryCodec.decode(data);
  var mc=Minecraft.getInstance();
  if(!batch.runtime().equals(background()) || mc.level==null || !batch.dimension().equals(mc.level.dimension().location().toString()))
   throw new IOException("geometry scene mismatch");
  long generation=batch.snapshot().generation(),revision=batch.snapshot().revision();
  if(generation<acceptedGeneration || generation==acceptedGeneration && revision<=acceptedRevision) return;
  boolean needsAtlas=batch.snapshot().sections().values().stream().flatMap(s->s.layers().stream()).anyMatch(RECEIVER_CAPABILITIES::accepts);
  if(needsAtlas && (atlas==null || generation!=atlasGeneration)) throw new IOException("geometry texture generation mismatch");
  long exportedMs=batch.exportedMs(); int sections=batch.snapshot().sections().size();
  int vertices=0,reused=0,rebuilt=0; long newest=0;
  Map<RenderSectionStore.Key,CachedSection> next=new HashMap<>();
  for(var entry:batch.snapshot().sections().entrySet()) {
   long key=entry.getKey().section(); newest=Math.max(newest,entry.getValue().capturedMs());
   CachedSection old=cachedSections.get(entry.getKey());
   if(old!=null && RenderSectionStore.sameLayers(old.layers(),entry.getValue().layers())) {
    next.put(entry.getKey(),old); reused++;
    for(Mesh mesh:old.meshes()) vertices+=mesh.count();
    continue;
   }
   var sectionMeshes=new ArrayList<Mesh>();
   for(var layer:entry.getValue().layers()) {
    int count=layer.vertexCount();
    if(!RECEIVER_CAPABILITIES.accepts(layer) || count%4!=0 || !layer.meshId().isEmpty()) {
     MmlfLog.sampled("render.submission","unsupported_mesh",5000,Map.of("pass",layer.renderType(),"format",layer.vertexFormat(),"topology",layer.topology())); continue;
    }
    if(count==0) continue;
    sectionMeshes.add(new Mesh(key,layer.renderType(),ByteBuffer.wrap(layer.vertices()).order(ByteOrder.LITTLE_ENDIAN),count));
    vertices+=count;
   }
   next.put(entry.getKey(),new CachedSection(entry.getValue().layers(),List.copyOf(sectionMeshes))); rebuilt++;
  }
  cachedSections.clear(); cachedSections.putAll(next);
  staticByPass.clear();
  for(var section:cachedSections.values()) for(Mesh mesh:section.meshes()) staticByPass.computeIfAbsent(mesh.pass(),k->new ArrayList<>()).add(mesh);
  acceptedGeneration=generation; acceptedRevision=revision; remoteSnapshot=batch.snapshot();
  TerrainMeshes.invalidate();
  long now=System.currentTimeMillis();
  MmlfLog.info("experiment","geometry_upload",Map.of("sections",sections,"vertices",vertices,"bytes",data.length,"uploadMicros",(System.nanoTime()-started)/1000,"reusedSections",reused,"rebuiltSections",rebuilt,
   "exportToUploadMs",now-exportedMs,"captureToUploadMs",newest==0?-1:now-newest));
 }
 private static boolean atlasCurrent() { return atlas!=null && atlasGeneration==acceptedGeneration; }

 // ---- render host, per frame ----

 /** Render host, render thread, at the start of each world frame: takes the newest dynamic frame and places the stand-in. */
 public static void beginFrame(float partial) {
  var mc=Minecraft.getInstance();
  boolean host=Experiments.GEOMETRY_REPLAY && BridgeCore.session()!=null && BridgeCore.connected() && mc.level!=null && isRenderHost();
  if(!host) { RemoteProxy.removeCurrent(); return; }
  worldFrames++;
  // In one JVM the newest submitted sections are taken every frame instead of every client tick.
  if(sameJvm()) poll();
  TerrainMeshes.refresh(mc,remoteSnapshot,atlasWidth,atlasHeight);
  dev.mmlf.bridge.ProxyItems.hostFrame(mc);
  if(atlasCurrent()) AtlasAnimation.apply(background(),atlas,atlasGeneration,atlasWidth,atlasHeight);
  try {
   // Frame alignment (opt-in, costs frame rate): wait briefly for the capture that followed this runtime's last pose.
   long target=FRAME_ALIGN_NANOS>0 ? BridgeCore.publishedPoseSequence() : 0;
   long deadline=System.nanoTime()+FRAME_ALIGN_NANOS;
   while(true) {
    byte[] bytes=RenderChannel.poll(background(),"dynamic",dynamicCursor,RenderDynamicCodec.MAX_BYTES+65536);
    RenderDynamicCodec.Frame frame=bytes==null ? null : RenderDynamicCodec.decode(bytes);
    if(frame!=null) loadDynamic(mc,frame);
    if(target<=0 || frame!=null && frame.cameraSequence()>=target || dynamicCamera>=target || System.nanoTime()>=deadline) break;
    java.util.concurrent.locks.LockSupport.parkNanos(200_000L);
   }
   if(target>0) { alignFrames++; if(dynamicCamera<target) alignMisses++;
    if(alignFrames%600==0) { MmlfLog.info("render.submission","frame_align",Map.of("frames",alignFrames,"misses",alignMisses,"budgetMs",FRAME_ALIGN_NANOS/1e6)); alignMisses=0; } }
  } catch(IOException | RuntimeException e) { MmlfLog.sampled("render.submission","dynamic_receive_failed",2000,Map.of("exception",e.toString())); }
  boolean content=(atlasCurrent() && !staticByPass.isEmpty()) || !dynamicDraws.isEmpty();
  RemoteProxy.place(mc,content);
 }

 private static void loadDynamic(Minecraft mc,RenderDynamicCodec.Frame frame) {
  if(!frame.runtime().equals(background()) || !frame.dimension().equals(mc.level.dimension().location().toString())
    || frame.generation()<dynamicGeneration || frame.generation()==dynamicGeneration && frame.frame()<=dynamicFrame) return;
  var draws=new ArrayList<Draw>(frame.batches().size());
  int skipped=0;
  boolean blobShadows=drawsBlobShadows(mc);
  for(var batch:frame.batches()) {
   if(!blobShadows && batch.renderType().equals("entity_shadow")) continue;
   VertexFormat format=DynamicCapture.FORMATS.get(batch.format());
   VertexFormat.Mode mode;
   try { mode=VertexFormat.Mode.valueOf(batch.mode()); } catch(IllegalArgumentException e) { skipped++; continue; }
   if(format==null || (long)batch.vertexCount()*format.getVertexSize()!=batch.vertices().length || mode==VertexFormat.Mode.QUADS && batch.vertexCount()%4!=0) { skipped++; continue; }
   RenderType type=dynamicType(mc,batch.renderType(),batch.texture(),format,mode);
   if(type==null) { skipped++; continue; }
   draws.add(new Draw(type,format,ByteBuffer.wrap(batch.vertices()).order(ByteOrder.LITTLE_ENDIAN),batch.vertexCount(),offsets(format)));
  }
  dynamicDraws=draws; dynamicFrame=frame.frame(); dynamicGeneration=frame.generation(); lastDynamicNanos=System.nanoTime(); dynamicCamera=frame.cameraSequence();
  dynamicX=frame.originX(); dynamicY=frame.originY(); dynamicZ=frame.originZ();
  if(skipped>0) MmlfLog.sampled("render.submission","dynamic_batches_skipped",5000,Map.of("skipped",skipped,"drawn",draws.size()));
 }

 private static volatile long lastBlobShadow;
 /** {@code EntityRenderDispatcherMixin}: this runtime just drew a blob shadow for one of its own entities. */
 public static void blobShadowDrawn() { lastBlobShadow=System.nanoTime(); }
 /**
  * Whether the submitter's entity blob shadows are drawn: as this runtime does for its own entities. Seen recently,
  * that is the answer (it reflects options and any shader mod suppressing them); otherwise the option decides.
  */
 private static boolean drawsBlobShadows(Minecraft mc) {
  if(lastBlobShadow!=0 && System.nanoTime()-lastBlobShadow<2_000_000_000L) return true;
  return mc.options.entityShadows().get() && !RenderShaderCapabilities.localActive();
 }

 private static final Map<String,Function<ResourceLocation,RenderType>> TEXTURED=Map.ofEntries(
  Map.entry("entity_solid",RenderType::entitySolid),Map.entry("entity_cutout",RenderType::entityCutout),
  Map.entry("entity_cutout_no_cull",RenderType::entityCutoutNoCull),Map.entry("entity_cutout_no_cull_z_offset",RenderType::entityCutoutNoCullZOffset),
  Map.entry("item_entity_translucent_cull",RenderType::itemEntityTranslucentCull),Map.entry("entity_translucent_cull",RenderType::entityTranslucentCull),
  Map.entry("entity_translucent",RenderType::entityTranslucent),Map.entry("entity_translucent_emissive",RenderType::entityTranslucentEmissive),
  Map.entry("entity_smooth_cutout",RenderType::entitySmoothCutout),Map.entry("entity_decal",RenderType::entityDecal),
  Map.entry("entity_no_outline",RenderType::entityNoOutline),Map.entry("entity_shadow",RenderType::entityShadow),
  Map.entry("entity_alpha",RenderType::dragonExplosionAlpha),Map.entry("eyes",RenderType::eyes),
  Map.entry("armor_cutout_no_cull",RenderType::armorCutoutNoCull),Map.entry("armor_decal_cutout_no_cull",RenderType::createArmorDecalCutoutNoCull),
  Map.entry("text",RenderType::text),Map.entry("text_intensity",RenderType::textIntensity),Map.entry("text_polygon_offset",RenderType::textPolygonOffset),
  Map.entry("text_intensity_polygon_offset",RenderType::textIntensityPolygonOffset),Map.entry("text_see_through",RenderType::textSeeThrough),
  Map.entry("text_intensity_see_through",RenderType::textIntensitySeeThrough));
 private static final Map<String,Supplier<RenderType>> UNTEXTURED=Map.ofEntries(
  Map.entry("lines",RenderType::lines),Map.entry("line_strip",RenderType::lineStrip),Map.entry("leash",RenderType::leash),
  Map.entry("lightning",RenderType::lightning),Map.entry("water_mask",RenderType::waterMask),Map.entry("glint",RenderType::glint),
  Map.entry("glint_translucent",RenderType::glintTranslucent),Map.entry("entity_glint",RenderType::entityGlint),
  Map.entry("entity_glint_direct",RenderType::entityGlintDirect),Map.entry("armor_entity_glint",RenderType::armorEntityGlint),
  Map.entry("text_background",RenderType::textBackground),Map.entry("text_background_see_through",RenderType::textBackgroundSeeThrough),
  Map.entry("dragon_rays",RenderType::dragonRays),Map.entry("dragon_rays_depth",RenderType::dragonRaysDepth),
  Map.entry("end_portal",RenderType::endPortal),Map.entry("end_gateway",RenderType::endGateway));

 /**
  * This runtime's render type for a received batch: the vanilla type of the same name with the received texture, a
  * chunk-layer copy for block layers, or by vertex format for unknown names. Null while the texture has not arrived
  * (retried next frame) or when nothing equivalent exists (remembered).
  */
 private static RenderType dynamicType(Minecraft mc,String name,String texture,VertexFormat format,VertexFormat.Mode mode) {
  String key=name+"|"+texture+"|"+DynamicCapture.formatName(format)+"|"+mode;
  var cached=dynamicTypes.get(key);
  if(cached!=null) return cached.orElse(null);
  RenderType type=null;
  var untextured=UNTEXTURED.get(name);
  if(untextured!=null) type=untextured.get();
  else if(!texture.isEmpty()) {
   ResourceLocation location=textureLocation(mc,texture);
   if(location==null) return null;
   int id=mc.getTextureManager().getTexture(location).getId();
   if(chunkType(name)!=null) type=remoteType(name.equals("translucent_moving_block")?"translucent":name,id);
   else if(name.equals("terrain_sheet") || name.startsWith("particle_sheet_")) type=particleType(name,id);
   else if(TEXTURED.containsKey(name)) type=TEXTURED.get(name).apply(location);
   else if(format==DefaultVertexFormat.NEW_ENTITY) type=RenderType.entityTranslucent(location);
   else if(format==DefaultVertexFormat.BLOCK) type=remoteType("translucent",id);
  }
  if(type!=null && (type.format()!=format || type.mode()!=mode)) type=null;
  if(type==null) MmlfLog.info("render.submission","dynamic_type_unsupported",Map.of("renderType",name,"texture",texture,"format",String.valueOf(DynamicCapture.formatName(format)),"mode",mode.name()));
  dynamicTypes.put(key,Optional.ofNullable(type));
  return type;
 }

 /** A received texture's local name, loading or refreshing it from its channel; null until it has arrived. */
 private static ResourceLocation textureLocation(Minecraft mc,String key) {
  if(key.equals(BLOCK_ATLAS)) return atlas!=null ? atlasLocation : null;
  long now=System.nanoTime();
  Long polled=texturePolls.get(key);
  if(polled==null || now-polled>250_000_000L) {
   texturePolls.put(key,now);
   try {
    byte[] bytes=RenderChannel.poll(background(),DynamicCapture.textureChannel(key),textureCursors.computeIfAbsent(key,k->new RenderChannel.Cursor()),RenderTextureCodec.MAX_BYTES+65536);
    if(bytes!=null) {
     var texture=RenderTextureCodec.decode(bytes);
     byte[] data=texture.data();
     if(texture.runtime().equals(background()) && texture.resource().equals(key) && texture.format().equals("rgba8") && (long)texture.width()*texture.height()*4==data.length) {
      var local=textures.get(key);
      if(local==null) {
       local=new RemoteTexture(false);
       var location=ResourceLocation.fromNamespaceAndPath("mmlf","remote/"+background()+"/"+DynamicCapture.textureChannel(key).substring("texture/".length()));
       mc.getTextureManager().register(location,local);
       textures.put(key,local); textureLocations.put(key,location);
      }
      local.upload(texture.width(),texture.height(),data);
     }
    }
   } catch(IOException | RuntimeException e) { MmlfLog.sampled("render.submission","dynamic_texture_failed",5000,Map.of("texture",key,"exception",e.toString())); }
  }
  return textureLocations.get(key);
 }
 /** Keeps refreshed textures current: the submitter republishes small textures (glyph pages, maps) periodically. */
 private static void refreshTextures(Minecraft mc) { for(String key:new ArrayList<>(textureLocations.keySet())) textureLocation(mc,key); }

 /** The vanilla chunk layer's state with a texture of the source runtime in place of this runtime's block atlas. */
 private static RenderType remoteType(String pass,int texture) {
  return remoteTypes.computeIfAbsent(pass+"@"+texture,k->{
   RenderType base=chunkType(pass);
   boolean translucent=pass.equals("translucent") || pass.equals("tripwire");
   // Translucent layers are sorted against this camera when the buffer source draws them, like translucent entities.
   return new RenderType("mmlf_remote_"+pass,DefaultVertexFormat.BLOCK,VertexFormat.Mode.QUADS,base.bufferSize(),true,translucent,
    ()->{ base.setupRenderState(); RenderSystem.setShaderTexture(0,texture); },base::clearRenderState) {};
  });
 }

 /**
  * Particles of the submitter, drawn like vanilla's particle sheets (particle shader, lightmap, the sheet's texture);
  * vanilla has no render type for them, so this is the state its {@code ParticleRenderType} sets up.
  */
 private static RenderType particleType(String sheet,int texture) {
  return remoteTypes.computeIfAbsent(sheet+"@"+texture,k->{
   boolean translucent=!sheet.equals("particle_sheet_opaque") && !sheet.equals("terrain_sheet");
   var light=Minecraft.getInstance().gameRenderer.lightTexture();
   return new RenderType("mmlf_remote_"+sheet,DefaultVertexFormat.PARTICLE,VertexFormat.Mode.QUADS,1536,false,translucent,()->{
    RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getParticleShader);
    RenderSystem.setShaderTexture(0,texture);
    RenderSystem.enableDepthTest(); RenderSystem.depthMask(true);
    if(translucent) { RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); } else RenderSystem.disableBlend();
    light.turnOnLightLayer();
   },()->{ light.turnOffLightLayer(); RenderSystem.disableBlend(); RenderSystem.defaultBlendFunc(); }) {};
  });
 }

 /**
  * Render host: draws everything received, from the stand-in's renderer in whichever pass is drawing entities now.
  * {@code poseStack} is at the stand-in's position, in that pass's own space; {@code frustum} is that pass's.
  */
 static void drawProxy(double px,double py,double pz,Frustum frustum,PoseStack poseStack,MultiBufferSource buffers) {
  long started=System.nanoTime();
  var pose=poseStack.last(); Matrix4f matrix=pose.pose(); Matrix3f normalMatrix=pose.normal();
  Vector3f position=new Vector3f(),normal=new Vector3f();
  int meshes=0,vertices=0;
  if(atlasCurrent()) for(String pass:PASSES) {
   VertexConsumer out=null;
   for(Mesh mesh:staticByPass.getOrDefault(pass,List.of())) {
    long key=mesh.section();
    if(TerrainModels.enabled() && TerrainMeshes.hasSection(key)) continue;
    int x=SectionPos.sectionToBlockCoord(SectionPos.x(key)),y=SectionPos.sectionToBlockCoord(SectionPos.y(key)),z=SectionPos.sectionToBlockCoord(SectionPos.z(key));
    if(frustum!=null && !frustum.isVisible(new AABB(x,y,z,x+16,y+16,z+16))) continue;
    if(out==null) out=buffers.getBuffer(remoteType(pass,atlas.getId()));
    writeBlock(out,mesh,matrix,normalMatrix,(float)(x-px),(float)(y-py),(float)(z-pz),position,normal);
    meshes++; vertices+=mesh.count();
   }
  }
  float dx=(float)(dynamicX-px),dy=(float)(dynamicY-py),dz=(float)(dynamicZ-pz);
  for(Draw draw:dynamicDraws) {
   writeGeneric(buffers.getBuffer(draw.type()),draw,matrix,normalMatrix,dx,dy,dz,position,normal,-1,-1);
   vertices+=draw.count();
  }
  long now=System.nanoTime();
  lastDrawNanos=now;
  drawNanos+=now-started; drawVertices+=vertices;
  if(++drawCount%600==0) {
   // More than one draw per world frame means other passes (a shader pack's shadow pass) draw the stand-in too.
   double perFrame=worldFrames==framesAtStats ? 0 : 600.0/(worldFrames-framesAtStats);
   MmlfLog.info("render.submission","geometry_emit",Map.of("draws",drawCount,"drawsPerWorldFrame",perFrame,"cpuMicrosPerDraw",drawNanos/1000/600,"verticesPerDraw",drawVertices/600,"dynamicBatches",dynamicDraws.size()));
   drawNanos=0; drawVertices=0; framesAtStats=worldFrames;
  }
  if(!replayReported && meshes+dynamicDraws.size()>0) {
   replayReported=true;
   MmlfLog.info("render.submission","geometry_replay",Map.of("runtime",background(),"sections",meshes,"dynamicBatches",dynamicDraws.size(),"path","entity_proxy"));
  }
 }

 private static void writeBlock(VertexConsumer out,Mesh mesh,Matrix4f matrix,Matrix3f normalMatrix,float dx,float dy,float dz,Vector3f position,Vector3f normal) {
  ByteBuffer v=mesh.vertices();
  for(int i=0;i<mesh.count();i++) {
   int o=i*32;
   matrix.transformPosition(v.getFloat(o)+dx,v.getFloat(o+4)+dy,v.getFloat(o+8)+dz,position);
   normalMatrix.transform(v.get(o+28)/127f,v.get(o+29)/127f,v.get(o+30)/127f,normal);
   if(normal.lengthSquared()>1e-12f) normal.normalize();
   int color=(v.get(o+15)&0xff)<<24|(v.get(o+12)&0xff)<<16|(v.get(o+13)&0xff)<<8|(v.get(o+14)&0xff);
   int light=(v.getShort(o+26)&0xffff)<<16|(v.getShort(o+24)&0xffff);
   out.addVertex(position.x,position.y,position.z,color,v.getFloat(o+16),v.getFloat(o+20),OverlayTexture.NO_OVERLAY,light,normal.x,normal.y,normal.z);
  }
 }

 /** A received mesh resolved to this runtime's render types, drawn wherever and as often as needed. */
 public static final class Resolved { private final List<Draw> draws; private Resolved(List<Draw> draws) { this.draws=draws; } }
 /** Null while a texture it needs has not arrived; batches without any equivalent here are left out. */
 public static Resolved resolveBatches(List<RenderDynamicCodec.Batch> batches) {
  var mc=Minecraft.getInstance(); var draws=new ArrayList<Draw>(batches.size());
  for(var batch:batches) {
   VertexFormat format=DynamicCapture.FORMATS.get(batch.format());
   VertexFormat.Mode mode;
   try { mode=VertexFormat.Mode.valueOf(batch.mode()); } catch(IllegalArgumentException e) { continue; }
   if(format==null || (long)batch.vertexCount()*format.getVertexSize()!=batch.vertices().length) continue;
   RenderType type=dynamicType(mc,batch.renderType(),batch.texture(),format,mode);
   if(type==null) {
    if(dynamicTypes.containsKey(batch.renderType()+"|"+batch.texture()+"|"+DynamicCapture.formatName(format)+"|"+mode)) continue; // unsupported
    return null; // texture still missing
   }
   draws.add(new Draw(type,format,ByteBuffer.wrap(batch.vertices()).order(ByteOrder.LITTLE_ENDIAN),batch.vertexCount(),offsets(format)));
  }
  return new Resolved(draws);
 }
 /** Draws a resolved mesh at {@code pose}, with this runtime's light and overlay for this draw. */
 public static void drawResolved(Resolved mesh,PoseStack.Pose pose,MultiBufferSource buffers,int light,int overlay) {
  Vector3f position=new Vector3f(),normal=new Vector3f();
  for(Draw draw:mesh.draws) writeGeneric(buffers.getBuffer(draw.type()),draw,pose.pose(),pose.normal(),0,0,0,position,normal,light,overlay);
 }

 /** Byte offsets of position, colour, UV0, UV1, UV2 and normal in {@code format}; -1 where absent. */
 private static int[] offsets(VertexFormat format) {
  VertexFormatElement[] elements={VertexFormatElement.POSITION,VertexFormatElement.COLOR,VertexFormatElement.UV0,VertexFormatElement.UV1,VertexFormatElement.UV2,VertexFormatElement.NORMAL};
  int[] offsets=new int[elements.length];
  for(int i=0;i<elements.length;i++) offsets[i]=format.contains(elements[i])?format.getOffset(elements[i]):-1;
  return offsets;
 }
 /** Re-emits a vertex batch element by element, so the destination (and any shader mod's extended format) fills its own layout. */
 /** {@code light} / {@code overlay} replace the recorded values when not negative. */
 private static void writeGeneric(VertexConsumer out,Draw draw,Matrix4f matrix,Matrix3f normalMatrix,float dx,float dy,float dz,Vector3f position,Vector3f normal,int light,int overlay) {
  ByteBuffer v=draw.vertices(); int stride=draw.format().getVertexSize(); int[] at=draw.offsets();
  for(int i=0;i<draw.count();i++) {
   int o=i*stride;
   int p=o+at[0];
   matrix.transformPosition(v.getFloat(p)+dx,v.getFloat(p+4)+dy,v.getFloat(p+8)+dz,position);
   out.addVertex(position.x,position.y,position.z);
   if(at[1]>=0) { int c=o+at[1]; out.setColor(v.get(c)&0xff,v.get(c+1)&0xff,v.get(c+2)&0xff,v.get(c+3)&0xff); }
   if(at[2]>=0) out.setUv(v.getFloat(o+at[2]),v.getFloat(o+at[2]+4));
   if(at[3]>=0) { if(overlay>=0) out.setOverlay(overlay); else out.setUv1(v.getShort(o+at[3]),v.getShort(o+at[3]+2)); }
   if(at[4]>=0) { if(light>=0) out.setLight(light); else out.setUv2(v.getShort(o+at[4]),v.getShort(o+at[4]+2)); }
   if(at[5]>=0) {
    normalMatrix.transform(v.get(o+at[5])/127f,v.get(o+at[5]+1)/127f,v.get(o+at[5]+2)/127f,normal);
    if(normal.lengthSquared()>1e-12f) normal.normalize();
    out.setNormal(normal.x,normal.y,normal.z);
   }
  }
 }

 /**
  * Render host: tells the submitter it draws its content of the accepted generation, so the submitter may leave it
  * out of its own frame. Sent only while the stand-in is actually drawn and the atlas matches.
  */
 private static void confirmConsumption(Minecraft mc) {
  refreshTextures(mc);
  long now=System.nanoTime();
  // Ready for everything submitted: the atlas matches, dynamic frames arrive, and the stand-in is being drawn
  // (or there is nothing to draw, in which case the submitter's own world is redundant too).
  boolean drawing=now-lastDrawNanos<1_000_000_000L || staticByPass.isEmpty() && dynamicDraws.isEmpty();
  if(!Experiments.GEOMETRY_REPLAY || acceptedGeneration<0 || !atlasCurrent() || now-lastDynamicNanos>1_000_000_000L
    || !drawing || now-lastConsumedSignal<1_000_000_000L) return;
  lastConsumedSignal=now;
  BridgeCore.send("event",background(),new dev.mmlf.common.Messages.Event("mmlf:geometry_consumed",
   Map.of("generation",Long.toString(acceptedGeneration),"dimension",mc.level.dimension().location().toString())));
 }
 /** Submitter: the render host's confirmation, accepted only from the current host and for this dimension. */
 public static void receiveConsumed(String source,Map<String,String> values) {
  if(values==null || isRenderHost() || !Objects.equals(source,renderHost())) return;
  var mc=Minecraft.getInstance();
  try {
   long generation=Long.parseLong(values.get("generation"));
   String dimension=values.get("dimension");
   mc.execute(()->{ if(mc.level!=null && mc.level.dimension().location().toString().equals(dimension)) OwnedSectionCompiler.acknowledged(generation); });
  } catch(NumberFormatException | NullPointerException ignored) { }
 }
}
