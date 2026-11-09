package dev.mmlf.bridge.mixin;

import java.util.Map;
import java.util.Queue;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The live particles by sheet, so the submitter can record the ones it submits. */
@Mixin(ParticleEngine.class)
public interface ParticleEngineAccessor {
    @Accessor("particles") Map<ParticleRenderType,Queue<Particle>> mmlf$particles();
}
