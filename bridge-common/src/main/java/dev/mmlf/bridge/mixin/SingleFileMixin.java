package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.RuntimeContext;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.sources.SingleFile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The submitting runtime must not enlarge the atlas it sends with the receiver's reserve. */
@Mixin(SingleFile.class)
public abstract class SingleFileMixin {
    @Shadow @Final private ResourceLocation resourceId;
    @Inject(method="run", at=@At("HEAD"), cancellable=true)
    private void mmlf$omitRemoteReserve(ResourceManager manager, SpriteSource.Output output, CallbackInfo ci) {
        if (!resourceId.equals(ResourceLocation.fromNamespaceAndPath("mmlf_bridge", "remote_reserve"))) return;
        ci.cancel();
        if (RuntimeContext.current().background() || !dev.mmlf.bridge.Experiments.GEOMETRY_REPLAY
                || !"on".equalsIgnoreCase(System.getProperty("mmlf.terrain",System.getenv().getOrDefault("MMLF_TERRAIN","off")))) return;
        ResourceLocation id=resourceId;
        int size=dev.mmlf.bridge.render.TerrainAtlas.reserveSize();
        output.add(id,loader -> {
            var image=new com.mojang.blaze3d.platform.NativeImage(size,size,false);
            image.fillRect(0,0,size,size,0x80ffffff);
            return new net.minecraft.client.renderer.texture.SpriteContents(id,
                    new net.minecraft.client.resources.metadata.animation.FrameSize(size,size),image,
                    net.minecraft.server.packs.resources.ResourceMetadata.EMPTY);
        });
    }
}
