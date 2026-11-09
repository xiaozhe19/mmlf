package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.VisualLayers;
import dev.mmlf.bridge.BridgeCore;
import dev.mmlf.bridge.Experiments;
import dev.mmlf.bridge.RemoteItems;
import dev.mmlf.bridge.render.GeometryLayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Covers render pipelines that call the common first-person renderer without GameRenderer.renderItemInHand. */
@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin {
    @org.spongepowered.asm.mixin.Unique private boolean mmlf$handSuppressionReported;
    @Inject(method="renderHandsWithItems",at=@At("HEAD"),cancellable=true)
    private void mmlf$replacePrimaryHands(CallbackInfo ci) {
        if(VisualLayers.replacePrimaryHand()) {
            if(!mmlf$handSuppressionReported) {
                mmlf$handSuppressionReported=true;
                dev.mmlf.common.MmlfLog.info("render.hand","primary_suppressed",java.util.Map.of("entry","renderHandsWithItems"));
            }
            ci.cancel();
        } else if(Experiments.GEOMETRY_REPLAY && !BridgeCore.isPrimary() && GeometryLayer.isRenderHost() && !RemoteItems.nativeHandSelected()) {
            // A remote scene renderer must not bake its own copy of the player's local hand into the world image.
            ci.cancel();
        }
    }
}
