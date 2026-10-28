package dev.mmlf.bridge.mixin;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BlockRenderDispatcher.class)
public abstract class BlockRenderDispatcherMixin {
 @Inject(method="renderBatched",at=@At("HEAD"),cancellable=true)
 private void mmlf$filter(BlockState state,BlockPos pos,BlockAndTintGetter level,PoseStack pose,VertexConsumer vertices,boolean cull,RandomSource random,CallbackInfo ci) {
  if(!BridgeCore.renderOwned(state,pos)) ci.cancel();
 }
}
