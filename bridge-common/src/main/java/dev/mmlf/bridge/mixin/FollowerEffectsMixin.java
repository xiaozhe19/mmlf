package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.SharedPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;

/**
 * The primary's effects on a background's copy of the player do not tick there (poison would hurt twice, durations are
 * the primary's): they are taken out while the copy's effects tick, so only the background's own effects do.
 */
@Mixin(LivingEntity.class)
public abstract class FollowerEffectsMixin {
    @Unique private Map<Holder<MobEffect>,MobEffectInstance> mmlf$held;
    @Inject(method="tickEffects",at=@At("HEAD"))
    private void mmlf$hold(CallbackInfo ci) { mmlf$held=SharedPlayer.holdShared((LivingEntity)(Object)this); }
    @Inject(method="tickEffects",at=@At("RETURN"))
    private void mmlf$restore(CallbackInfo ci) { SharedPlayer.restoreShared((LivingEntity)(Object)this,mmlf$held); mmlf$held=null; }
}
