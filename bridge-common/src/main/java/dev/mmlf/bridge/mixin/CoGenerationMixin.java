package dev.mmlf.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mmlf.bridge.CoGeneration;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Co-generation ({@link CoGeneration}): the decoration step of both runtimes. */
@Mixin(ChunkStatusTasks.class)
public abstract class CoGenerationMixin {
    @WrapOperation(method="generateFeatures",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/chunk/ChunkGenerator;applyBiomeDecoration(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/StructureManager;)V"))
    private static void mmlf$decorate(ChunkGenerator generator,WorldGenLevel region,ChunkAccess chunk,StructureManager structures,Operation<Void> original) {
        CoGeneration.decorate(region,chunk,()->original.call(generator,region,chunk,structures));
    }
}
