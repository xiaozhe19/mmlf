package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.SharedPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A background's copy of the player does not jump: the primary's player jumped (and was tired by it) already. */
@Mixin(Player.class)
public abstract class FollowerJumpMixin {
    @Inject(method="jumpFromGround",at=@At("HEAD"),cancellable=true)
    private void mmlf$jump(CallbackInfo ci) { if(SharedPlayer.shared((Player)(Object)this)) ci.cancel(); }
}
