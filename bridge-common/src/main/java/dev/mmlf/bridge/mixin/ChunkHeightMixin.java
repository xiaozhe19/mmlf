package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.SharedWorld;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A hollow world's heights count the primary's map ({@link SharedWorld#height}); everywhere else they are the chunk's own. */
@Mixin(ChunkAccess.class)
public abstract class ChunkHeightMixin {
    @Inject(method="getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I",at=@At("RETURN"),cancellable=true)
    private void mmlf$height(Heightmap.Types type,int x,int z,CallbackInfoReturnable<Integer> cir) {
        if(!((Object)this instanceof LevelChunk chunk)) return;
        int own=cir.getReturnValueI(), height=SharedWorld.height(chunk,type,x,z,own);
        if(height!=own) cir.setReturnValue(height);
    }
}
