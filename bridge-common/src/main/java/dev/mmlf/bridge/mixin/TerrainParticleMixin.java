package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.StandInBlock;
import dev.mmlf.bridge.render.TerrainMeshes;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block particles (breaking, landing, running) of another runtime's block: the stand-in has no texture of its own, so
 * they take the texture of the remote block's converted quads at that position (in the reserved part of the block
 * atlas, {@link TerrainMeshes}); where none is known the particle is not shown rather than the missing texture.
 */
@Mixin(TerrainParticle.class)
public abstract class TerrainParticleMixin {
    @Shadow @Final private float uo;
    @Shadow @Final private float vo;
    /** The remote quad's atlas bounds {u0,v0,u1,v1}, or null for any other block. */
    @Unique private float[] mmlf$bounds;

    @Inject(method="<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDDLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",at=@At("TAIL"))
    private void mmlf$remote(ClientLevel level,double x,double y,double z,double xd,double yd,double zd,BlockState state,BlockPos pos,CallbackInfo ci) {
        if(!StandInBlock.is(state)) return;
        mmlf$bounds=TerrainMeshes.particleBounds(level,pos);
        if(mmlf$bounds==null) ((Particle)(Object)this).remove();
    }
    // As vanilla picks a quarter of the block's sprite: a quarter of the remote quad's area.
    @Inject(method="getU0",at=@At("HEAD"),cancellable=true)
    private void mmlf$u0(CallbackInfoReturnable<Float> cir) { if(mmlf$bounds!=null) cir.setReturnValue(mmlf$bounds[0]+(mmlf$bounds[2]-mmlf$bounds[0])*(uo+1f)/4f); }
    @Inject(method="getU1",at=@At("HEAD"),cancellable=true)
    private void mmlf$u1(CallbackInfoReturnable<Float> cir) { if(mmlf$bounds!=null) cir.setReturnValue(mmlf$bounds[0]+(mmlf$bounds[2]-mmlf$bounds[0])*uo/4f); }
    @Inject(method="getV0",at=@At("HEAD"),cancellable=true)
    private void mmlf$v0(CallbackInfoReturnable<Float> cir) { if(mmlf$bounds!=null) cir.setReturnValue(mmlf$bounds[1]+(mmlf$bounds[3]-mmlf$bounds[1])*vo/4f); }
    @Inject(method="getV1",at=@At("HEAD"),cancellable=true)
    private void mmlf$v1(CallbackInfoReturnable<Float> cir) { if(mmlf$bounds!=null) cir.setReturnValue(mmlf$bounds[1]+(mmlf$bounds[3]-mmlf$bounds[1])*(vo+1f)/4f); }
}
