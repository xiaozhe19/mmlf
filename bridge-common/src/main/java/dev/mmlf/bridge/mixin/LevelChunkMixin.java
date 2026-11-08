package dev.mmlf.bridge.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.mmlf.bridge.Boundary;
import dev.mmlf.bridge.NeighborMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every block change of a loaded server chunk, and every chunk that becomes loaded or unloads, reaches the boundary
 * index: objects are known wherever their chunk is loaded, without scanning around the player. Reads of a chunk with
 * mirrored neighbours ({@link NeighborMirror}) answer those positions with the other runtime's block; any other chunk
 * pays one field check.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin implements NeighborMirror.Overlaid {
 @Unique private volatile boolean mmlf$overlaid;
 @Override public boolean mmlf$overlaid() { return mmlf$overlaid; }
 @Override public void mmlf$setOverlaid(boolean value) { mmlf$overlaid=value; }

 @ModifyReturnValue(method="getBlockState",at=@At("RETURN"))
 private BlockState mmlf$mirrored(BlockState own,BlockPos pos) {
  return mmlf$overlaid ? NeighborMirror.read((LevelChunk)(Object)this,pos,own) : own;
 }
 @Inject(method="setBlockState",at=@At("RETURN"))
 private void mmlf$changed(BlockPos pos,BlockState state,boolean moved,CallbackInfoReturnable<BlockState> cir) {
  // Null: nothing changed, or the block was replaced again while being removed.
  if(cir.getReturnValue()!=null && ((LevelChunk)(Object)this).getLevel() instanceof ServerLevel level) Boundary.changed(level,pos,state);
 }
 @Inject(method="setLoaded",at=@At("TAIL"))
 private void mmlf$loaded(boolean loaded,CallbackInfo ci) {
  Boundary.chunk((LevelChunk)(Object)this,loaded);
 }
}
