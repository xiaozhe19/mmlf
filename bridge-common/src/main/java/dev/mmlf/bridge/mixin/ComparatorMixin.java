package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.RedstoneBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A comparator reads the block behind it: its analog output (a container's fill, a mod's level) replaces the redstone
 * input. When that block is another runtime's object, the published analog output takes its place the same way.
 */
@Mixin(ComparatorBlock.class)
public abstract class ComparatorMixin {
 @Inject(method="getInputSignal",at=@At("RETURN"),cancellable=true)
 private void mmlf$remoteAnalog(Level level,BlockPos pos,BlockState state,CallbackInfoReturnable<Integer> cir) {
  BlockPos behind=pos.relative(state.getValue(ComparatorBlock.FACING));
  if(level.getBlockState(behind).hasAnalogOutputSignal()) return;
  int analog=RedstoneBridge.remoteAnalog(level,behind);
  if(analog>=0) cir.setReturnValue(analog);
 }
}
