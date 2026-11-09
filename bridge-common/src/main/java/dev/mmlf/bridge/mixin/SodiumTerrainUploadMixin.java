package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.render.TerrainMeshes;
import java.util.Collection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager",remap=false)
public abstract class SodiumTerrainUploadMixin {
    @org.spongepowered.asm.mixin.Unique private static final java.util.concurrent.atomic.AtomicBoolean mmlf$reported=new java.util.concurrent.atomic.AtomicBoolean();
    @Inject(method="uploadResults(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Ljava/util/Collection;)V",at=@At("TAIL"),require=0,remap=false)
    private void mmlf$uploaded(@Coerce Object command,Collection<?> outputs,CallbackInfo ci) { if(mmlf$reported.compareAndSet(false,true)) dev.mmlf.common.MmlfLog.info("render.submission","terrain_sodium_upload_hook",java.util.Map.of("outputs",outputs.size())); TerrainMeshes.sodiumUploaded(outputs); }
}
