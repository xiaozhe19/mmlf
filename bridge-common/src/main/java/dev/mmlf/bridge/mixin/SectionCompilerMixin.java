package dev.mmlf.bridge.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/**
 * Leaves blocks another runtime draws out of this runtime's chunk meshes. The check sits on the section loop's render
 * shape test rather than on BlockRenderDispatcher.renderBatched, because Fabric's Indigo renderer redirects that call.
 * NeoForge has a delegating compile overload; the wildcard reaches the one that holds the loop on both loaders.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
 @WrapOperation(method="compile*",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
 private RenderShape mmlf$owned(BlockState state,Operation<RenderShape> original,@Local(ordinal=2) BlockPos pos) {
  RenderShape shape=original.call(state);
  return shape==RenderShape.MODEL && !BridgeCore.renderOwned(state,pos) ? RenderShape.INVISIBLE : shape;
 }
 /** Experiment {@code geometry}: a background keeps a copy of each compiled section for the primary to draw. */
 @Inject(method="compile*",at=@At("RETURN"),require=0)
 private void mmlf$captureGeometry(CallbackInfoReturnable<SectionCompiler.Results> ci,@Local(argsOnly=true) net.minecraft.core.SectionPos section) {
  if(dev.mmlf.bridge.Experiments.GEOMETRY) dev.mmlf.bridge.render.GeometryLayer.captured(section,ci.getReturnValue().renderedLayers);
 }
}
