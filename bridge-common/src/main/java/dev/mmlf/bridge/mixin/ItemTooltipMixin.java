package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.RemoteItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;
/** A proxy shows the tooltip its owner generated for the real item. */
@Mixin(ItemStack.class)
public abstract class ItemTooltipMixin {
 @Inject(method="getTooltipLines",at=@At("RETURN"),cancellable=true)
 private void mmlf$remote(Item.TooltipContext context,Player player,TooltipFlag flag,CallbackInfoReturnable<List<Component>> ci) {
  List<Component> lines=RemoteItems.tooltip((ItemStack)(Object)this);
  if(lines!=null) ci.setReturnValue(lines);
 }
}
