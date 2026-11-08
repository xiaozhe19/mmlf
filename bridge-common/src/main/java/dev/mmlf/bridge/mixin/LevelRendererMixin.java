package dev.mmlf.bridge.mixin;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Experiment {@code geometry} (section meshes drawn by the primary) and render ownership (parts a background skips). */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
 /** Render ownership: a background's sky and weather are drawn by the primary (see {@code RenderOwnership}). */
 @Inject(method="renderSky",at=@At("HEAD"),cancellable=true,require=0)
 private void mmlf$sky(CallbackInfo ci) { if(dev.mmlf.bridge.render.RenderOwnership.skip(dev.mmlf.bridge.render.RenderOwnership.Part.SKY)) ci.cancel(); }
 @Inject(method="renderSnowAndRain",at=@At("HEAD"),cancellable=true,require=0)
 private void mmlf$weather(CallbackInfo ci) { if(dev.mmlf.bridge.render.RenderOwnership.skip(dev.mmlf.bridge.render.RenderOwnership.Part.WEATHER)) ci.cancel(); }
 @Inject(method="renderSectionLayer",at=@At("TAIL"))
 private void mmlf$remoteGeometry(RenderType type,double x,double y,double z,Matrix4f modelView,Matrix4f projection,CallbackInfo ci) {
  if(dev.mmlf.bridge.Experiments.GEOMETRY) dev.mmlf.bridge.render.GeometryLayer.draw(type,x,y,z,modelView,projection);
 }
}
