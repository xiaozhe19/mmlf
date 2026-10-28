package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
 @Inject(method="renderLevel",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V",shift=At.Shift.BEFORE),require=1)
 private void mmlf$capture(DeltaTracker delta,CallbackInfo ci) { BridgeCore.captureFrame(); dev.mmlf.bridge.VisualLayers.beforeHand(); }
 @Inject(method="getFov",at=@At("RETURN"),cancellable=true)
 private void mmlf$fov(net.minecraft.client.Camera camera,float delta,boolean changing,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Double> ci) {
  var pose=BridgeCore.renderedPose(); if(pose==null) return;
  // A background renders the world (not the hand) wider than the primary, so a quick turn still finds remote pixels
  // at the screen edge; the primary re-projects with the projection the frame was really rendered with.
  double fov=pose.fov();
  if(!BridgeCore.isPrimary() && changing) fov=Math.toDegrees(2*Math.atan(Math.tan(Math.toRadians(fov)/2)*BridgeCore.OVERSCAN));
  ci.setReturnValue(fov);
 }
 @Inject(method="pick(F)V",at=@At("HEAD"))
 private void mmlf$followPrimary(float partial,CallbackInfo ci) { BridgeCore.followPrimaryFrame(); }
 @Inject(method="renderLevel",at=@At("RETURN"))
 private void mmlf$visualAfter(DeltaTracker delta,CallbackInfo ci) {dev.mmlf.bridge.VisualLayers.afterHand();}
 @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
 private void mmlf$remoteHand(net.minecraft.client.Camera camera,float delta,org.joml.Matrix4f view,CallbackInfo ci) {
  if(dev.mmlf.bridge.VisualLayers.replacePrimaryHand()) ci.cancel();
 }
}
