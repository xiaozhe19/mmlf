package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Entity.class)
public abstract class EntityCollisionMixin {
 @Inject(method="collide",at=@At("RETURN"),cancellable=true)
 private void mmlf$collision(Vec3 requested,CallbackInfoReturnable<Vec3> ci) {
  Entity entity=(Entity)(Object)this;
  Vec3 movement=ci.getReturnValue();
  var shapes=BridgeCore.remoteCollisions(entity,entity.getBoundingBox().expandTowards(movement).inflate(1.0E-7));
  if(!shapes.isEmpty()) ci.setReturnValue(Entity.collideBoundingBox(entity,movement,entity.getBoundingBox(),entity.level(),shapes));
 }
}
