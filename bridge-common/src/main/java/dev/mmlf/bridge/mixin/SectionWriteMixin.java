package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.CoGeneration;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Co-generation, background: every block a decoration writes, including ore placement that writes sections directly
 * rather than through the level. Only recorded on a thread that is decorating for the primary.
 */
@Mixin(LevelChunkSection.class)
public abstract class SectionWriteMixin {
    @Inject(method="setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",at=@At("HEAD"))
    private void mmlf$record(int x,int y,int z,BlockState state,boolean lock,CallbackInfoReturnable<BlockState> cir) {
        CoGeneration.sectionWrite((LevelChunkSection)(Object)this,x,y,z,state);
    }
}
