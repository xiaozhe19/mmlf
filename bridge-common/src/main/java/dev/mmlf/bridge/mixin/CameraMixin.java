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
 @Inject(method="setup",at=@At("RETURN"))
 private void mmlf$pose(BlockGetter level,Entity entity,boolean detached,boolean reverse,float partial,CallbackInfo ci) {
  if(BridgeCore.isPrimary()) {BridgeCore.publishCamera((Camera)(Object)this);return;}
  Pose p=BridgeCore.lastRemotePose();
  if(p!=null) { setPosition(p.x(),p.y(),p.z()); setRotation(p.yaw(),p.pitch()); }
 }
}
