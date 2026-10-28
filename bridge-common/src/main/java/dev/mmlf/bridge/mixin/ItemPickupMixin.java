package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.InventoryBridge;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** While another runtime holds the inventory, the primary does not add picked-up items behind its back. */
@Mixin(ItemEntity.class)
public abstract class ItemPickupMixin {
 @Inject(method="playerTouch",at=@At("HEAD"),cancellable=true)
 private void mmlf$locked(Player player,CallbackInfo ci) { if(InventoryBridge.locked(player)) ci.cancel(); }
}
