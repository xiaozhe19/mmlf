package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** A background runtime's block outline would be composited over the primary's own one. */
@Mixin(LevelRenderer.class)
public abstract class HitOutlineMixin {
 @Inject(method="renderHitOutline",at=@At("HEAD"),cancellable=true)
 private void mmlf$noBackgroundOutline(CallbackInfo ci) { if(BridgeCore.connected() && !BridgeCore.isPrimary()) ci.cancel(); }
}
