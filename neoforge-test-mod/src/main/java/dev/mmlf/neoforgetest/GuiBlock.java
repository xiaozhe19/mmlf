package dev.mmlf.neoforgetest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
/** Opens an ordinary client-side screen, as configuration-style mod blocks do; nothing here knows about MMLF. */
public final class GuiBlock extends Block {
 public GuiBlock() { super(Properties.of().strength(1.0f)); }
 @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
  if(level.isClientSide) TestScreens.open(pos);
  return InteractionResult.sidedSuccess(level.isClientSide);
 }
}
