package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.RedstoneBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every signal query of 1.21.1 reaches a block state's own getSignal or getDirectSignal: SignalGetter's
 * hasNeighborSignal, getBestNeighborSignal and strong-power conduction are all built on these two. A position whose
 * signal another runtime publishes gives the stronger of its own and the published one.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateSignalMixin {
 @Inject(method="getSignal",at=@At("RETURN"),cancellable=true)
 private void mmlf$remoteSignal(BlockGetter getter,BlockPos pos,Direction direction,CallbackInfoReturnable<Integer> cir) {
  if(getter instanceof Level level) { int remote=RedstoneBridge.remoteSignal(level,pos,direction,false); if(remote>cir.getReturnValue()) cir.setReturnValue(remote); }
 }
 @Inject(method="getDirectSignal",at=@At("RETURN"),cancellable=true)
 private void mmlf$remoteDirectSignal(BlockGetter getter,BlockPos pos,Direction direction,CallbackInfoReturnable<Integer> cir) {
  if(getter instanceof Level level) { int remote=RedstoneBridge.remoteSignal(level,pos,direction,true); if(remote>cir.getReturnValue()) cir.setReturnValue(remote); }
 }
}
