package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.ChunkFollow;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/**
 * Experiment {@code follow}: a background's players hold no chunk tickets, as spectators do when they may not generate
 * chunks; loaded chunks are still sent to them. Its chunks are loaded by {@link ChunkFollow} instead.
 */
@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
 @Inject(method="skipPlayer",at=@At("HEAD"),cancellable=true)
 private void mmlf$followPrimary(ServerPlayer player,CallbackInfoReturnable<Boolean> cir) {
  if(!ChunkFollow.playersLoadChunks()) cir.setReturnValue(true);
 }
}
