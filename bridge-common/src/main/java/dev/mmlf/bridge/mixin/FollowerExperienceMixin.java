package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.RemoteEntities;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Experience an owner's copy of the player collects goes to the real player on the primary. */
@Mixin(Player.class)
public abstract class FollowerExperienceMixin {
    @Inject(method="giveExperiencePoints",at=@At("HEAD"),cancellable=true)
    private void mmlf$experience(int amount,CallbackInfo ci) {
        if(RemoteEntities.followerExperience((Player)(Object)this,amount)) ci.cancel();
    }
}
