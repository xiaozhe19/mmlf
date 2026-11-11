package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.CoGeneration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Co-generation, background: of a biome's decoration only its own mods' placed features run; vanilla's are the
 * primary's. A mod's placed feature that uses a vanilla configuration (more iron ore) is the mod's and runs.
 */
@Mixin(PlacedFeature.class)
public abstract class HollowFeatureFilterMixin {
    @Inject(method="placeWithBiomeCheck",at=@At("HEAD"),cancellable=true)
    private void mmlf$ownOnly(WorldGenLevel level,ChunkGenerator generator,RandomSource random,BlockPos pos,CallbackInfoReturnable<Boolean> cir) {
        if(!CoGeneration.skipsVanillaDecoration()) return;
        var key=level.registryAccess().registryOrThrow(Registries.PLACED_FEATURE).getKey((PlacedFeature)(Object)this);
        if(key!=null && key.getNamespace().equals("minecraft")) cir.setReturnValue(false);
    }
}
