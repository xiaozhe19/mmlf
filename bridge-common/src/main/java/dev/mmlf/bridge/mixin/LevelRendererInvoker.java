package dev.mmlf.bridge.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The section-dirty call vanilla uses for a block the player changed: {@code true} compiles the section on the render
 * thread before the next frame when it is near the camera (Sodium's overwrite of the same method schedules it as
 * important), instead of on a worker some frames later.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererInvoker {
    @Invoker("setSectionDirty") void mmlf$setSectionDirty(int sectionX,int sectionY,int sectionZ,boolean reRenderOnMainThread);
}
