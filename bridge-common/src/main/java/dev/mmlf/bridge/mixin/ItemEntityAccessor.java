package dev.mmlf.bridge.mixin;

import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The age an item entity's renderer spins and bobs by, copied from the render host's proxy item. */
@Mixin(ItemEntity.class)
public interface ItemEntityAccessor {
    @Accessor("age") void mmlf$setAge(int age);
}
