package dev.mmlf.bridge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render host: a proxy of the submitter's item drawn in the world (held in third person, in an item frame, worn) is
 * left to the submitter, which draws its real stack with the same transform ({@code ProxyItems}). Every such draw goes
 * through this method, whichever renderer asked for it.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {
    @Inject(method="render",at=@At("HEAD"),cancellable=true)
    private void mmlf$proxy(ItemStack stack,ItemDisplayContext context,boolean leftHand,PoseStack pose,MultiBufferSource buffers,int light,int overlay,BakedModel model,CallbackInfo ci) {
        if(dev.mmlf.bridge.ProxyItems.drawnByOwner(stack,context,leftHand,pose,buffers,light,overlay)) ci.cancel();
    }
}
