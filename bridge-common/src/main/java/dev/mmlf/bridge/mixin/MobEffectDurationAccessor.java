package dev.mmlf.bridge.mixin;

import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MobEffectInstance.class)
public interface MobEffectDurationAccessor {
    @Accessor("duration")
    void mmlf$setDuration(int duration);
}
