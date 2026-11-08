package dev.mmlf.bridge;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import java.util.List;
import java.util.Optional;

/** {@code thin_world} experiment: a flat overworld of a single air layer, with no structures or features. */
final class VoidWorld {
    private VoidWorld() {}
    static WorldDimensions dimensions(RegistryAccess registries) {
        var dimensions=registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions();
        var biomes=registries.lookupOrThrow(Registries.BIOME);
        var settings=FlatLevelGeneratorSettings.getDefault(biomes,registries.lookupOrThrow(Registries.STRUCTURE_SET),registries.lookupOrThrow(Registries.PLACED_FEATURE))
            .withBiomeAndLayers(List.of(new FlatLayerInfo(1,Blocks.AIR)),Optional.empty(),biomes.getOrThrow(Biomes.PLAINS));
        return dimensions.replaceOverworldGenerator(registries,new FlatLevelSource(settings));
    }
}
