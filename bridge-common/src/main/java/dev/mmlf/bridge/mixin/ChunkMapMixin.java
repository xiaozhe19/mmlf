package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.ChunkFollow;
import dev.mmlf.bridge.CoGeneration;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/**
 * Experiment {@code follow}: a background's players hold no chunk tickets, as spectators do when they may not generate
 * chunks; loaded chunks are still sent to them. Its chunks are loaded by {@link ChunkFollow} instead.
 * <p>
 * Co-generation: a background chunk that never got the primary's terrain is not saved ({@link CoGeneration#unsaved}).
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
 @Shadow @Final ServerLevel level;
 @Inject(method="skipPlayer",at=@At("HEAD"),cancellable=true)
 private void mmlf$followPrimary(ServerPlayer player,CallbackInfoReturnable<Boolean> cir) {
  if(!ChunkFollow.playersLoadChunks()) cir.setReturnValue(true);
 }
 @Inject(method="save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",at=@At("HEAD"),cancellable=true)
 private void mmlf$unfilled(ChunkAccess chunk,CallbackInfoReturnable<Boolean> cir) {
  if(CoGeneration.unsaved(level,chunk)) cir.setReturnValue(false);
 }
}
