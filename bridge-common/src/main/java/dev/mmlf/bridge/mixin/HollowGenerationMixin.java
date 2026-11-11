package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.CoGeneration;
import dev.mmlf.bridge.SharedWorld;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hollow worlds ({@link SharedWorld#hollow}): a background that reads the primary's map computes none of it. Its chunks
 * still go through every status, but biomes, surface, carvers and initial mobs do nothing. These are the status tasks,
 * not the generator's methods, so every dimension and every generator (mods' too) is covered.
 * <p>
 * With co-generation ({@link CoGeneration}) the terrain step takes the primary's blocks and biomes, and the decoration
 * step runs this runtime's own mods' decoration on them for the primary. Without it, terrain and decoration are
 * skipped too (the biomes are then copied from the primary's chunks once loaded, {@code SharedWorld.copyBiomes}).
 */
@Mixin(ChunkStatusTasks.class)
public abstract class HollowGenerationMixin {
    @Inject(method={"generateBiomes","generateSurface","generateCarvers","generateSpawn"},at=@At("HEAD"),cancellable=true)
    private static void mmlf$hollow(WorldGenContext context,ChunkStep step,StaticCache2D<GenerationChunkHolder> cache,ChunkAccess chunk,CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if(SharedWorld.hollow()) cir.setReturnValue(CompletableFuture.completedFuture(chunk));
    }
    @Inject(method="generateNoise",at=@At("HEAD"),cancellable=true)
    private static void mmlf$terrain(WorldGenContext context,ChunkStep step,StaticCache2D<GenerationChunkHolder> cache,ChunkAccess chunk,CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if(!SharedWorld.hollow()) return;
        cir.setReturnValue(CoGeneration.backgroundDecorates() ? CoGeneration.fill(context.level(),chunk) : CompletableFuture.completedFuture(chunk));
    }
    /** Without co-generation, features are skipped too; the heightmaps they would prime still are. */
    @Inject(method="generateFeatures",at=@At("HEAD"),cancellable=true)
    private static void mmlf$hollowFeatures(WorldGenContext context,ChunkStep step,StaticCache2D<GenerationChunkHolder> cache,ChunkAccess chunk,CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if(!SharedWorld.hollow() || CoGeneration.backgroundDecorates()) return;
        Heightmap.primeHeightmaps(chunk,ChunkStatus.FINAL_HEIGHTMAPS);
        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
    }
}
