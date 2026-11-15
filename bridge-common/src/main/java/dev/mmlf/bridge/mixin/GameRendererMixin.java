package dev.mmlf.bridge.mixin;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
 @Inject(method="renderLevel",at=@At("HEAD"))
 private void mmlf$beginFrame(DeltaTracker delta,CallbackInfo ci) { dev.mmlf.bridge.render.GeometryLayer.beginFrame(delta.getGameTimeDeltaPartialTick(false)); }
 @Inject(method="renderLevel",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",shift=At.Shift.BEFORE),require=1)
 private void mmlf$capture(DeltaTracker delta,CallbackInfo ci) { dev.mmlf.bridge.render.FrameCompositor.captureFrame(); dev.mmlf.bridge.render.VisualLayers.beforeHand(); }
 @Inject(method="getFov",at=@At("RETURN"),cancellable=true)
 private void mmlf$fov(net.minecraft.client.Camera camera,float delta,boolean changing,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Double> ci) {
  if(!changing) return;
  if(dev.mmlf.bridge.transport.SessionLink.isPrimary()) {
   // Preserve Minecraft and loader FOV effects and publish the value used for this world projection.
   dev.mmlf.bridge.render.CameraPublisher.publishCamera(camera,ci.getReturnValue().floatValue());
   return;
  }
  var pose=dev.mmlf.bridge.render.CameraPublisher.renderedPose(); if(pose==null) return;
  double fov=dev.mmlf.bridge.render.CameraPublisher.backgroundFov(pose);
  ci.setReturnValue(fov);
 }
 @Inject(method="pick(F)V",at=@At("HEAD"))
 private void mmlf$followPrimary(float partial,CallbackInfo ci) { dev.mmlf.bridge.collab.PlayerFollower.followPrimaryFrame(); }
 @Inject(method="renderLevel",at=@At("RETURN"))
 private void mmlf$visualAfter(DeltaTracker delta,CallbackInfo ci) {dev.mmlf.bridge.render.VisualLayers.afterHand();}
 @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
 private void mmlf$remoteHand(net.minecraft.client.Camera camera,float delta,org.joml.Matrix4f view,CallbackInfo ci) {
  if(dev.mmlf.bridge.render.VisualLayers.replacePrimaryHand()) ci.cancel();
 }
}
