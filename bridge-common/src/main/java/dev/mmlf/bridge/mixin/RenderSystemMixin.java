package dev.mmlf.bridge.mixin;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
 private static long mmlf$last;
 @Inject(method="limitDisplayFPS",at=@At("HEAD"),cancellable=true,remap=false)
 private static void mmlf$wait(int fps,CallbackInfo ci) {
  if(System.getProperty("mmlf.session")==null) return;
  long now=System.nanoTime(),wait=16_666_667L-(now-mmlf$last);
  if(wait>0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
  mmlf$last=System.nanoTime(); ci.cancel();
 }
}
