package dev.mmlf.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mmlf.bridge.collab.MobSpawning;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.MobSpawnSettings.SpawnerData;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Each runtime draws its own share of the one world's spawns ({@link MobSpawning}). */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMixin {
    @Inject(method="getRandomSpawnMobAt",at=@At("RETURN"),cancellable=true)
    private static void mmlf$share(ServerLevel level,StructureManager structures,ChunkGenerator generator,MobCategory category,RandomSource random,BlockPos pos,CallbackInfoReturnable<Optional<SpawnerData>> cir) {
        Optional<SpawnerData> drawn=cir.getReturnValue(), kept=MobSpawning.natural(level,category,random,pos,drawn);
        if(kept!=drawn) cir.setReturnValue(kept);
    }
    @WrapOperation(method="spawnMobsForChunkGeneration",at=@At(value="INVOKE",target="Lnet/minecraft/util/random/WeightedRandomList;getRandom(Lnet/minecraft/util/RandomSource;)Ljava/util/Optional;"))
    private static Optional<SpawnerData> mmlf$generation(WeightedRandomList<SpawnerData> list,RandomSource random,Operation<Optional<SpawnerData>> original) {
        return MobSpawning.generation(original.call(list,random));
    }
}
