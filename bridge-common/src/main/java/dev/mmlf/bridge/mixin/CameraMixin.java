package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.common.Messages.Pose;
import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Camera.class)
public abstract class CameraMixin {
 @Shadow protected abstract void setPosition(double x,double y,double z);
 @Shadow protected abstract void setRotation(float yaw,float pitch);
 @Inject(method="setup",at=@At("HEAD"))
 private void mmlf$pollPose(BlockGetter level,Entity entity,boolean detached,boolean reverse,float partial,CallbackInfo ci) { BridgeCore.beginCameraRender(); }
 @Inject(method="setup",at=@At("RETURN"))
 private void mmlf$pose(BlockGetter level,Entity entity,boolean detached,boolean reverse,float partial,CallbackInfo ci) {
  if(BridgeCore.isPrimary()) return;
  Pose p=BridgeCore.lastRemotePose();
  // Only the camera is predicted; the player (crosshair ray, interactions) keeps the primary's real rotation.
  if(p!=null && net.minecraft.client.Minecraft.getInstance().level!=null && net.minecraft.client.Minecraft.getInstance().level.dimension().location().toString().equals(p.dimension())) { float[] r=BridgeCore.predictedRotation(p); setPosition(p.x(),p.y(),p.z()); setRotation(r[0],r[1]); }
 }
}
