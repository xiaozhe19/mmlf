package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.collab.TickSync;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** End of each server tick: the boundary is published ({@link TickSync#endOfTick}). Waiting for a permit: {@code lockstep} only. */
@Mixin(MinecraftServer.class)
public abstract class ServerTickMixin {
 @Shadow private long nextTickTimeNanos;
 /** The server thread starts: on the primary its world opens now, before the client ticks again (Worlds). */
 @Inject(method="runServer",at=@At("HEAD"))
 private void mmlf$serverStarting(CallbackInfo ci) { dev.mmlf.bridge.world.Worlds.serverStarting((MinecraftServer)(Object)this); }
 @Inject(method="tickServer",at=@At("HEAD"))
 private void mmlf$startOfTick(java.util.function.BooleanSupplier haveTime,CallbackInfo ci) { dev.mmlf.bridge.collab.SharedPlayer.startOfTick((MinecraftServer)(Object)this); }
 @Inject(method="tickServer",at=@At("TAIL"))
 private void mmlf$endOfTick(java.util.function.BooleanSupplier haveTime,CallbackInfo ci) { TickSync.endOfTick((MinecraftServer)(Object)this); }
 @Inject(method="waitUntilNextTick",at=@At("HEAD"),cancellable=true)
 private void mmlf$awaitPermit(CallbackInfo ci) {
  // While waiting, the next tick is pretended far away so chunk loading and saving keep running between ticks.
  if(TickSync.awaitPermit((MinecraftServer)(Object)this,net.minecraft.Util::getNanos,waiting->nextTickTimeNanos=net.minecraft.Util.getNanos()+(waiting?1_000_000_000L:0L))) ci.cancel();
 }
}
