package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.StandInBlock;
import dev.mmlf.bridge.render.TerrainModels;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockModelShaper.class)
public abstract class BlockModelShaperMixin {
    @Inject(method="getBlockModel",at=@At("HEAD"),cancellable=true)
    private void mmlf$terrainModel(BlockState state,CallbackInfoReturnable<BakedModel> result) {
        if(StandInBlock.is(state) && TerrainModels.enabled() && TerrainModels.get()!=null) result.setReturnValue(TerrainModels.get());
    }
}
