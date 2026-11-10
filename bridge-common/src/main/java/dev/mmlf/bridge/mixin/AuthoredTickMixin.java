package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.SharedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One map ({@link SharedWorld}): an entity of this runtime ticking, or a scheduled tick of its own block, is this
 * runtime acting; what it changes in the map goes to the primary. Other entities and blocks only simulate this
 * runtime's own copy of the vanilla world.
 */
@Mixin(ServerLevel.class)
public abstract class AuthoredTickMixin {
    @Inject(method="tickNonPassenger",at=@At("HEAD"))
    private void mmlf$entityBegin(Entity entity,CallbackInfo ci) { SharedWorld.begin(dev.mmlf.bridge.render.DynamicCapture.owned(entity)); }
    @Inject(method="tickNonPassenger",at=@At("RETURN"))
    private void mmlf$entityEnd(Entity entity,CallbackInfo ci) { SharedWorld.end(); }
    @Inject(method="tickBlock",at=@At("HEAD"))
    private void mmlf$blockBegin(BlockPos pos,Block block,CallbackInfo ci) { SharedWorld.begin(SharedWorld.ownBlock(block.defaultBlockState(),pos)); }
    @Inject(method="tickBlock",at=@At("RETURN"))
    private void mmlf$blockEnd(BlockPos pos,Block block,CallbackInfo ci) { SharedWorld.end(); }
}
