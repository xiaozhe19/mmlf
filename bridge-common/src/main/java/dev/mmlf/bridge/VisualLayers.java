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

/** Transfers native first-person and item-GUI pixels. No foreign resource or model is registered. */
public final class VisualLayers {
 private record Notice(Frame frame,String reference,int x,int y) {}
 private record Crop(ByteBuffer pixels,int x,int y,int width,int height) {}
 private static FrameFile handWriter,iconWriter,handReader,iconReader;
 private static String handOwner,iconOwner;
 private static Notice incomingHand,completeHand;
 private static FrameFile.Pixels handPixels,handSpare;
 private static final Map<String,Notice> incomingIcons=new HashMap<>();
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
 public static void receive(Event event,String source) {
  var fields=event.values(); Frame frame=Wire.JSON.fromJson(fields.get("frame"),Frame.class);
  var reference=RemoteItemRef.parse(fields.get("reference")).orElseThrow();
  if(!frame.runtime().equals(source) || !reference.owner().equals(frame.runtime())) throw new IllegalArgumentException("visual owner mismatch");
  int x=Integer.parseInt(fields.getOrDefault("x","0")),y=Integer.parseInt(fields.getOrDefault("y","0"));
  Notice notice=new Notice(frame,fields.get("reference"),x,y);
  if(fields.get("kind").equals("hand")) incomingHand=notice;else incomingIcons.put(notice.reference(),notice);
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
  if(!RemoteItems.nativeHandSelected()) return;
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
   if(handWriter==null) handWriter=new FrameFile(BridgeCore.session().framePath(BridgeCore.runtimeId()+"-hand"));
   long id=++sequence;int slot=handWriter.publish(id,crop.width(),crop.height(),crop.pixels(),zeroDepth);
   if(slot>=0 && !handExportReady) {handExportReady=true;MmlfLog.info("render.hand","native_export_ready",Map.of("width",crop.width(),"height",crop.height()));}
   Pose pose=BridgeCore.renderedPose();
   if(slot>=0 && pose!=null && pose.width()==target.width && pose.height()==target.height) RemoteItems.sendVisual("hand",new Frame(BridgeCore.runtimeId(),id,pose,slot,crop.width(),crop.height(),System.nanoTime(),true,"overlay"),RemoteItems.selectedReference(),crop.x(),crop.y());
   MmlfLog.sampled("render.hand","native_export",1000,Map.of("frameId",id,"slot",slot,"x",crop.x(),"y",crop.y(),"width",crop.width(),"height",crop.height()));
   renderIconNative();
  } catch(Exception e) {MmlfLog.error("render.hand","export_failed",Map.of("exception",e.toString()));}
 }
 private static void compositeHand() {
  if(!replacePrimaryHand() || incomingHand==null) return;
  String reference=held();Notice notice=incomingHand;
  if(!reference.equals(notice.reference())) return;
  try(PixelTransfer transfer=new PixelTransfer()) {
   if(handReader==null || !notice.frame().runtime().equals(handOwner)) {
    if(handReader!=null) handReader.close();handOwner=notice.frame().runtime();
    handReader=new FrameFile(BridgeCore.session().framePath(handOwner+"-hand"));completeHand=null;
   }
   if(completeHand==null || completeHand.frame().frameId()!=notice.frame().frameId()) {
    var pixels=handReader.read(notice.frame(),handSpare);
    if(pixels!=null) {handSpare=handPixels;handPixels=pixels;completeHand=notice;}
   }
   if(completeHand==null || !reference.equals(completeHand.reference())) return;
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
 private static void renderIconNative() throws Exception {
  String reference=RemoteItems.selectedReference();long now=System.nanoTime();
  if(reference.equals(lastIconReference) && now-lastIcon<250_000_000L) return;
  lastIconReference=reference;lastIcon=now;
  var mc=Minecraft.getInstance();var stack=mc.player.getMainHandItem();
  if(stack.isEmpty()) return;
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
  if(iconWriter==null) iconWriter=new FrameFile(BridgeCore.session().framePath(BridgeCore.runtimeId()+"-icon"),ICON_PIXELS);
  ByteBuffer empty=BufferUtils.createByteBuffer(32*32*4);
  long id=++sequence;int slot=iconWriter.publish(id,32,32,iconColor,empty);
  if(slot>=0 && !iconExportReady) {iconExportReady=true;MmlfLog.info("render.icon","native_export_ready",Map.of("width",32,"height",32));}
  if(slot>=0) RemoteItems.sendVisual("icon",new Frame(BridgeCore.runtimeId(),id,BridgeCore.renderedPose(),slot,32,32,System.nanoTime(),true,"overlay"),reference);
  MmlfLog.sampled("render.icon","native_export",1000,Map.of("frameId",id,"slot",slot));
 }
 public static boolean drawIcon(GuiGraphics graphics,ItemStack stack,int x,int y) {
  if(!BridgeCore.isPrimary()) return false;
  String reference=RemoteItems.held(stack);var remote=RemoteItemRef.parse(reference);if(remote.isEmpty()) return false;
  var notice=incomingIcons.get(reference);Icon icon=icons.get(reference);
  try(PixelTransfer transfer=new PixelTransfer()) {
   if(notice!=null && (icon==null || icon.frame()!=notice.frame().frameId())) {
    if(iconReader==null || !remote.get().owner().equals(iconOwner)) {
     if(iconReader!=null) iconReader.close();iconOwner=remote.get().owner();iconReader=new FrameFile(BridgeCore.session().framePath(iconOwner+"-icon"),ICON_PIXELS);
    }
    var pixels=iconReader.read(notice.frame());
    if(pixels!=null) {
     var image=new NativeImage(32,32,false);var bytes=pixels.color();
     for(int row=0;row<32;row++) for(int col=0;col<32;col++) {
      int i=((31-row)*32+col)*4;image.setPixelRGBA(col,row,(bytes.get(i)&255)|((bytes.get(i+1)&255)<<8)|((bytes.get(i+2)&255)<<16)|((bytes.get(i+3)&255)<<24));
     }
     if(icon!=null) Minecraft.getInstance().getTextureManager().release(icon.id());
     var texture=new DynamicTexture(image);var id=Minecraft.getInstance().getTextureManager().register("mmlf-native-icon",texture);
     icon=new Icon(texture,id,notice.frame().frameId());icons.put(reference,icon);
     if(!iconReceivedReady) {iconReceivedReady=true;MmlfLog.info("render.icon","native_icon_ready",Map.of("owner",remote.get().owner()));}
     MmlfLog.debug("render.icon","native_received",Map.of("owner",remote.get().owner(),"frameId",notice.frame().frameId()));
    }
   }
   if(icon==null) return false;
   RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();graphics.blit(icon.id(),x,y,16,16,0F,0F,32,32,32,32);
   return true;
  } catch(Exception e) {MmlfLog.error("render.icon","draw_failed",Map.of("exception",e.toString()));return true;}
 }
}
