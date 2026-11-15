package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.SharedPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A background's copy of the player, while it reads the primary's state ({@link SharedPlayer}): it never dies (death is
 * the primary's), and the movement it is moved by is the primary player's, whose statistics and exhaustion the primary
 * counts already.
 */
@Mixin(ServerPlayer.class)
public abstract class FollowerStateMixin {
    @Inject(method="die",at=@At("HEAD"),cancellable=true)
    private void mmlf$die(DamageSource source,CallbackInfo ci) { if(SharedPlayer.shared((ServerPlayer)(Object)this)) ci.cancel(); }
    @Inject(method="checkMovementStatistics",at=@At("HEAD"),cancellable=true)
    private void mmlf$movement(double dx,double dy,double dz,CallbackInfo ci) { if(SharedPlayer.shared((ServerPlayer)(Object)this)) ci.cancel(); }
    @Inject(method="checkRidingStatistics",at=@At("HEAD"),cancellable=true)
    private void mmlf$riding(double dx,double dy,double dz,CallbackInfo ci) { if(SharedPlayer.shared((ServerPlayer)(Object)this)) ci.cancel(); }
}
