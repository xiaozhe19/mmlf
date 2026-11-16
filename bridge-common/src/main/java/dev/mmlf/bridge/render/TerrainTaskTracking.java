package dev.mmlf.bridge.render;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * A section compile task that reports its upload to {@link TerrainMeshes} ({@code TerrainRebuildTaskMixin}). A loader
 * whose tasks are built by another constructor (NeoForge adds the section's extra renderers) hands the section over
 * through this, from its own mixin.
 */
public interface TerrainTaskTracking {
    void mmlf$track(SectionRenderDispatcher.RenderSection section);
}
