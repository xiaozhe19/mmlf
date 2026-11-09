package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.RemoteEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What a block of this runtime drops as it breaks (items, experience) belongs to this runtime, whatever the items are:
 * entities added while it drops its resources are claimed ({@code FreshEntityMixin}). Every overload is covered.
 */
@Mixin(Block.class)
public abstract class BlockDropsMixin {
    /** One entry per overload entered (they call each other); only the one that opened the scope closes it. */
    @Unique private static final ThreadLocal<java.util.ArrayDeque<Boolean>> mmlf$opened=ThreadLocal.withInitial(java.util.ArrayDeque::new);
    @Unique private static void mmlf$begin(BlockState state,BlockPos pos) { mmlf$opened.get().push(RemoteEntities.beginBlockDrops(state,pos)); }
    @Unique private static void mmlf$end() { Boolean opened=mmlf$opened.get().poll(); if(Boolean.TRUE.equals(opened)) RemoteEntities.endDrops(); }

    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",at=@At("HEAD"))
    private static void mmlf$begin3(BlockState state,Level level,BlockPos pos,CallbackInfo ci) { mmlf$begin(state,pos); }
    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",at=@At("RETURN"))
    private static void mmlf$end3(BlockState state,Level level,BlockPos pos,CallbackInfo ci) { mmlf$end(); }

    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",at=@At("HEAD"))
    private static void mmlf$begin4(BlockState state,LevelAccessor level,BlockPos pos,BlockEntity blockEntity,CallbackInfo ci) { mmlf$begin(state,pos); }
    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",at=@At("RETURN"))
    private static void mmlf$end4(BlockState state,LevelAccessor level,BlockPos pos,BlockEntity blockEntity,CallbackInfo ci) { mmlf$end(); }

    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",at=@At("HEAD"))
    private static void mmlf$begin6(BlockState state,Level level,BlockPos pos,BlockEntity blockEntity,Entity entity,ItemStack tool,CallbackInfo ci) { mmlf$begin(state,pos); }
    @Inject(method="dropResources(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/item/ItemStack;)V",at=@At("RETURN"))
    private static void mmlf$end6(BlockState state,Level level,BlockPos pos,BlockEntity blockEntity,Entity entity,ItemStack tool,CallbackInfo ci) { mmlf$end(); }
}
