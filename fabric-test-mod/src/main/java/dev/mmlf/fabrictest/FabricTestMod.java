package dev.mmlf.fabrictest;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
public final class FabricTestMod implements ModInitializer {
 public static final Block BLOCK=new DemoBlock();
 public static final Block GUI_BLOCK=new GuiBlock(), BOX_BLOCK=new BoxBlock();
 private static volatile net.minecraft.server.MinecraftServer server;
 public static Block STICKY_BLOCK;
 public static net.minecraft.world.entity.EntityType<TestMobs.TestCow> TEST_COW;
 public static net.minecraft.world.entity.EntityType<TestMobs.TestZombie> TEST_ZOMBIE;
 public void onInitialize() {
  var id=ResourceLocation.fromNamespaceAndPath("mmlf_fabric_test","demo_block");
  Registry.register(BuiltInRegistries.BLOCK,id,BLOCK);
  Registry.register(BuiltInRegistries.ITEM,id,new BlockItem(BLOCK,new Item.Properties()));
  Block crate=new CollabBlocks.CrateBlock();
  var crateType=Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,ResourceLocation.fromNamespaceAndPath("mmlf_fabric_test","crate"),
   net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder.create(CollabBlocks.CrateBlockEntity::new,crate).build());
  CollabBlocks.crateType=()->crateType;
  for(var entry:java.util.Map.<String,Block>of("gui_block",GUI_BLOCK,"box_block",BOX_BLOCK,"signal_block",new CollabBlocks.SignalBlock(),
    "lamp_block",new CollabBlocks.LampBlock(),"probe_block",new CollabBlocks.ProbeBlock(),"crate_block",crate).entrySet()) {
   var key=ResourceLocation.fromNamespaceAndPath("mmlf_fabric_test",entry.getKey());
   Registry.register(BuiltInRegistries.BLOCK,key,entry.getValue());
   Registry.register(BuiltInRegistries.ITEM,key,new BlockItem(entry.getValue(),new Item.Properties()));
  }
  registerFeelAndMobs();
  net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(s->server=s);
  net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(s->server=null);
  dev.mmlf.common.BridgeEvents.subscribe(event->{
   if(!event.topic().equals("mmlf.toggle") || server==null) return;
   var data=event.values();
   try {
    var dim=net.minecraft.resources.ResourceLocation.parse(data.get("dimension"));
    var key=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,dim);
    var pos=new net.minecraft.core.BlockPos(Integer.parseInt(data.get("x")),Integer.parseInt(data.get("y")),Integer.parseInt(data.get("z")));
    server.execute(()->{
     var level=server.getLevel(key); if(level==null) return;
     var state=level.getBlockState(pos);
     if(state.is(BLOCK)) {
      level.setBlock(pos,state.cycle(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT),3);
      System.out.println("MMLF REVERSE EVENT state="+level.getBlockState(pos));
     }
    });
   } catch(RuntimeException ex) { System.err.println("MMLF test event ignored: "+ex.getMessage()); }
  });
 }
 /** Stand-in feel test blocks ({@link FeelBlocks}) and naturally spawning test mobs ({@link TestMobs}). */
 private static void registerFeelAndMobs() {
  for(var sound:java.util.List.of(FeelBlocks.CHIME_STEP,FeelBlocks.CHIME_FALL)) Registry.register(BuiltInRegistries.SOUND_EVENT,sound.getLocation(),sound);
  STICKY_BLOCK=new FeelBlocks.StickyBlock();
  var blocks=new java.util.LinkedHashMap<String,Block>();
  blocks.put("slippery_block",FeelBlocks.slippery()); blocks.put("slow_block",FeelBlocks.slow()); blocks.put("bouncy_block",new FeelBlocks.BouncyBlock());
  blocks.put("springy_block",new FeelBlocks.SpringyBlock()); blocks.put("sticky_block",STICKY_BLOCK); blocks.put("chime_block",FeelBlocks.chime());
  for(var entry:blocks.entrySet()) {
   var key=ResourceLocation.fromNamespaceAndPath("mmlf_fabric_test",entry.getKey());
   Registry.register(BuiltInRegistries.BLOCK,key,entry.getValue());
   Registry.register(BuiltInRegistries.ITEM,key,new BlockItem(entry.getValue(),new Item.Properties()));
  }
  TEST_COW=Registry.register(BuiltInRegistries.ENTITY_TYPE,ResourceLocation.fromNamespaceAndPath("mmlf_fabric_test","test_cow"),
   net.minecraft.world.entity.EntityType.Builder.<TestMobs.TestCow>of(TestMobs.TestCow::new,net.minecraft.world.entity.MobCategory.CREATURE).sized(0.9f,1.4f).clientTrackingRange(10).build("test_cow"));
  TEST_ZOMBIE=Registry.register(BuiltInRegistries.ENTITY_TYPE,ResourceLocation.fromNamespaceAndPath("mmlf_fabric_test","test_zombie"),
   net.minecraft.world.entity.EntityType.Builder.<TestMobs.TestZombie>of(TestMobs.TestZombie::new,net.minecraft.world.entity.MobCategory.MONSTER).sized(0.6f,1.95f).eyeHeight(1.74f).passengerAttachments(2.0125f).ridingOffset(-0.7f).clientTrackingRange(8).build("test_zombie"));
  net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(TEST_COW,net.minecraft.world.entity.animal.Cow.createAttributes());
  net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(TEST_ZOMBIE,net.minecraft.world.entity.monster.Zombie.createAttributes());
  net.fabricmc.fabric.api.biome.v1.BiomeModifications.addSpawn(net.fabricmc.fabric.api.biome.v1.BiomeSelectors.includeByKey(
    net.minecraft.world.level.biome.Biomes.PLAINS,net.minecraft.world.level.biome.Biomes.SUNFLOWER_PLAINS,net.minecraft.world.level.biome.Biomes.FOREST,net.minecraft.world.level.biome.Biomes.MEADOW),
   net.minecraft.world.entity.MobCategory.CREATURE,TEST_COW,12,2,4);
  net.fabricmc.fabric.api.biome.v1.BiomeModifications.addSpawn(net.fabricmc.fabric.api.biome.v1.BiomeSelectors.foundInOverworld(),
   net.minecraft.world.entity.MobCategory.MONSTER,TEST_ZOMBIE,60,1,2);
 }
}
