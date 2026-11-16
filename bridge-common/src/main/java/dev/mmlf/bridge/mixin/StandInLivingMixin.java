package dev.mmlf.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mmlf.bridge.collab.StandInBlock;
import dev.mmlf.bridge.collab.StandInFeel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The landing sound on a stand-in is the owner's block's; so is its slipperiness where vanilla asks the block without a
 * position (Fabric; NeoForge asks {@code StandInBlock.getFriction} with one, and this target is absent there).
 */
@Mixin(LivingEntity.class)
public abstract class StandInLivingMixin {
    @Inject(method="playBlockFallSound",at=@At("HEAD"),cancellable=true)
    private void mmlf$fallSound(CallbackInfo ci) { if(StandInFeel.fallSound((LivingEntity)(Object)this)) ci.cancel(); }
    @WrapOperation(method="travel",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/Block;getFriction()F"),require=0)
    private float mmlf$friction(Block block,Operation<Float> original) {
        if(!(block instanceof StandInBlock)) return original.call(block);
        LivingEntity self=(LivingEntity)(Object)this;
        return StandInFeel.friction(self.level(),self.getBlockPosBelowThatAffectsMyMovement());
    }
}
