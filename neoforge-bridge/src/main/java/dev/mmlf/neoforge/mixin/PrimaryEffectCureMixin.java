package dev.mmlf.neoforge.mixin;

import dev.mmlf.bridge.collab.OwnEffects;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.EffectCure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** NeoForge cures by kind (milk, honey, totem, mods' own) instead of removing all: the backgrounds hear the cure's name. */
@Mixin(LivingEntity.class)
public abstract class PrimaryEffectCureMixin {
    @Inject(method="removeEffectsCuredBy",at=@At("HEAD"))
    private void mmlf$cured(EffectCure cure,CallbackInfoReturnable<Boolean> cir) { OwnEffects.primaryCured((LivingEntity)(Object)this,cure.name()); }
}
