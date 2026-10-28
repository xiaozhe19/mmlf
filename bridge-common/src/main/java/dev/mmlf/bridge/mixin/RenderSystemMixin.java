package dev.mmlf.bridge.mixin;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.mmlf.bridge.BridgeCore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
 private static long mmlf$last;
 /**
  * Java wait in place of GLFW's (which crashed in the NeoForge dev session). A background renders at its own, higher
  * rate: each primary pose then gets a remote frame sooner, which is what keeps quick turns from showing cut edges.
  */
 @Inject(method="limitDisplayFPS",at=@At("HEAD"),cancellable=true,remap=false)
 private static void mmlf$wait(int fps,CallbackInfo ci) {
  if(System.getProperty("mmlf.session")==null) return;
  int target=BridgeCore.connected() && !BridgeCore.isPrimary() ? BridgeCore.BACKGROUND_FPS : fps;
  long now=System.nanoTime(),wait=1_000_000_000L/Math.max(1,target)-(now-mmlf$last);
  if(wait>0) java.util.concurrent.locks.LockSupport.parkNanos(wait);
  mmlf$last=System.nanoTime(); ci.cancel();
 }
}
