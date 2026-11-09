package dev.mmlf.bridge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.mmlf.bridge.render.NativeWorld;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A submitting runtime whose content the render host draws leaves block entities out of its own world pass. Every
 * renderer (vanilla, or replacements such as Sodium that schedule block entities themselves) draws them through this
 * method, so this is the one generic place to do it.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private <E extends BlockEntity> void mmlf$hidden(E blockEntity,float partial,PoseStack poseStack,MultiBufferSource buffers,CallbackInfo ci) {
        if(NativeWorld.hidden()) ci.cancel();
    }
}
