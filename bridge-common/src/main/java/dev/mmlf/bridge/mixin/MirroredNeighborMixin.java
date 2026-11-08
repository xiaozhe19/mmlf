package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.NeighborMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A mirrored neighbour is another runtime's block, seen, never run: a neighbour update or shape update reaching it
 * does nothing here. Otherwise a mirrored redstone wire would recompute its power in this world, try to write it, still
 * read the mirrored state back and update its neighbours again without end.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class MirroredNeighborMixin {
 @Inject(method="handleNeighborChanged",at=@At("HEAD"),cancellable=true)
 private void mmlf$inertNeighbor(Level level,BlockPos pos,Block block,BlockPos from,boolean moved,CallbackInfo ci) {
  if(NeighborMirror.mirrored(level,pos)) ci.cancel();
 }
 @Inject(method="updateShape",at=@At("HEAD"),cancellable=true)
 private void mmlf$inertShape(Direction direction,BlockState neighbor,LevelAccessor level,BlockPos pos,BlockPos from,CallbackInfoReturnable<BlockState> cir) {
  if(NeighborMirror.mirrored(level,pos)) cir.setReturnValue((BlockState)(Object)this);
 }
}
