package dev.mmlf.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mmlf.bridge.collab.MobSpawning;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CustomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A background's vanilla special spawners (phantoms, patrols, cats, wandering trader) make the primary's mobs: they stay off. */
@Mixin(ServerLevel.class)
public abstract class CustomSpawnerMixin {
    @WrapOperation(method="tickCustomSpawners",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/CustomSpawner;tick(Lnet/minecraft/server/level/ServerLevel;ZZ)I"))
    private int mmlf$vanillaOff(CustomSpawner spawner,ServerLevel level,boolean enemies,boolean friendlies,Operation<Integer> original) {
        return MobSpawning.skipCustomSpawner(spawner) ? 0 : original.call(spawner,level,enemies,friendlies);
    }
}
