package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.RemoteCatalog;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Collection;
import java.util.Set;
/** Remote items an owner shows in a vanilla tab appear in the same tab here, and in the creative search. */
@Mixin(CreativeModeTab.class)
public abstract class CreativeTabMixin {
 @Shadow private Collection<ItemStack> displayItems;
 @Shadow private Set<ItemStack> displayItemsSearchTab;
 @Inject(method="buildContents",at=@At("RETURN"))
 private void mmlf$remote(CreativeModeTab.ItemDisplayParameters parameters,CallbackInfo ci) {
  var extra=RemoteCatalog.additions((CreativeModeTab)(Object)this);
  if(extra.isEmpty()) return;
  Set<ItemStack> items=ItemStackLinkedSet.createTypeAndComponentsSet(); items.addAll(displayItems);
  Set<ItemStack> search=ItemStackLinkedSet.createTypeAndComponentsSet(); search.addAll(displayItemsSearchTab);
  for(ItemStack stack:extra) { items.add(stack.copy()); search.add(stack.copy()); }
  displayItems=items; displayItemsSearchTab=search;
 }
}
