package dev.mmlf.bridge;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import java.nio.*;
import java.util.*;

/**
 * Transfers native first-person and item-GUI pixels. No foreign resource or model is registered.
 * The owner publishes each image with its {@link Visual} metadata in a shared slot; the primary polls the owner's file.
 */
public final class VisualLayers {
 private record Crop(ByteBuffer pixels,int x,int y,int width,int height) {}
 /** The primary's view of one owner's hand or icon file. */
 private static final class Source {
  final String owner; final FrameFile file; FrameFile.Latest cursor; long polled;
  Source(String owner,FrameFile file) {this.owner=owner;this.file=file;}
  void close() {try {file.close();} catch(java.io.IOException ignored) {}}
 }
 private static FrameFile handWriter,iconWriter;
 private static Source handSource;
 private static final Map<String,Source> iconSources=new HashMap<>();
 private static final Map<String,Long> nextOpen=new HashMap<>();
 private static Visual completeHand; private static long handArrived;
 private static FrameFile.Pixels handPixels,handSpare;
 private record Icon(DynamicTexture texture,ResourceLocation id,long frame) {}
 private static final Map<String,Icon> icons=new LinkedHashMap<>();
 private static ByteBuffer color,handCrop,zeroDepth,localColor,iconColor;
 private static TextureTarget iconTarget;
 private static final int ICON_PIXELS=32*32;
 private static long sequence,lastIcon,lastHand;
 private static String lastIconReference="";
 private static boolean handExportReady,handCompositeReady,iconExportReady,iconReceivedReady;
 private VisualLayers() {}
 private static String held() {
  var mc=Minecraft.getInstance();return mc.player==null?"minecraft:air":RemoteItems.held(mc.player.getMainHandItem());
 }
 public static boolean replacePrimaryHand() {return BridgeCore.isPrimary() && RemoteItemRef.parse(held()).isPresent();}
 private static byte[] encode(Visual visual) {return Wire.JSON.toJson(visual).getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 /** Null when the metadata does not describe an image of {@code owner}'s own item. */
 private static Visual decode(FrameFile.Latest latest,String owner) {
  try {
   Visual visual=Wire.JSON.fromJson(new String(latest.metadata(),java.nio.charset.StandardCharsets.UTF_8),Visual.class);
   if(visual==null || visual.frame()==null || !owner.equals(visual.frame().runtime()) || visual.frame().frameId()!=latest.frameId()
    || visual.frame().width()!=latest.width() || visual.frame().height()!=latest.height()) return null;
   var reference=RemoteItemRef.parse(visual.reference());
   return reference.isPresent() && reference.get().owner().equals(owner) ? visual : null;
  } catch(RuntimeException e) {return null;}
 }
 /** Opens an owner's file once it exists; a missing file is retried at most twice a second. */
 private static Source open(String owner,String suffix,int maxPixels) {
  String key=owner+suffix;long now=System.nanoTime();Long due=nextOpen.get(key);
  if(due!=null && now-due<0) return null;
  nextOpen.put(key,now+500_000_000L);
  try {return new Source(owner,FrameFile.reader(BridgeCore.session().framePath(key),maxPixels));}
  catch(java.io.IOException | IllegalArgumentException e) {MmlfLog.sampled("render.visual","file_unavailable",5000,Map.of("file",key,"reason",e.toString()));return null;}
 }
 public static void beforeHand() {
  if(!RemoteItems.nativeHandSelected()) return;
  // World colour/depth have already been copied. Isolate the following native hand pass.
  float[] previous=new float[4];GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE,previous);
  boolean scissor=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
  ByteBuffer mask=BufferUtils.createByteBuffer(4);GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK,mask);
  GL11.glDisable(GL11.GL_SCISSOR_TEST);GL11.glColorMask(true,true,true,true);
  GL11.glClearColor(0,0,0,0);GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
  GL11.glClearColor(previous[0],previous[1],previous[2],previous[3]);
  GL11.glColorMask(mask.get(0)!=0,mask.get(1)!=0,mask.get(2)!=0,mask.get(3)!=0);
  if(scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
 }
 public static void afterHand() {
  var mc=Minecraft.getInstance();if(BridgeCore.session()==null || mc.player==null) return;
  if(BridgeCore.isPrimary()) {compositeHand();return;}
  renderRequestedIcon();
  if(!RemoteItems.nativeHandSelected()) return;
  // Until the mirrored inventory has put the selected stack in this hand, a frame would show the wrong hand.
  if(InventoryBridge.heldProblem(mc.player.getMainHandItem(),RemoteItems.selectedReference())!=null) return;
  long now=System.nanoTime();
  if(now-lastHand<33_000_000L) return;
  lastHand=now;
  try(PixelTransfer transfer=new PixelTransfer()) {
   var target=mc.getMainRenderTarget();int n=target.width*target.height*4;
   if(color==null || color.capacity()<n) color=BufferUtils.createByteBuffer(n);
   color.clear().limit(n);
   GL11.glReadPixels(0,0,target.width,target.height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,color);
   Crop crop=crop(color,target.width,target.height);
   if(crop==null) return;
   int cropBytes=crop.width()*crop.height()*4;
   if(zeroDepth==null || zeroDepth.capacity()<cropBytes) zeroDepth=BufferUtils.createByteBuffer(cropBytes);
   zeroDepth.clear().limit(cropBytes);
   // Crop offsets are only meaningful while this framebuffer matches the primary's.
   Pose pose=BridgeCore.renderedPose();
   if(pose==null || pose.width()!=target.width || pose.height()!=target.height) return;
   if(handWriter==null) handWriter=FrameFile.writer(BridgeCore.session().framePath(BridgeCore.runtimeId()+"-hand"));
   long id=++sequence;
   Visual visual=new Visual(new Frame(BridgeCore.runtimeId(),id,pose,-1,crop.width(),crop.height(),System.nanoTime(),true,"overlay"),RemoteItems.selectedReference(),crop.x(),crop.y());
   int slot=handWriter.publish(id,crop.width(),crop.height(),crop.pixels(),zeroDepth,encode(visual));
   if(slot>=0 && !handExportReady) {handExportReady=true;MmlfLog.info("render.hand","native_export_ready",Map.of("width",crop.width(),"height",crop.height()));}
   MmlfLog.sampled("render.hand","native_export",1000,Map.of("frameId",id,"slot",slot,"x",crop.x(),"y",crop.y(),"width",crop.width(),"height",crop.height()));
   renderIconNative();
  } catch(Exception e) {MmlfLog.error("render.hand","export_failed",Map.of("exception",e.toString()));}
 }
 private static void compositeHand() {
  if(!replacePrimaryHand()) return;
  String reference=held();String owner=RemoteItemRef.parse(reference).orElseThrow().owner();
  try(PixelTransfer transfer=new PixelTransfer()) {
   if(handSource==null || !handSource.owner.equals(owner)) {
    if(handSource!=null) handSource.close();
    handSource=open(owner,"-hand",FrameFile.MAX_PIXELS);completeHand=null;
    if(handSource==null) return;
   }
   var latest=handSource.file.readLatest(handSource.cursor,handSpare);
   if(latest!=null) {
    handSource.cursor=latest;Visual visual=decode(latest,owner);
    if(visual!=null) {handSpare=handPixels;handPixels=latest.pixels();completeHand=visual;handArrived=System.nanoTime();}
   }
   // Right after a hotbar switch the owner's hand still shows the previous stack for a frame or two; keeping that
   // recent frame avoids the hand blinking out, while an old frame of another item is never shown.
   if(completeHand==null || !reference.equals(completeHand.reference()) && System.nanoTime()-handArrived>250_000_000L) return;
   var mc=Minecraft.getInstance();var target=mc.getMainRenderTarget();Frame frame=completeHand.frame();
   // Crop offsets are in the owner's framebuffer; they are only valid while both framebuffers have the same size.
   Pose source=frame.pose();
   if(source==null || source.width()!=target.width || source.height()!=target.height) {MmlfLog.sampled("render.hand","size_mismatch",1000,Map.of("width",target.width,"height",target.height,"sourceWidth",source==null?-1:source.width(),"sourceHeight",source==null?-1:source.height()));return;}
   if(completeHand.x()<0 || completeHand.y()<0 || completeHand.x()+frame.width()>target.width || completeHand.y()+frame.height()>target.height) return;
   int n=frame.width()*frame.height()*4;
   if(localColor==null || localColor.capacity()<n) localColor=BufferUtils.createByteBuffer(n);
   localColor.clear().limit(n);
   GL11.glReadPixels(completeHand.x(),completeHand.y(),frame.width(),frame.height(),GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,localColor);
   PixelCompositor.overlay(localColor,handPixels.color());
   int bound=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
   try {GL11.glBindTexture(GL11.GL_TEXTURE_2D,target.getColorTextureId());GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D,0,completeHand.x(),completeHand.y(),frame.width(),frame.height(),GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,localColor);}
   finally {GL11.glBindTexture(GL11.GL_TEXTURE_2D,bound);}
   if(!handCompositeReady) {handCompositeReady=true;MmlfLog.info("render.hand","native_composite_ready",Map.of("owner",frame.runtime()));}
   MmlfLog.sampled("render.hand","native_composite",1000,Map.of("frameId",frame.frameId(),"owner",frame.runtime()));
  } catch(Exception e) {MmlfLog.error("render.hand","composite_failed",Map.of("exception",e.toString()));}
 }
 private static Crop crop(ByteBuffer source,int width,int height) {
  int minX=width,minY=height,maxX=-1,maxY=-1;
  for(int y=0;y<height;y++) for(int x=0;x<width;x++) if((source.get((y*width+x)*4+3)&255)!=0) {
   minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
  }
  if(maxX<minX) return null;
  int padding=2;xClamp: {
   minX=Math.max(0,minX-padding);minY=Math.max(0,minY-padding);maxX=Math.min(width-1,maxX+padding);maxY=Math.min(height-1,maxY+padding);
  }
  int cropWidth=maxX-minX+1,cropHeight=maxY-minY+1,n=cropWidth*cropHeight*4;
  if(handCrop==null || handCrop.capacity()<n) handCrop=BufferUtils.createByteBuffer(n);
  handCrop.clear().limit(n);
  for(int y=minY;y<=maxY;y++) {
   ByteBuffer row=source.duplicate();row.position((y*width+minX)*4).limit((y*width+maxX+1)*4);handCrop.put(row);
  }
  handCrop.flip();return new Crop(handCrop,minX,minY,cropWidth,cropHeight);
 }
 /** Owner: icons the primary asked for (items shown in its inventory), at most one per frame. */
 private static final Set<String> requestedIcons=new LinkedHashSet<>();
 public static void requestIcons(String references) {
  if(references==null) return;
  synchronized(requestedIcons) {
   for(String reference:references.split(",")) {
    try {var ref=RemoteItemRef.parse(reference);if(ref.isPresent() && ref.get().owner().equals(BridgeCore.runtimeId()) && requestedIcons.size()<64) requestedIcons.add(reference);}
    catch(IllegalArgumentException ignored) {}
   }
  }
 }
 private static void renderRequestedIcon() {
  String reference;
  synchronized(requestedIcons) {if(requestedIcons.isEmpty()) return;var it=requestedIcons.iterator();reference=it.next();it.remove();}
  var stack=RemoteItems.nativeStack(RemoteItemRef.parse(reference).orElseThrow().token());
  if(stack==null || stack.isEmpty()) return;
  try(PixelTransfer transfer=new PixelTransfer()) {renderIcon(stack,reference);}
  catch(Exception e) {MmlfLog.error("render.icon","requested_failed",Map.of("exception",e.toString()));}
 }
 private static void renderIconNative() throws Exception {
  String reference=RemoteItems.selectedReference();long now=System.nanoTime();
  if(reference.equals(lastIconReference) && now-lastIcon<250_000_000L) return;
  lastIconReference=reference;lastIcon=now;
  var mc=Minecraft.getInstance();var stack=mc.player.getMainHandItem();
  if(stack.isEmpty()) return;
  renderIcon(stack,reference);
 }
 private static void renderIcon(ItemStack stack,String reference) throws Exception {
  var mc=Minecraft.getInstance();
  if(iconTarget==null) {iconTarget=new TextureTarget(32,32,true,Minecraft.ON_OSX);iconTarget.setClearColor(0,0,0,0);iconColor=BufferUtils.createByteBuffer(32*32*4);}
  Matrix4f previous=new Matrix4f(RenderSystem.getProjectionMatrix());var sorting=RenderSystem.getVertexSorting();
  try {
   iconTarget.clear(Minecraft.ON_OSX);iconTarget.bindWrite(true);
   RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0,16,16,0,-1000,1000),VertexSorting.ORTHOGRAPHIC_Z);
   // Runs after the level pass: GUI item models expect GUI lighting, no world fog and an identity model-view, as before the hotbar.
   var modelView=RenderSystem.getModelViewStack();modelView.pushMatrix();modelView.identity();RenderSystem.applyModelViewMatrix();
   net.minecraft.client.renderer.FogRenderer.setupNoFog();com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
   try {var graphics=new GuiGraphics(mc,mc.renderBuffers().bufferSource());graphics.renderItem(stack,0,0);graphics.flush();}
   finally {modelView.popMatrix();RenderSystem.applyModelViewMatrix();}
   iconColor.clear();GL11.glReadPixels(0,0,32,32,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,iconColor);
  } finally {RenderSystem.setProjectionMatrix(previous,sorting);mc.getMainRenderTarget().bindWrite(true);}
  if(iconWriter==null) iconWriter=FrameFile.writer(BridgeCore.session().framePath(BridgeCore.runtimeId()+"-icon"),ICON_PIXELS);
  ByteBuffer empty=BufferUtils.createByteBuffer(32*32*4);
  long id=++sequence;
  Visual visual=new Visual(new Frame(BridgeCore.runtimeId(),id,BridgeCore.renderedPose(),-1,32,32,System.nanoTime(),true,"overlay"),reference,0,0);
  int slot=iconWriter.publish(id,32,32,iconColor,empty,encode(visual));
  if(slot>=0 && !iconExportReady) {iconExportReady=true;MmlfLog.info("render.icon","native_export_ready",Map.of("width",32,"height",32));}
  MmlfLog.sampled("render.icon","native_export",1000,Map.of("frameId",id,"slot",slot));
 }
 /**
  * Owner: every stack's native GUI icon in one atlas, {@code columns} cells of {@code cell} pixels per row, read back
  * once (bottom row first). Used for a creative catalogue, so the primary has all icons before it shows the tabs.
  */
 static ByteBuffer renderAtlas(List<ItemStack> stacks,int cell,int columns,int width,int height) {
  var mc=Minecraft.getInstance();
  var target=new TextureTarget(width,height,true,Minecraft.ON_OSX);
  ByteBuffer pixels=BufferUtils.createByteBuffer(width*height*4);
  Matrix4f previous=new Matrix4f(RenderSystem.getProjectionMatrix());var sorting=RenderSystem.getVertexSorting();
  try(PixelTransfer transfer=new PixelTransfer()) {
   target.setClearColor(0,0,0,0);target.clear(Minecraft.ON_OSX);target.bindWrite(true);
   // GUI units of 16 per cell: an item renders at cell/16 pixels per unit.
   float scale=cell/16f;
   RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0,width/scale,height/scale,0,-1000,1000),VertexSorting.ORTHOGRAPHIC_Z);
   var modelView=RenderSystem.getModelViewStack();modelView.pushMatrix();modelView.identity();RenderSystem.applyModelViewMatrix();
   net.minecraft.client.renderer.FogRenderer.setupNoFog();com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
   try {
    var graphics=new GuiGraphics(mc,mc.renderBuffers().bufferSource());
    for(int i=0;i<stacks.size();i++) {
     try {graphics.renderItem(stacks.get(i),(i%columns)*16,(i/columns)*16);}
     catch(RuntimeException e) {MmlfLog.warn("render.icon","atlas_item_failed",Map.of("item",String.valueOf(stacks.get(i)),"exception",e.toString()));}
    }
    graphics.flush();
   } finally {modelView.popMatrix();RenderSystem.applyModelViewMatrix();}
   GL11.glReadPixels(0,0,width,height,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,pixels);
  } finally {RenderSystem.setProjectionMatrix(previous,sorting);mc.getMainRenderTarget().bindWrite(true);target.destroyBuffers();}
  MmlfLog.info("render.icon","atlas_rendered",Map.of("icons",stacks.size(),"width",width,"height",height));
  return pixels;
 }
 /** Primary: references without an icon yet, asked of their owners at most twice a second. */
 private static final Map<String,Set<String>> missingIcons=new HashMap<>();
 private static long lastIconRequest;
 private static void missingIcon(String owner,String reference) {missingIcons.computeIfAbsent(owner,o->new LinkedHashSet<>()).add(reference);}
 public static void flushIconRequests() {
  long now=System.nanoTime();if(missingIcons.isEmpty() || now-lastIconRequest<500_000_000L) return;
  lastIconRequest=now;
  for(var entry:missingIcons.entrySet()) BridgeCore.send("event",entry.getKey(),new Event("mmlf:icon_request",Map.of("references",String.join(",",entry.getValue().stream().limit(32).toList()))));
  missingIcons.clear();
 }
 public static boolean drawIcon(GuiGraphics graphics,ItemStack stack,int x,int y) {
  if(!BridgeCore.isPrimary()) return false;
  String reference=RemoteItems.held(stack);var remote=RemoteItemRef.parse(reference);if(remote.isEmpty()) return false;
  String owner=remote.get().owner();
  if(RemoteCatalog.drawAtlasIcon(graphics,owner,reference,x,y)) return true;
  try(PixelTransfer transfer=new PixelTransfer()) {
   Source source=iconSources.get(owner);
   if(source==null && (source=open(owner,"-icon",ICON_PIXELS))!=null) iconSources.put(owner,source);
   // The newest icon may belong to another of this owner's items; it is filed under its own reference.
   // A creative page draws dozens of remote icons per frame; the owner's file is read at most once per frame or so.
   long now=System.nanoTime();
   FrameFile.Latest latest=null;
   if(source!=null && now-source.polled>=4_000_000L) {source.polled=now;latest=source.file.readLatest(source.cursor,null);}
   Visual notice=null;
   if(latest!=null) {source.cursor=latest;notice=decode(latest,owner);}
   if(notice!=null) {
    var pixels=latest.pixels();Icon icon=icons.get(notice.reference());
    var image=new NativeImage(32,32,false);var bytes=pixels.color();
    for(int row=0;row<32;row++) for(int col=0;col<32;col++) {
     int i=((31-row)*32+col)*4;image.setPixelRGBA(col,row,(bytes.get(i)&255)|((bytes.get(i+1)&255)<<8)|((bytes.get(i+2)&255)<<16)|((bytes.get(i+3)&255)<<24));
    }
    if(icon!=null) Minecraft.getInstance().getTextureManager().release(icon.id());
    else if(icons.size()>=256) {var oldest=icons.keySet().iterator().next();Minecraft.getInstance().getTextureManager().release(icons.remove(oldest).id());}
    var texture=new DynamicTexture(image);var id=Minecraft.getInstance().getTextureManager().register("mmlf-native-icon",texture);
    icons.put(notice.reference(),new Icon(texture,id,notice.frame().frameId()));
    if(!iconReceivedReady) {iconReceivedReady=true;MmlfLog.info("render.icon","native_icon_ready",Map.of("owner",owner));}
    MmlfLog.debug("render.icon","native_received",Map.of("owner",owner,"frameId",notice.frame().frameId()));
   }
   Icon icon=icons.get(reference);
   // The native icon follows the pick by one round trip; until then draw nothing rather than the proxy paper.
   if(icon==null) {missingIcon(owner,reference);return true;}
   RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();graphics.blit(icon.id(),x,y,16,16,0F,0F,32,32,32,32);
   return true;
  } catch(Exception e) {MmlfLog.error("render.icon","draw_failed",Map.of("exception",e.toString()));return true;}
 }
}
