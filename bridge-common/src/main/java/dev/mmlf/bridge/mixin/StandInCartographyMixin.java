package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.InventoryBridge;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The cartography table checks for paper by item type, not by ingredient: a stand-in stack extends no map. */
@Mixin(CartographyTableMenu.class)
public abstract class StandInCartographyMixin {
    @Shadow @Final private ResultContainer resultContainer;
    @Inject(method="setupResultSlot",at=@At("HEAD"),cancellable=true)
    private void mmlf$noStandIn(ItemStack map,ItemStack additional,ItemStack result,CallbackInfo ci) {
        if(!InventoryBridge.standsIn(map) && !InventoryBridge.standsIn(additional)) return;
        resultContainer.setItem(2,ItemStack.EMPTY);
        ((CartographyTableMenu)(Object)this).broadcastChanges();
        ci.cancel();
    }
}
