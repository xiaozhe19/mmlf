package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.RemoteEntities;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * An owner runtime's copy of the one player never takes damage itself: the primary owns the player's health. Damage
 * this runtime's entities do to it is forwarded to the primary instead ({@link RemoteEntities#followerHurt}).
 */
@Mixin(ServerPlayer.class)
public abstract class FollowerDamageMixin {
    @Inject(method="hurt",at=@At("HEAD"),cancellable=true)
    private void mmlf$followerHurt(DamageSource source,float amount,CallbackInfoReturnable<Boolean> cir) {
        if(RemoteEntities.followerHurt((ServerPlayer)(Object)this,source,amount)) cir.setReturnValue(false);
    }
}
