package dev.mmlf.neoforgetest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Plain native blocks for cross-runtime collaboration tests, written only against Minecraft: nothing here knows about
 * MMLF. Whatever another runtime's blocks do to them, they see through Minecraft's own signal, block and container calls.
 */
public final class CollabBlocks {
 private CollabBlocks() {}

 /** Lit: gives 15 to every side, and a comparator reads 9 from it. Toggled by using it. */
 public static final class SignalBlock extends Block {
  public SignalBlock() { super(Properties.of().strength(1.0f)); registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.LIT,false)); }
  @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) { b.add(BlockStateProperties.LIT); }
  @Override protected boolean isSignalSource(BlockState state) { return true; }
  @Override protected int getSignal(BlockState state,BlockGetter level,BlockPos pos,Direction direction) { return state.getValue(BlockStateProperties.LIT)?15:0; }
  @Override protected boolean hasAnalogOutputSignal(BlockState state) { return true; }
  @Override protected int getAnalogOutputSignal(BlockState state,Level level,BlockPos pos) { return state.getValue(BlockStateProperties.LIT)?9:0; }
  @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit) {
   if(!level.isClientSide) level.setBlock(pos,state.cycle(BlockStateProperties.LIT),3);
   return InteractionResult.sidedSuccess(level.isClientSide);
  }
 }

 /** Lit while any neighbour gives it a redstone signal, as a redstone lamp is. */
 public static final class LampBlock extends Block {
  public LampBlock() { super(Properties.of().strength(1.0f)); registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.LIT,false)); }
  @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) { b.add(BlockStateProperties.LIT); }
  @Override protected void neighborChanged(BlockState state,Level level,BlockPos pos,Block block,BlockPos from,boolean moved) { update(state,level,pos); }
  @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moved) { update(state,level,pos); }
  private static void update(BlockState state,Level level,BlockPos pos) {
   if(level.isClientSide) return;
   boolean powered=level.hasNeighborSignal(pos);
   if(powered!=state.getValue(BlockStateProperties.LIT)) level.setBlock(pos,state.setValue(BlockStateProperties.LIT,powered),2);
  }
 }

 /** Lit while a lit campfire is directly below it: it reads its neighbour's block and properties, not a signal. */
 public static final class ProbeBlock extends Block {
  public ProbeBlock() { super(Properties.of().strength(1.0f)); registerDefaultState(stateDefinition.any().setValue(BlockStateProperties.LIT,false)); }
  @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b) { b.add(BlockStateProperties.LIT); }
  @Override protected void neighborChanged(BlockState state,Level level,BlockPos pos,Block block,BlockPos from,boolean moved) { update(state,level,pos); }
  @Override protected void onPlace(BlockState state,Level level,BlockPos pos,BlockState old,boolean moved) { update(state,level,pos); }
  private static void update(BlockState state,Level level,BlockPos pos) {
   if(level.isClientSide) return;
   BlockState below=level.getBlockState(pos.below());
   boolean heated=below.is(Blocks.CAMPFIRE) && below.getValue(CampfireBlock.LIT);
   if(heated!=state.getValue(BlockStateProperties.LIT)) level.setBlock(pos,state.setValue(BlockStateProperties.LIT,heated),2);
  }
 }

 /** A nine-slot container hoppers can fill and empty. */
 public static final class CrateBlock extends Block implements EntityBlock {
  public CrateBlock() { super(Properties.of().strength(1.0f)); }
  @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state) { return new CrateBlockEntity(pos,state); }
 }
 public static java.util.function.Supplier<BlockEntityType<CrateBlockEntity>> crateType;
 public static final class CrateBlockEntity extends BlockEntity implements Container {
  private final NonNullList<ItemStack> items=NonNullList.withSize(9,ItemStack.EMPTY);
  public CrateBlockEntity(BlockPos pos,BlockState state) { super(crateType.get(),pos,state); }
  @Override public int getContainerSize() { return items.size(); }
  @Override public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
  @Override public ItemStack getItem(int slot) { return items.get(slot); }
  @Override public ItemStack removeItem(int slot,int amount) { ItemStack taken=ContainerHelper.removeItem(items,slot,amount); if(!taken.isEmpty()) setChanged(); return taken; }
  @Override public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items,slot); }
  @Override public void setItem(int slot,ItemStack stack) { items.set(slot,stack); setChanged(); }
  @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this,player); }
  @Override public void clearContent() { items.clear(); }
  @Override protected void loadAdditional(CompoundTag tag,HolderLookup.Provider registries) { super.loadAdditional(tag,registries); ContainerHelper.loadAllItems(tag,items,registries); }
  @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries) { super.saveAdditional(tag,registries); ContainerHelper.saveAllItems(tag,items,registries); }
  /** For self-tests: how many items of this kind the crate holds. */
  public int count(net.minecraft.world.item.Item item) { return items.stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum(); }
 }
}
