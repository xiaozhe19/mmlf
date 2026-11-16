package dev.mmlf.bridge.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.mmlf.bridge.collab.StandInFeel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A background's copy of the player leaves alone the block contact the primary runs on its stand-in itself
 * ({@link StandInFeel#followerContact}): falling on, bouncing, stepping on, being inside. Nothing happens twice.
 */
@Mixin(Entity.class)
public abstract class FollowerContactMixin {
    @WrapOperation(method="checkFallDamage",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/Block;fallOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;F)V"),require=0)
    private void mmlf$fall(Block block,Level level,BlockState state,BlockPos pos,Entity entity,float distance,Operation<Void> original) {
        if(!StandInFeel.followerFall(entity,state)) original.call(block,level,state,pos,entity,distance);
    }
    @WrapOperation(method="move",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/Block;updateEntityAfterFallOn(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;)V"),require=0)
    private void mmlf$bounce(Block block,BlockGetter level,Entity entity,Operation<Void> original) {
        if(!StandInFeel.followerBounce(entity,block.defaultBlockState())) original.call(block,level,entity);
    }
    @WrapOperation(method="move",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/Block;stepOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/Entity;)V"),require=0)
    private void mmlf$step(Block block,Level level,BlockPos pos,BlockState state,Entity entity,Operation<Void> original) {
        if(!StandInFeel.followerStep(entity,state)) original.call(block,level,pos,state,entity);
    }
    @WrapOperation(method="checkInsideBlocks",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase;entityInside(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;)V"),require=0)
    private void mmlf$inside(BlockBehaviour.BlockStateBase state,Level level,BlockPos pos,Entity entity,Operation<Void> original) {
        if(!StandInFeel.followerInside(entity,(BlockState)state)) original.call(state,level,pos,entity);
    }
    /** A custom block slowing the copy ({@link StandInFeel#followerTick}): the slowing goes to the primary's player. */
    @org.spongepowered.asm.mixin.injection.Inject(method="makeStuckInBlock",at=@At("HEAD"))
    private void mmlf$stuck(BlockState state,net.minecraft.world.phys.Vec3 multiplier,org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        StandInFeel.stuckFollower((Entity)(Object)this,multiplier);
    }
}
