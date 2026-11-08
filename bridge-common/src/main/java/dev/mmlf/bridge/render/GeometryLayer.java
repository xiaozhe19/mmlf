package dev.mmlf.bridge.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.common.MmlfLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geometry-layer prototype (split-runtime manual §6.2, step 1, experiment {@code geometry}).
 * A background keeps a copy of every chunk-section mesh it compiles (already limited to blocks it owns) and publishes
 * them with its block atlas; the primary uploads them and draws them inside its own section layers, binding the
 * background's atlas so the UVs need no remapping. Research capture is retained, but partial geometry is not drawn
 * or used as a replacement for the complete remote frame until it covers all required render paths.
 */
public final class GeometryLayer {
 private GeometryLayer() {}
 private record Layer(String renderType,int vertexCount,byte[] vertices) {}
 private record Section(long capturedMs,List<Layer> layers) {}
 /** Background: section key -> latest compiled meshes, written by section compile workers. */
 private static final Map<Long,Section> captured=new ConcurrentHashMap<>();
 private static volatile long capturedVersion;
 private static long exportedVersion,lastExport,exports,exportBytesTotal;
 private static boolean atlasWritten,unsupportedLogged;
 /** Primary: uploaded remote sections by render type. */
 private record Uploaded(long section,VertexBuffer buffer,int vertices) {}
 private static final Map<String,List<Uploaded>> uploaded=new HashMap<>();
 private static long seenModified,lastPoll,drawNanos,drawCalls,drawFrames;
 private static int atlasTexture=-1;
 private static long atlasModified;

 private static Path file(String runtime,String suffix) { return BridgeCore.session().root().resolve(runtime+suffix); }
 private static String background() { return "fabric".equals(BridgeCore.primaryId())?"neoforge":"fabric"; }

 /** Called on a section compile worker with the compiler's results, before they are uploaded. */
 public static void captured(SectionPos section,Map<RenderType,MeshData> layers) {
  if(BridgeCore.isPrimary()) return;
  long started=System.nanoTime();
  var copies=new ArrayList<Layer>();
  for(var entry:layers.entrySet()) {
   MeshData.DrawState state=entry.getValue().drawState();
   if(state.format()!=DefaultVertexFormat.BLOCK || state.mode()!=VertexFormat.Mode.QUADS) {
    if(!unsupportedLogged) { unsupportedLogged=true; MmlfLog.info("experiment","geometry_unsupported",Map.of("renderType",entry.getKey().toString(),"format",state.format().toString(),"mode",state.mode().toString())); }
    continue;
   }
   ByteBuffer source=entry.getValue().vertexBuffer();
   byte[] bytes=new byte[state.vertexCount()*DefaultVertexFormat.BLOCK.getVertexSize()];
   source.duplicate().get(bytes);
   copies.add(new Layer(name(entry.getKey()),state.vertexCount(),bytes));
  }
  captured.put(section.asLong(),new Section(System.currentTimeMillis(),copies));
  capturedVersion++;
  MmlfLog.sampled("experiment","geometry_capture",2000,Map.of("section",section.toString(),"layers",copies.size(),"copyMicros",(System.nanoTime()-started)/1000));
 }
 private static String name(RenderType type) {
  return type==RenderType.solid()?"solid":type==RenderType.cutoutMipped()?"cutout_mipped":type==RenderType.cutout()?"cutout":type==RenderType.translucent()?"translucent":type==RenderType.tripwire()?"tripwire":type.toString();
 }
 private static RenderType type(String name) {
  return switch(name) { case "solid"->RenderType.solid(); case "cutout_mipped"->RenderType.cutoutMipped(); case "cutout"->RenderType.cutout(); case "translucent"->RenderType.translucent(); case "tripwire"->RenderType.tripwire(); default->null; };
 }

 /** Client tick on both sides: a background publishes, the primary polls. */
 public static void tick(Minecraft mc) {
  if(BridgeCore.session()==null || mc.level==null || !BridgeCore.connected()) return;
  if(BridgeCore.isPrimary()) { poll(); return; }
  if(!atlasWritten) writeAtlas(mc);
  long now=System.currentTimeMillis();
  long version=capturedVersion;
  if(version==exportedVersion || now-lastExport<250) return;
  exportedVersion=version; lastExport=now;
  long started=System.nanoTime();
  try {
   var bytes=new ByteArrayOutputStream();
   var out=new DataOutputStream(bytes);
   int sections=0,vertices=0; long newest=0;
   var snapshot=new HashMap<>(captured);
   for(Section section:snapshot.values()) if(!section.layers().isEmpty()) sections++;
   out.writeInt(0x4D4D4C47); out.writeLong(now); out.writeInt(sections);
   for(var entry:snapshot.entrySet()) {
    Section section=entry.getValue(); if(section.layers().isEmpty()) continue;
    newest=Math.max(newest,section.capturedMs());
    out.writeLong(entry.getKey()); out.writeLong(section.capturedMs()); out.writeInt(section.layers().size());
    for(Layer layer:section.layers()) { out.writeUTF(layer.renderType()); out.writeInt(layer.vertexCount()); out.writeInt(layer.vertices().length); out.write(layer.vertices()); vertices+=layer.vertexCount(); }
   }
   out.flush();
   Path target=file(BridgeCore.runtimeId(),".geometry"),temp=file(BridgeCore.runtimeId(),".geometry.tmp");
   Files.write(temp,bytes.toByteArray());
   Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
   exports++; exportBytesTotal+=bytes.size();
   MmlfLog.info("experiment","geometry_export",Map.of("sections",sections,"vertices",vertices,"bytes",bytes.size(),"exportMicros",(System.nanoTime()-started)/1000,
    "newestCaptureAgeMs",newest==0?-1:now-newest,"exports",exports,"bytesTotal",exportBytesTotal));
  } catch(IOException e) { MmlfLog.sampled("experiment","geometry_export_failed",2000,Map.of("exception",e.toString())); }
 }

 /** Raw RGBA of the background's block atlas, mip level 0. */
 private static void writeAtlas(Minecraft mc) {
  var texture=mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
  if(texture==null) return;
  RenderSystem.bindTexture(texture.getId());
  int width=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),height=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
  if(width<=0 || height<=0) return;
  ByteBuffer pixels=MemoryUtil.memAlloc(width*height*4);
  try {
   long started=System.nanoTime();
   GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
   byte[] data=new byte[width*height*4]; pixels.get(data);
   var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
   out.writeInt(width); out.writeInt(height); out.write(data); out.flush();
   Path target=file(BridgeCore.runtimeId(),".atlas"),temp=file(BridgeCore.runtimeId(),".atlas.tmp");
   Files.write(temp,bytes.toByteArray());
   Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
   atlasWritten=true;
   MmlfLog.info("experiment","geometry_atlas",Map.of("width",width,"height",height,"bytes",data.length,"micros",(System.nanoTime()-started)/1000));
  } catch(IOException e) { MmlfLog.sampled("experiment","geometry_atlas_failed",2000,Map.of("exception",e.toString())); }
  finally { MemoryUtil.memFree(pixels); }
 }

 private static void poll() {
  long now=System.currentTimeMillis();
  if(now-lastPoll<100) return;
  lastPoll=now;
  String runtime=background();
  try {
   Path atlas=file(runtime,".atlas");
   if(Files.isRegularFile(atlas) && Files.getLastModifiedTime(atlas).toMillis()!=atlasModified) loadAtlas(atlas);
   Path geometry=file(runtime,".geometry");
   if(!Files.isRegularFile(geometry)) return;
   long modified=Files.getLastModifiedTime(geometry).toMillis();
   if(modified==seenModified) return;
   seenModified=modified;
   load(Files.readAllBytes(geometry));
  } catch(IOException | RuntimeException e) { MmlfLog.sampled("experiment","geometry_poll_failed",2000,Map.of("exception",e.toString())); }
 }
 private static void loadAtlas(Path path) throws IOException {
  var in=new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(path)));
  int width=in.readInt(),height=in.readInt(); byte[] data=new byte[width*height*4]; in.readFully(data);
  ByteBuffer pixels=MemoryUtil.memAlloc(data.length).put(data).flip();
  if(atlasTexture<0) atlasTexture=GlStateManager._genTexture();
  RenderSystem.bindTexture(atlasTexture);
  GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH,0); GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS,0); GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS,0); GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT,4);
  GL11.glTexImage2D(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA8,width,height,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
  GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
  GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL12.GL_TEXTURE_MAX_LEVEL,4);
  GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,GL11.GL_NEAREST_MIPMAP_LINEAR);
  GL11.glTexParameteri(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,GL11.GL_NEAREST);
  MemoryUtil.memFree(pixels);
  atlasModified=Files.getLastModifiedTime(path).toMillis();
  MmlfLog.info("experiment","geometry_atlas_loaded",Map.of("width",width,"height",height));
 }
 private static void load(byte[] data) throws IOException {
  long started=System.nanoTime();
  var in=new DataInputStream(new ByteArrayInputStream(data));
  if(in.readInt()!=0x4D4D4C47) throw new IOException("bad geometry magic");
  long exportedMs=in.readLong(); int sections=in.readInt();
  for(var list:uploaded.values()) for(Uploaded u:list) u.buffer().close();
  uploaded.clear();
  int vertices=0; long newest=0;
  ByteBufferBuilder scratch=new ByteBufferBuilder(1<<16);
  try {
   for(int s=0;s<sections;s++) {
    long key=in.readLong(); long capturedMs=in.readLong(); int layers=in.readInt(); newest=Math.max(newest,capturedMs);
    for(int l=0;l<layers;l++) {
     String name=in.readUTF(); int count=in.readInt(); byte[] bytes=new byte[in.readInt()]; in.readFully(bytes);
     RenderType type=type(name); if(type==null || count==0) continue;
     ByteBuffer v=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
     BufferBuilder builder=new BufferBuilder(scratch,VertexFormat.Mode.QUADS,DefaultVertexFormat.BLOCK);
     for(int i=0;i<count;i++) {
      int o=i*32;
      int color=(v.get(o+15)&0xff)<<24|(v.get(o+12)&0xff)<<16|(v.get(o+13)&0xff)<<8|(v.get(o+14)&0xff);
      int light=(v.getShort(o+26)&0xffff)<<16|(v.getShort(o+24)&0xffff);
      builder.addVertex(v.getFloat(o),v.getFloat(o+4),v.getFloat(o+8),color,v.getFloat(o+16),v.getFloat(o+20),0,light,v.get(o+28)/127f,v.get(o+29)/127f,v.get(o+30)/127f);
     }
     MeshData mesh=builder.build(); if(mesh==null) continue;
     VertexBuffer buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);
     buffer.bind(); buffer.upload(mesh); VertexBuffer.unbind();
     uploaded.computeIfAbsent(name,k->new ArrayList<>()).add(new Uploaded(key,buffer,count));
     vertices+=count;
    }
   }
  } finally { scratch.close(); }
  long now=System.currentTimeMillis();
  MmlfLog.info("experiment","geometry_upload",Map.of("sections",sections,"vertices",vertices,"bytes",data.length,"uploadMicros",(System.nanoTime()-started)/1000,
   "exportToUploadMs",now-exportedMs,"captureToUploadMs",newest==0?-1:now-newest));
 }

 /** Called at the end of the primary's LevelRenderer.renderSectionLayer, with that call's arguments. */
 public static void draw(RenderType renderType,double cameraX,double cameraY,double cameraZ,Matrix4f modelView,Matrix4f projection) {
  if(!BridgeCore.isPrimary() || atlasTexture<0) return;
  if(renderType==RenderType.solid() && ++drawFrames%600==0) {
   MmlfLog.info("experiment","geometry_draw",Map.of("frames",drawFrames,"cpuMicrosPerFrame",drawNanos/1000/600,"drawCallsPerFrame",drawCalls/600.0));
   drawNanos=0; drawCalls=0;
  }
  List<Uploaded> list=uploaded.get(name(renderType)); if(list==null || list.isEmpty()) return;
  long started=System.nanoTime();
  renderType.setupRenderState();
  RenderSystem.setShaderTexture(0,atlasTexture);
  var shader=RenderSystem.getShader();
  shader.setDefaultUniforms(VertexFormat.Mode.QUADS,modelView,projection,Minecraft.getInstance().getWindow());
  shader.apply();
  var offset=shader.CHUNK_OFFSET;
  for(Uploaded u:list) {
   if(offset!=null) {
    long key=u.section();
    offset.set((float)(SectionPos.sectionToBlockCoord(SectionPos.x(key))-cameraX),(float)(SectionPos.sectionToBlockCoord(SectionPos.y(key))-cameraY),(float)(SectionPos.sectionToBlockCoord(SectionPos.z(key))-cameraZ));
    offset.upload();
   }
   u.buffer().bind(); u.buffer().draw(); drawCalls++;
  }
  if(offset!=null) offset.set(0f,0f,0f);
  shader.clear(); VertexBuffer.unbind();
  renderType.clearRenderState();
  drawNanos+=System.nanoTime()-started;
 }
}
