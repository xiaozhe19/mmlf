package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.render.NativeWorld;
import dev.mmlf.bridge.render.RemoteProxy;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Unified render layer: the render host's stand-in entity gets its renderer here, and a submitting runtime whose
 * content the render host draws leaves every entity out of its own world pass (it records its own entities separately).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
    @Inject(method="getRenderer",at=@At("HEAD"),cancellable=true)
    private <T extends Entity> void mmlf$proxyRenderer(T entity,CallbackInfoReturnable<EntityRenderer<? super T>> cir) {
        if(entity instanceof RemoteProxy) {
            @SuppressWarnings("unchecked") var renderer=(EntityRenderer<? super T>)RemoteProxy.renderer(entity);
            cir.setReturnValue(renderer);
        }
    }
    /**
     * Reached only when this runtime really drew an entity's blob shadow: options and shader mods that suppress
     * vanilla shadows return earlier. The render host draws the submitter's blob shadows exactly when it draws its own.
     */
    @Inject(method="renderShadow",at=@At("TAIL"),require=0)
    private static void mmlf$blobShadowDrawn(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        dev.mmlf.bridge.render.GeometryLayer.blobShadowDrawn();
    }
    @Inject(method="shouldRender",at=@At("HEAD"),cancellable=true)
    private <E extends Entity> void mmlf$hidden(E entity,Frustum frustum,double x,double y,double z,CallbackInfoReturnable<Boolean> cir) {
        if(NativeWorld.hidden()) cir.setReturnValue(false);
    }
}
