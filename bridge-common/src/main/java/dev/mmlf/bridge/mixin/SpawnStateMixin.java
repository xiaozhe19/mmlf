package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.collab.MobSpawning;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A category's cap counts the other runtime's mobs too ({@link MobSpawning}): both spawn into one world. */
@Mixin(NaturalSpawner.SpawnState.class)
public abstract class SpawnStateMixin {
    @Shadow @Final private int spawnableChunkCount;
    @Shadow @Final private Object2IntOpenHashMap<MobCategory> mobCategoryCounts;
    @Inject(method="canSpawnForCategory",at=@At("HEAD"),cancellable=true)
    private void mmlf$sharedCap(MobCategory category,ChunkPos chunk,CallbackInfoReturnable<Boolean> cir) {
        int remote=MobSpawning.remoteCount(category); if(remote<=0) return;
        int cap=category.getMaxInstancesPerChunk()*spawnableChunkCount/289;
        if(mobCategoryCounts.getInt(category)+remote>=cap) cir.setReturnValue(false);
    }
}
