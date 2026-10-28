package dev.mmlf.fabrictest;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import java.util.HashMap;
import java.util.Map;
/** A nine-slot server-side container opened through the vanilla menu path; contents are kept for the session only. */
public final class BoxBlock extends Block {
 private static final Map<String,SimpleContainer> contents=new HashMap<>();
 public BoxBlock() { super(Properties.of().strength(1.0f)); }
 @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
  if(!level.isClientSide) {
   SimpleContainer container=contents.computeIfAbsent(level.dimension().location()+"|"+pos.asLong(),k->{
    var c=new SimpleContainer(9); c.setItem(0,new ItemStack(Items.COBBLESTONE,16)); c.setItem(1,new ItemStack(Items.EMERALD,3));
    c.addListener(changed->System.out.println("MMLF TEST BOX loader=fabric changed slot0="+changed.getItem(0)+" slot1="+changed.getItem(1)));
    return c;
   });
   player.openMenu(new SimpleMenuProvider((id,inventory,p)->new ChestMenu(MenuType.GENERIC_9x1,id,inventory,container,1),Component.literal("MMLF Fabric Test Box")));
  }
  return InteractionResult.sidedSuccess(level.isClientSide);
 }
}
