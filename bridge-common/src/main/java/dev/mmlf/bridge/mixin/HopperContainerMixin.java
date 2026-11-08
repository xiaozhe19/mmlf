package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.ContainerBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Where a hopper finds no block container of its own world, another runtime's container at that position answers
 * through its stand-in. Both loaders' hoppers fall back to this lookup when their own item APIs find nothing there.
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperContainerMixin {
 @Inject(method="getBlockContainer",at=@At("RETURN"),cancellable=true)
 private static void mmlf$remoteContainer(Level level,BlockPos pos,BlockState state,CallbackInfoReturnable<Container> cir) {
  if(cir.getReturnValue()==null) { Container remote=ContainerBridge.proxy(level,pos); if(remote!=null) cir.setReturnValue(remote); }
 }
}
