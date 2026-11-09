package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.RemoteEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Entities added while an owned entity drops its death loot are that runtime's too ({@link RemoteEntities#beginDrops}). */
@Mixin(ServerLevel.class)
public abstract class FreshEntityMixin {
    @Inject(method="addFreshEntity",at=@At("HEAD"))
    private void mmlf$added(Entity entity,CallbackInfoReturnable<Boolean> cir) { RemoteEntities.added(entity); }
}
