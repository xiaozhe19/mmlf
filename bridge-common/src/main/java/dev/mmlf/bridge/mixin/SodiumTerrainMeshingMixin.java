package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.render.TerrainMeshes;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Associates a Sodium mesh result with the terrain snapshot used when its worker started. */
@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask",remap=false)
public abstract class SodiumTerrainMeshingMixin {
    @Unique private long mmlf$version=-1, mmlf$section;
    @Unique private static final java.util.concurrent.atomic.AtomicBoolean mmlf$reported=new java.util.concurrent.atomic.AtomicBoolean();
    @Inject(method="execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",at=@At("HEAD"),require=0,remap=false)
    private void mmlf$start(CallbackInfoReturnable<Object> result) {
        mmlf$version=TerrainMeshes.version();
        if(mmlf$reported.compareAndSet(false,true)) dev.mmlf.common.MmlfLog.info("render.submission","terrain_sodium_compile",java.util.Map.of("version",mmlf$version));
        try {
            var field=getClass().getSuperclass().getDeclaredField("render"); field.setAccessible(true);
            Object section=field.get(this); Class<?> type=section.getClass();
            int x=(int)type.getMethod("getChunkX").invoke(section),y=(int)type.getMethod("getChunkY").invoke(section),z=(int)type.getMethod("getChunkZ").invoke(section);
            mmlf$section=SectionPos.asLong(x,y,z);
        } catch(ReflectiveOperationException | RuntimeException e) { mmlf$version=-1; dev.mmlf.common.MmlfLog.warn("render.submission","terrain_sodium_reflection_failed",java.util.Map.of("error",e.toString())); }
    }
    @Inject(method="execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",at=@At("RETURN"),require=0,remap=false)
    private void mmlf$finish(CallbackInfoReturnable<Object> result) {
        if(mmlf$version>=0) TerrainMeshes.sodiumOutput(result.getReturnValue(),mmlf$section,mmlf$version);
    }
}
