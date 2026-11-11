package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.SharedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One map ({@link SharedWorld}): an entity of this runtime ticking, or a scheduled tick of its own block, is this
 * runtime acting; what it changes in the map goes to the primary. Other entities and blocks only simulate this
 * runtime's own copy of the vanilla world.
 * <p>
 * In a hollow world nothing acts where the primary's map does not answer ({@link SharedWorld#covered}): entities and
 * random ticks there skip the tick, as in a chunk that is not ticking, and scheduled ticks wait a tick and try again, so
 * none is lost. A skipped entity tick still opens a scope, closed by the RETURN handler or the per-tick reset.
 */
@Mixin(ServerLevel.class)
public abstract class AuthoredTickMixin {
    @Inject(method="tickNonPassenger",at=@At("HEAD"),cancellable=true)
    private void mmlf$entityBegin(Entity entity,CallbackInfo ci) {
        if(!(entity instanceof Player) && !(entity instanceof dev.mmlf.bridge.render.RemoteProxy)
            && !SharedWorld.covered(entity.level(),SectionPos.blockToSectionCoord(entity.getBlockX()),SectionPos.blockToSectionCoord(entity.getBlockZ()))) {
            SharedWorld.begin(false); ci.cancel(); return;
        }
        SharedWorld.begin(dev.mmlf.bridge.render.DynamicCapture.owned(entity));
    }
    @Inject(method="tickNonPassenger",at=@At("RETURN"))
    private void mmlf$entityEnd(Entity entity,CallbackInfo ci) { SharedWorld.end(); }
    @Inject(method="tickBlock",at=@At("HEAD"),cancellable=true)
    private void mmlf$blockBegin(BlockPos pos,Block block,CallbackInfo ci) {
        ServerLevel level=(ServerLevel)(Object)this;
        if(!SharedWorld.covered(level,pos.getX()>>4,pos.getZ()>>4)) { level.scheduleTick(pos,block,1); SharedWorld.begin(false); ci.cancel(); return; }
        SharedWorld.begin(SharedWorld.ownBlock(block.defaultBlockState(),pos));
    }
    @Inject(method="tickBlock",at=@At("RETURN"))
    private void mmlf$blockEnd(BlockPos pos,Block block,CallbackInfo ci) { SharedWorld.end(); }
    @Inject(method="tickFluid",at=@At("HEAD"),cancellable=true)
    private void mmlf$fluid(BlockPos pos,Fluid fluid,CallbackInfo ci) {
        ServerLevel level=(ServerLevel)(Object)this;
        if(!SharedWorld.covered(level,pos.getX()>>4,pos.getZ()>>4)) { level.scheduleTick(pos,fluid,1); ci.cancel(); }
    }
    @Inject(method="tickChunk",at=@At("HEAD"),cancellable=true)
    private void mmlf$chunk(LevelChunk chunk,int randomTickSpeed,CallbackInfo ci) {
        if(!SharedWorld.covered((ServerLevel)(Object)this,chunk.getPos().x,chunk.getPos().z)) ci.cancel();
    }
}
