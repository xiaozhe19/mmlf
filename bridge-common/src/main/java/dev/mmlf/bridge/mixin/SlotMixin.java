package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.InventoryBridge;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Menus that move items through their own code still ask the slot; a placeholder never leaves it. */
@Mixin(Slot.class)
public abstract class SlotMixin {
 @Shadow public abstract net.minecraft.world.item.ItemStack getItem();
 @Inject(method="mayPickup",at=@At("HEAD"),cancellable=true)
 private void mmlf$pickup(Player player,CallbackInfoReturnable<Boolean> ci) { if(InventoryBridge.isPlaceholder(getItem())) ci.setReturnValue(false); }
}
