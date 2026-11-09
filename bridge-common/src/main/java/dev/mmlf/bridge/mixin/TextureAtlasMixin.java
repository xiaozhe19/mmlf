package dev.mmlf.bridge.mixin;

import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A rebuilt atlas can change UVs; old meshes must not be replayed with it. */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixin {
    @Inject(method="upload",at=@At("TAIL"))
    private void mmlf$atlasReloaded(SpriteLoader.Preparations preparations,CallbackInfo ci) {
        if(dev.mmlf.bridge.Experiments.GEOMETRY && ((TextureAtlas)(Object)this).location().equals(TextureAtlas.LOCATION_BLOCKS))
            dev.mmlf.bridge.render.GeometryLayer.invalidateAssets();
    }
}
