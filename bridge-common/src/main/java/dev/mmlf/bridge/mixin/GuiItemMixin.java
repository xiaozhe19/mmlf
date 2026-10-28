package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.VisualLayers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GuiGraphics.class)
public abstract class GuiItemMixin {
 @Inject(method="renderItem(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;IIII)V",at=@At("HEAD"),cancellable=true)
 private void mmlf$icon(LivingEntity entity,Level level,ItemStack stack,int x,int y,int seed,int offset,CallbackInfo ci) {
  if(VisualLayers.drawIcon((GuiGraphics)(Object)this,stack,x,y)) ci.cancel();
 }
}
