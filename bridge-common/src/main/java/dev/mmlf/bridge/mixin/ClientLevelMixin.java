package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.render.OwnedSectionCompiler;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * World-data change events for the unified render layer's submission compiler. These are the client level's own
 * entry points, which every renderer is notified through; renderer methods themselves are often replaced (Sodium
 * overwrites LevelRenderer.setSectionDirty), so they are not observed here.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method="onChunkLoaded",at=@At("HEAD"),require=0)
    private void mmlf$chunkLoaded(ChunkPos pos,CallbackInfo ci) {
        dev.mmlf.bridge.Occupancy.clientChunkLoaded((ClientLevel)(Object)this,pos);
        if(dev.mmlf.bridge.Experiments.GEOMETRY) OwnedSectionCompiler.chunkLoaded((ClientLevel)(Object)this,pos);
    }
    @Inject(method="unload",at=@At("HEAD"),require=0)
    private void mmlf$chunkUnloaded(LevelChunk chunk,CallbackInfo ci) {
        if(dev.mmlf.bridge.Experiments.GEOMETRY) OwnedSectionCompiler.chunkUnloaded((ClientLevel)(Object)this,chunk);
    }
    @Inject(method="sendBlockUpdated",at=@At("HEAD"),require=0)
    private void mmlf$blockUpdated(BlockPos pos,BlockState old,BlockState now,int flags,CallbackInfo ci) {
        dev.mmlf.bridge.DemoScene.watch(pos,old,now,false);
        if(dev.mmlf.bridge.Experiments.GEOMETRY) OwnedSectionCompiler.blockChanged((ClientLevel)(Object)this,pos);
    }
    @Inject(method="setBlocksDirty",at=@At("HEAD"),require=0)
    private void mmlf$blocksDirty(BlockPos pos,BlockState old,BlockState now,CallbackInfo ci) {
        if(dev.mmlf.bridge.Experiments.GEOMETRY) OwnedSectionCompiler.blockChanged((ClientLevel)(Object)this,pos);
    }
    /** Light updates and section-wide changes. */
    @Inject(method="setSectionDirtyWithNeighbors",at=@At("HEAD"),require=0)
    private void mmlf$sectionDirty(int x,int y,int z,CallbackInfo ci) {
        if(dev.mmlf.bridge.Experiments.GEOMETRY) OwnedSectionCompiler.sectionChanged((ClientLevel)(Object)this,x,y,z);
    }
}
