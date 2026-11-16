package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.StandInFeel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Speed and jump factors and footsteps on a stand-in are the owner's block's ({@link StandInFeel}). */
@Mixin(Entity.class)
public abstract class StandInMovementMixin {
    @Inject(method="getBlockSpeedFactor",at=@At("HEAD"),cancellable=true)
    private void mmlf$speed(CallbackInfoReturnable<Float> cir) { Float f=StandInFeel.speedFactor((Entity)(Object)this); if(f!=null) cir.setReturnValue(f); }
    @Inject(method="getBlockJumpFactor",at=@At("HEAD"),cancellable=true)
    private void mmlf$jump(CallbackInfoReturnable<Float> cir) { Float f=StandInFeel.jumpFactor((Entity)(Object)this); if(f!=null) cir.setReturnValue(f); }
    @Inject(method="playStepSound",at=@At("HEAD"),cancellable=true)
    private void mmlf$step(BlockPos pos,BlockState state,CallbackInfo ci) { if(StandInFeel.stepSound((Entity)(Object)this,pos,state)) ci.cancel(); }
}
