package dev.mmlf.bridge.mixin;

import dev.mmlf.bridge.SharedWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** One map ({@link SharedWorld}): this runtime's copy of the player using or breaking acts for the primary's player. */
@Mixin(ServerPlayerGameMode.class)
public abstract class AuthoredPlayerMixin {
    @Inject(method="useItemOn",at=@At("HEAD"))
    private void mmlf$useOnBegin(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> cir) { SharedWorld.begin(true); }
    @Inject(method="useItemOn",at=@At("RETURN"))
    private void mmlf$useOnEnd(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,BlockHitResult hit,CallbackInfoReturnable<InteractionResult> cir) { SharedWorld.end(); }
    @Inject(method="useItem",at=@At("HEAD"))
    private void mmlf$useBegin(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,CallbackInfoReturnable<InteractionResult> cir) { SharedWorld.begin(true); }
    @Inject(method="useItem",at=@At("RETURN"))
    private void mmlf$useEnd(ServerPlayer player,Level level,ItemStack stack,InteractionHand hand,CallbackInfoReturnable<InteractionResult> cir) { SharedWorld.end(); }
    @Inject(method="destroyBlock",at=@At("HEAD"))
    private void mmlf$destroyBegin(BlockPos pos,CallbackInfoReturnable<Boolean> cir) { SharedWorld.begin(true); }
    @Inject(method="destroyBlock",at=@At("RETURN"))
    private void mmlf$destroyEnd(BlockPos pos,CallbackInfoReturnable<Boolean> cir) { SharedWorld.end(); }
}
