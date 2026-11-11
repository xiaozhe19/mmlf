package dev.mmlf.bridge.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Co-generation ({@code CoGeneration}): a chunk's holder, read from any thread (the visible map is replaced, never changed). */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("getVisibleChunkIfPresent") ChunkHolder mmlf$visible(long chunk);
}
