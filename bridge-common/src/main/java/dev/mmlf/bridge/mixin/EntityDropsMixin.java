package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.RemoteEntities;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** What an owned entity drops (loot, shearing, eggs) belongs to the same runtime, although the item may be vanilla. */
@Mixin(Entity.class)
public abstract class EntityDropsMixin {
    @Inject(method="spawnAtLocation(Lnet/minecraft/world/item/ItemStack;F)Lnet/minecraft/world/entity/item/ItemEntity;",at=@At("RETURN"))
    private void mmlf$dropped(ItemStack stack,float offset,CallbackInfoReturnable<ItemEntity> cir) {
        RemoteEntities.dropped((Entity)(Object)this,cir.getReturnValue());
    }
}
