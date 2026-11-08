package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * The same owner rule as {@code SectionCompilerMixin}, on the chunk mesher that replaces the vanilla one when Sodium (or
 * a mod built on it) is installed: Sodium meshes each block through {@code BlockRenderer.renderModel(model, state, pos,
 * origin)} instead of the vanilla section loop, so without this a background would mesh blocks the primary draws.
 * Absent Sodium, the target does not exist and this does nothing.
 */
@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer",remap=false)
public abstract class SodiumBlockRendererMixin {
 @Inject(method="renderModel",at=@At("HEAD"),cancellable=true,require=0,remap=false)
 private void mmlf$owned(BakedModel model,BlockState state,BlockPos pos,BlockPos origin,CallbackInfo ci) {
  if(!BridgeCore.renderOwned(state,pos)) ci.cancel();
 }
}
