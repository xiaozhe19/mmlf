package dev.mmlf.neoforgetest;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredBlock;
@Mod("mmlf_neoforge_test")
public final class NeoForgeTestMod {
 public static final DeferredRegister.Blocks BLOCKS=DeferredRegister.createBlocks("mmlf_neoforge_test");
 public static final DeferredRegister.Items ITEMS=DeferredRegister.createItems("mmlf_neoforge_test");
 public static final DeferredBlock<Block> BLOCK=BLOCKS.register("demo_block",DemoBlock::new);
 public static final DeferredBlock<Block> GUI_BLOCK=BLOCKS.register("gui_block",GuiBlock::new);
 public static final DeferredBlock<Block> BOX_BLOCK=BLOCKS.register("box_block",BoxBlock::new);
 public static final DeferredBlock<Block> SIGNAL_BLOCK=BLOCKS.register("signal_block",CollabBlocks.SignalBlock::new);
 public static final DeferredBlock<Block> LAMP_BLOCK=BLOCKS.register("lamp_block",CollabBlocks.LampBlock::new);
 public static final DeferredBlock<Block> PROBE_BLOCK=BLOCKS.register("probe_block",CollabBlocks.ProbeBlock::new);
 public static final DeferredBlock<Block> CRATE_BLOCK=BLOCKS.register("crate_block",CollabBlocks.CrateBlock::new);
 public static final DeferredRegister<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITIES=DeferredRegister.create(net.minecraft.core.registries.Registries.BLOCK_ENTITY_TYPE,"mmlf_neoforge_test");
 public static final java.util.function.Supplier<net.minecraft.world.level.block.entity.BlockEntityType<CollabBlocks.CrateBlockEntity>> CRATE_TYPE=
  BLOCK_ENTITIES.register("crate",()->net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(CollabBlocks.CrateBlockEntity::new,CRATE_BLOCK.get()).build(null));
 public NeoForgeTestMod(IEventBus bus) {
  CollabBlocks.crateType=CRATE_TYPE;
  BLOCKS.register(bus); ITEMS.register(bus); BLOCK_ENTITIES.register(bus);
  dev.mmlf.common.BridgeEvents.subscribe(event->{
   if(!event.topic().equals("mmlf.toggle")) return;
   var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer(); if(server==null) return;
   var v=event.values();
   try {
    var key=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(v.get("dimension")));
    var pos=new net.minecraft.core.BlockPos(Integer.parseInt(v.get("x")),Integer.parseInt(v.get("y")),Integer.parseInt(v.get("z")));
    server.execute(()->{var level=server.getLevel(key);if(level!=null && level.getBlockState(pos).is(BLOCK.get())) {
     level.setBlock(pos,level.getBlockState(pos).cycle(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT),3);
     System.out.println("MMLF FABRIC TO NEO EVENT state="+level.getBlockState(pos));
    }});
   } catch(RuntimeException ex) {System.err.println("MMLF test event rejected: "+ex.getMessage());}
  });
 }
 static {
  ITEMS.register("demo_block",()->new BlockItem(BLOCK.get(),new Item.Properties()));
  ITEMS.register("gui_block",()->new BlockItem(GUI_BLOCK.get(),new Item.Properties()));
  ITEMS.register("box_block",()->new BlockItem(BOX_BLOCK.get(),new Item.Properties()));
  ITEMS.register("signal_block",()->new BlockItem(SIGNAL_BLOCK.get(),new Item.Properties()));
  ITEMS.register("lamp_block",()->new BlockItem(LAMP_BLOCK.get(),new Item.Properties()));
  ITEMS.register("probe_block",()->new BlockItem(PROBE_BLOCK.get(),new Item.Properties()));
  ITEMS.register("crate_block",()->new BlockItem(CRATE_BLOCK.get(),new Item.Properties()));
 }
}
