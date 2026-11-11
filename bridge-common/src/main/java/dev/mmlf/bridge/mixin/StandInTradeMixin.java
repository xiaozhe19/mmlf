package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.InventoryBridge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A stack standing for another runtime's item pays for no trade (a cartographer or librarian buying paper). */
@Mixin(ItemCost.class)
public abstract class StandInTradeMixin {
    @Inject(method="test(Lnet/minecraft/world/item/ItemStack;)Z",at=@At("HEAD"),cancellable=true)
    private void mmlf$notPayment(ItemStack stack,CallbackInfoReturnable<Boolean> cir) {
        if(InventoryBridge.standsIn(stack)) cir.setReturnValue(false);
    }
}
