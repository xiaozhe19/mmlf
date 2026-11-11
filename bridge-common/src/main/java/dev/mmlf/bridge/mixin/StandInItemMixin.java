package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.InventoryBridge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A stack standing for another runtime's item is paper only by its base item: no recipe ingredient matches it (crafting,
 * smelting, smithing, stonecutting and every mod recipe built on {@link Ingredient}), so it is never consumed as paper.
 */
@Mixin(Ingredient.class)
public abstract class StandInItemMixin {
    @Inject(method="test(Lnet/minecraft/world/item/ItemStack;)Z",at=@At("HEAD"),cancellable=true)
    private void mmlf$notAnIngredient(ItemStack stack,CallbackInfoReturnable<Boolean> cir) {
        if(InventoryBridge.standsIn(stack)) cir.setReturnValue(false);
    }
}
