package dev.mmlf.neoforge.mixin;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BlockRenderDispatcher.class)
public abstract class NativeBlockFilterMixin {
 @Inject(method="renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",at=@At("HEAD"),cancellable=true)
 private void filter(BlockState state,BlockPos pos,BlockAndTintGetter level,PoseStack pose,VertexConsumer vertices,boolean cull,RandomSource random,ModelData data,RenderType type,CallbackInfo ci) {
  if(!BridgeCore.renderOwned(state,pos)) ci.cancel();
 }
}
