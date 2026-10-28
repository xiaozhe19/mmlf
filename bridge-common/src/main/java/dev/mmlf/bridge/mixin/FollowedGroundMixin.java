package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** A background's own player stands where the primary player stands, on the ground or not. */
@Mixin(Entity.class)
public abstract class FollowedGroundMixin {
 @Inject(method="onGround",at=@At("HEAD"),cancellable=true)
 private void mmlf$followed(CallbackInfoReturnable<Boolean> ci) { Boolean grounded=BridgeCore.followedGround((Entity)(Object)this); if(grounded!=null) ci.setReturnValue(grounded); }
}
