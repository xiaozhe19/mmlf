package dev.mmlf.neoforge.mixin;

import dev.mmlf.bridge.render.TerrainTaskTracking;
import java.util.List;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NeoForge builds section compile tasks with its own constructor (it adds the section's extra renderers), which the
 * common {@code TerrainRebuildTaskMixin} does not see. Without this, no remote section was ever confirmed uploaded on
 * a NeoForge render host, and the entity proxy drew every remote section a second time (remote glass hid the water
 * behind it and flickered).
 */
@Mixin(targets="net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
public abstract class NeoForgeRebuildTaskMixin {
    @Inject(method="<init>(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;DLnet/minecraft/client/renderer/chunk/RenderChunkRegion;ZLjava/util/List;)V",at=@At("TAIL"))
    private void mmlf$track(SectionRenderDispatcher.RenderSection section,double distance,RenderChunkRegion region,boolean priority,List<?> renderers,CallbackInfo ci) {
        ((TerrainTaskTracking)(Object)this).mmlf$track(section);
    }
}
