package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.SharedPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A background's copy of the player neither gets hungry nor regenerates: its food and health are the primary's. */
@Mixin(FoodData.class)
public abstract class FollowerFoodMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void mmlf$tick(Player player,CallbackInfo ci) { if(SharedPlayer.shared(player)) ci.cancel(); }
}
