package dev.mmlf.bridge.mixin;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Render ownership for parts a background may safely skip. */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
 /** Render ownership: a background's sky and weather are drawn by the primary (see {@code RenderOwnership}). */
 @Inject(method="renderSky",at=@At("HEAD"),cancellable=true,require=0)
 private void mmlf$sky(CallbackInfo ci) { if(dev.mmlf.bridge.render.RenderOwnership.skip(dev.mmlf.bridge.render.RenderOwnership.Part.SKY)) ci.cancel(); }
 @Inject(method="renderSnowAndRain",at=@At("HEAD"),cancellable=true,require=0)
 private void mmlf$weather(CallbackInfo ci) { if(dev.mmlf.bridge.render.RenderOwnership.skip(dev.mmlf.bridge.render.RenderOwnership.Part.WEATHER)) ci.cancel(); }
}
