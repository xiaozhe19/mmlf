package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.OwnEffects;
import dev.mmlf.common.PlayerShare;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The primary's player losing all its effects (milk and the totem in vanilla, /effect clear): the backgrounds' own effects go too. */
@Mixin(LivingEntity.class)
public abstract class PrimaryCureMixin {
    @Inject(method="removeAllEffects",at=@At("HEAD"))
    private void mmlf$cured(CallbackInfoReturnable<Boolean> cir) { OwnEffects.primaryCured((LivingEntity)(Object)this,PlayerShare.CURE_ALL); }
}
