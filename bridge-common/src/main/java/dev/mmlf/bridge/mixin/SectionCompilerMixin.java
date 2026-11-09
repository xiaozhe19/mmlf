package dev.mmlf.bridge.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mmlf.bridge.BridgeCore;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
/**
 * Leaves blocks another runtime draws out of this runtime's chunk meshes. The check sits on the section loop's render
 * shape test rather than on BlockRenderDispatcher.renderBatched, because Fabric's Indigo renderer redirects that call.
 * NeoForge has a delegating compile overload; the wildcard reaches the one that holds the loop on both loaders.
 * The unified render layer's submission compiler ({@code OwnedSectionCompiler}) runs through the same loop.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
 @WrapOperation(method="compile*",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockState;getRenderShape()Lnet/minecraft/world/level/block/RenderShape;"))
 private RenderShape mmlf$owned(BlockState state,Operation<RenderShape> original,@Local(ordinal=2) BlockPos pos) {
  RenderShape shape=original.call(state);
  if(shape!=RenderShape.MODEL) return shape;
  boolean accepted=BridgeCore.renderOwnedInSection(state,pos);
  dev.mmlf.bridge.render.RenderPathEvidence.observed(false,accepted);
  return accepted ? shape : RenderShape.INVISIBLE;
 }
 /** Experiment {@code geometry}: fluids follow the owner rule too, so submitted sections do not repeat the primary's water. */
 @WrapOperation(method="compile*",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderLiquid(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"),require=0)
 private void mmlf$ownedFluid(BlockRenderDispatcher dispatcher,BlockPos pos,BlockAndTintGetter level,VertexConsumer consumer,BlockState state,FluidState fluid,Operation<Void> original) {
  if(!dev.mmlf.bridge.Experiments.GEOMETRY || BridgeCore.renderOwnedFluidInSection(fluid,pos)) original.call(dispatcher,pos,level,consumer,state,fluid);
 }
}
