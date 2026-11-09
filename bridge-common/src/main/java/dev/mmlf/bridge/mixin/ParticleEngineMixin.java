package dev.mmlf.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.mmlf.bridge.render.NativeWorld;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A submitting runtime whose content the render host draws leaves the particles of the standard sheets out of its
 * world pass: the ones it owns are recorded and submitted ({@code DynamicCapture}), the rest belong to the world the
 * render host already shows. Particles with custom rendering stay in the composited fallback frame. NeoForge's
 * overload of {@code render} has the same loop.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {
    @WrapOperation(method="render*",at=@At(value="INVOKE",target="Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"),require=0)
    private void mmlf$hidden(Particle particle,VertexConsumer consumer,Camera camera,float partial,Operation<Void> original) {
        // Particles on the submitted sheets are recorded by the submitter (or belong to the render host's world).
        if(NativeWorld.hidden() && dev.mmlf.bridge.render.DynamicCapture.particleSheet(particle.getRenderType())!=null) return;
        original.call(particle,consumer,camera,partial);
    }
}
