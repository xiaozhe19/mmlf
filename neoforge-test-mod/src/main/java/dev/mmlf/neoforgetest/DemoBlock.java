package dev.mmlf.neoforgetest;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
public final class DemoBlock extends Block {
 public DemoBlock() { super(Properties.of().strength(1.0f)); registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.LIT,false)); }
 @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) { b.add(BlockStateProperties.LIT); }
 @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
  if (!level.isClientSide) {
    level.setBlock(pos,state.cycle(BlockStateProperties.LIT),3);
    dev.mmlf.common.BridgeEvents.emit("fabric","mmlf.toggle",java.util.Map.of("dimension",level.dimension().location().toString(),"x","-1","y","100","z","0"));
  }
  return InteractionResult.sidedSuccess(level.isClientSide);
 }
}
