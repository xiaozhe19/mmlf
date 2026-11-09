package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.RemoteEntities;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Everything an owned entity's death adds to the world (loot, experience orbs) belongs to the same runtime: entities
 * added while it dies are claimed ({@code FreshEntityMixin}). The whole of {@code die} is covered, because NeoForge
 * collects the drops there and adds them to the world only after its drops event, outside {@code dropAllDeathLoot}.
 */
@Mixin(LivingEntity.class)
public abstract class DeathLootMixin {
    @Inject(method="die",at=@At("HEAD"))
    private void mmlf$begin(DamageSource source,CallbackInfo ci) { RemoteEntities.beginDrops((LivingEntity)(Object)this); }
    @Inject(method="die",at=@At("RETURN"))
    private void mmlf$end(DamageSource source,CallbackInfo ci) { RemoteEntities.endDrops(); }
}
