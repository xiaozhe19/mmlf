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
 public void onInitialize() {
  var id=ResourceLocation.fromNamespaceAndPath("mmlf_fabric_test","demo_block");
  Registry.register(BuiltInRegistries.BLOCK,id,BLOCK);
  Registry.register(BuiltInRegistries.ITEM,id,new BlockItem(BLOCK,new Item.Properties()));
  for(var entry:java.util.Map.<String,Block>of("gui_block",GUI_BLOCK,"box_block",BOX_BLOCK).entrySet()) {
   var key=ResourceLocation.fromNamespaceAndPath("mmlf_fabric_test",entry.getKey());
   Registry.register(BuiltInRegistries.BLOCK,key,entry.getValue());
   Registry.register(BuiltInRegistries.ITEM,key,new BlockItem(entry.getValue(),new Item.Properties()));
  }
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
}
