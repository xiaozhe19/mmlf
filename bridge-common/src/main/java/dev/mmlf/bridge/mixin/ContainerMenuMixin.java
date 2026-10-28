package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.InventoryBridge;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * Placeholders stand for primary items this runtime cannot load; no click of any kind may move them, including a
 * hotbar swap that targets one from another slot. Applies to every menu, vanilla or modded, on both logical sides.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ContainerMenuMixin {
 @Shadow public abstract net.minecraft.world.inventory.Slot getSlot(int index);
 @Shadow public net.minecraft.core.NonNullList<net.minecraft.world.inventory.Slot> slots;
 @Inject(method="doClick",at=@At("HEAD"),cancellable=true)
 private void mmlf$placeholders(int slotId,int button,ClickType type,Player player,CallbackInfo ci) {
  if(slotId>=0 && slotId<slots.size() && InventoryBridge.isPlaceholder(getSlot(slotId).getItem())) { ci.cancel(); return; }
  if(type==ClickType.SWAP && button>=0 && button<player.getInventory().getContainerSize() && InventoryBridge.isPlaceholder(player.getInventory().getItem(button))) { ci.cancel(); return; }
  if(type==ClickType.SWAP && button==40 && InventoryBridge.isPlaceholder(player.getOffhandItem())) ci.cancel();
 }
}
