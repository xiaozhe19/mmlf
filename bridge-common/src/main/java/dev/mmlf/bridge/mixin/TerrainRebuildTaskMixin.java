package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.render.TerrainMeshes;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets="net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$RebuildTask")
public abstract class TerrainRebuildTaskMixin implements dev.mmlf.bridge.render.TerrainTaskTracking {
    @Shadow protected RenderChunkRegion region;
    @Unique private SectionRenderDispatcher.RenderSection mmlf$renderSection;
    @Unique private BlockPos mmlf$origin;
    @Unique private long mmlf$version;
    @Unique private boolean mmlf$hasRegion;
    @Inject(method="<init>",at=@At("TAIL"))
    private void mmlf$init(SectionRenderDispatcher.RenderSection section,double distance,RenderChunkRegion region,boolean priority,CallbackInfo ci) {
        mmlf$track(section);
    }
    /** Also called by a loader's own mixin for the constructor it builds tasks with ({@link TerrainTaskTracking}). */
    @Override public void mmlf$track(SectionRenderDispatcher.RenderSection section) {
        mmlf$renderSection=section; mmlf$origin=section.getOrigin().immutable();
    }
    @Inject(method="doTask",at=@At("HEAD"))
    private void mmlf$start(SectionBufferBuilderPack buffers,CallbackInfoReturnable<CompletableFuture<?>> cir) {
        mmlf$version=TerrainMeshes.version(); mmlf$hasRegion=region!=null;
    }
    @Inject(method="doTask",at=@At("RETURN"))
    private void mmlf$done(SectionBufferBuilderPack buffers,CallbackInfoReturnable<CompletableFuture<?>> cir) {
        long version=mmlf$version; BlockPos origin=mmlf$origin; var section=mmlf$renderSection;
        if(!mmlf$hasRegion) return;
        if(origin==null || section==null) { dev.mmlf.common.MmlfLog.sampled("render.submission","terrain_task_untracked",60000,java.util.Map.of()); return; }
        cir.getReturnValue().thenAccept(result->{
            if(result instanceof Enum<?> value && value.name().equals("SUCCESSFUL") && origin.equals(section.getOrigin()))
                TerrainMeshes.uploaded(SectionPos.of(origin).asLong(),version);
        });
    }
}
