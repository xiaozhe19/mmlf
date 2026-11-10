package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.SharedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * One map for both runtimes ({@link SharedWorld}): the other runtime's light reads answer the primary's light, so its
 * entities are lit (and its mobs see the sky) as the primary's world is. Overrides the interface's default method.
 */
@Mixin(Level.class)
public abstract class LevelLightMixin {
    public int getBrightness(LightLayer layer,BlockPos pos) {
        Level self=(Level)(Object)this;
        int shared=SharedWorld.light(self,layer,pos);
        return shared>=0 ? shared : self.getLightEngine().getLayerListener(layer).getLightValue(pos);
    }
}
