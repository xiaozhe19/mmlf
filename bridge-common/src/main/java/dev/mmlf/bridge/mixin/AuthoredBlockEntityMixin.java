package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.SharedWorld;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One map ({@link SharedWorld}): a block entity of this runtime ticking (a mod machine) is this runtime acting. In a
 * hollow world it ticks only where the primary's map answers.
 */
@Mixin(targets="net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class AuthoredBlockEntityMixin {
    @Shadow @Final private BlockEntity blockEntity;
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void mmlf$begin(CallbackInfo ci) {
        boolean server=blockEntity.getLevel()!=null && !blockEntity.getLevel().isClientSide();
        // Hollow world: a machine where the primary's map does not answer waits (SharedWorld.covered).
        if(server && !SharedWorld.covered(blockEntity.getLevel(),blockEntity.getBlockPos().getX()>>4,blockEntity.getBlockPos().getZ()>>4)) { SharedWorld.begin(false); ci.cancel(); return; }
        SharedWorld.begin(server && SharedWorld.ownBlock(blockEntity.getBlockState(),blockEntity.getBlockPos()));
    }
    @Inject(method="tick",at=@At("RETURN"))
    private void mmlf$end(CallbackInfo ci) { SharedWorld.end(); }
}
