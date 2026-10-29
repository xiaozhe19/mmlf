package dev.mmlf.bridge;
import dev.mmlf.common.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.*;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import java.nio.file.*;
import java.util.*;

/** Opt-in isolated developer fixture, configured externally; no test mod compatibility logic. */
public final class DemoScene {
 private static boolean started,prepared,hidden,skipWait;
 private static int testTicks;
 /** The self-test is written for a remote demo block on +x; when this runtime's remote block is on -x, it is mirrored. */
 private static int side=1;
 private static double x(double value) { return side>0?value:1-value; }
 private static float yaw(float value) { return side>0?value:-value; }
 /** {@code properties} sets block state values by name; {@code items} ("id*count") fills a container block entity. */
 public record Placement(String runtime,String block,int x,int y,int z,boolean optional,Map<String,String> properties,List<String> items) {}
 public record Scene(List<Placement> placements) {}
 private static net.minecraft.world.level.block.state.BlockState withProperties(net.minecraft.world.level.block.state.BlockState state,Map<String,String> properties) {
  if(properties==null) return state;
  for(var entry:properties.entrySet()) for(var property:state.getProperties()) if(property.getName().equals(entry.getKey())) state=withValue(state,property,entry.getValue());
  return state;
 }
 private static <T extends Comparable<T>> net.minecraft.world.level.block.state.BlockState withValue(net.minecraft.world.level.block.state.BlockState state,net.minecraft.world.level.block.state.properties.Property<T> property,String value) {
  return property.getValue(value).map(v->state.setValue(property,v)).orElseThrow(()->new IllegalStateException("Invalid scenario property "+property.getName()+"="+value));
 }
 public static void tick(Minecraft mc,Session session,String runtime) {
  if(session==null || !Files.isRegularFile(session.root().resolve("scenario.json"))) return;
  if(prepared && !hidden && !BridgeCore.isPrimary() && "true".equals(System.getenv("MMLF_HIDE_SECONDARY"))) {
   org.lwjgl.glfw.GLFW.glfwHideWindow(mc.getWindow().getWindow()); hidden=true;
  }
  mc.options.pauseOnLostFocus=false;

  if(!started && mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
  // The primary opens its world only when a background runtime has loaded its own, so both appear together.
  if(!started && !skipWait && BridgeCore.isPrimary() && mc.level==null && !BridgeCore.remoteWorldReady()) {
   if(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) mc.setScreen(new WaitingScreen(()->{skipWait=true;mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());}));
   return;
  }
  if(!started && mc.screen instanceof WaitingScreen) mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
  if(!started && mc.level==null && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
   started=true;
   var settings=new LevelSettings("MMLF Demo",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
   mc.options.pauseOnLostFocus=false;
   mc.options.framerateLimit().set(60);
   mc.options.bobView().set(false);
   mc.options.fov().set(70);
   mc.createWorldOpenFlows().createFreshLevel("mmlf-"+System.currentTimeMillis(),settings,new WorldOptions(0L,false,false),
    registries->registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);
  }
  // Background runtimes may join a minute after the primary; the scripted test starts once a remote frame has arrived.
  if(prepared && BridgeCore.isPrimary() && BridgeCore.remoteLayerReady() && mc.player!=null && Files.exists(session.root().resolve("self-test"))) {
   testTicks++;
   if(testTicks>=100 && testTicks<=140) { mc.player.setYRot(yaw(180f+(testTicks-100)*12.53f/40f)); mc.player.setXRot(13.65f); }
   if(testTicks==125) net.minecraft.client.Screenshot.grab(mc.gameDirectory,"mmlf-turning-demo.png",mc.getMainRenderTarget(),message->System.out.println("MMLF screenshot "+message.getString()));
   if(testTicks==120 || testTicks==160) {
    float error=Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.gameRenderer.getMainCamera().getYRot()-mc.player.getYRot()));
    System.out.println("MMLF CAMERA CONTROL SELFTEST yawError="+error+" passed="+(error<2));
   }
   if(testTicks==220) {
    var box=new net.minecraft.world.phys.AABB(0.2,100,0.2,0.8,101.8,0.8);
    var motion=new net.minecraft.world.phys.Vec3(side,0,0);
    var shapes=BridgeCore.remoteCollisions(mc.player,box.expandTowards(motion));
    var resolved=net.minecraft.world.entity.Entity.collideBoundingBox(mc.player,motion,box,mc.level,shapes);
    System.out.println("MMLF COLLISION SELFTEST shapes="+shapes.size()+" dx="+resolved.x+" passed="+(!shapes.isEmpty() && Math.abs(resolved.x)<=0.20001));
   }
   if(testTicks==240) net.minecraft.client.Screenshot.grab(mc.gameDirectory,"mmlf-core-demo.png",mc.getMainRenderTarget(),message->System.out.println("MMLF screenshot "+message.getString()));
   if(testTicks==260) {
    var server=mc.getSingleplayerServer(); server.execute(()->server.getPlayerList().getPlayers().getFirst().connection.teleport(x(2.5),100,3.5,yaw(198.435f),25.68f));
   }
   if(testTicks==350) System.out.println("MMLF THIRD PARTY request="+BridgeCore.requestInteraction());
   // On the mirrored side the remote third-party block is a door, which the use step swung open out of the pick ray.
   if(testTicks==395 && side<0) System.out.println("MMLF DOOR CLOSE request="+BridgeCore.requestInteraction());
   if(testTicks==400) System.out.println("MMLF PICK SELFTEST request="+BridgeCore.requestInteraction("pick"));
   if(testTicks==420) {var server=mc.getSingleplayerServer();server.execute(()->server.getPlayerList().getPlayers().getFirst().connection.teleport(x(2.5),100,3.5,yaw(180),40));}
   if(testTicks==460) System.out.println("MMLF REMOTE PLACE SELFTEST request="+BridgeCore.requestInteraction());
   if(testTicks==540) System.out.println("MMLF PARTICLE SELFTEST request="+BridgeCore.requestInteraction("attack"));
   if(testTicks==548) net.minecraft.client.Screenshot.grab(mc.gameDirectory,"mmlf-particle-demo.png",mc.getMainRenderTarget(),message->System.out.println("MMLF screenshot "+message.getString()));
   if(testTicks==500) net.minecraft.client.Screenshot.grab(mc.gameDirectory,"mmlf-item-demo.png",mc.getMainRenderTarget(),message->System.out.println("MMLF screenshot "+message.getString()));
   if(testTicks==390) net.minecraft.client.Screenshot.grab(mc.gameDirectory,"mmlf-thirdparty-demo.png",mc.getMainRenderTarget(),message->System.out.println("MMLF screenshot "+message.getString()));
   if(testTicks==200) System.out.println("MMLF SELFTEST request="+BridgeCore.requestInteraction());
   if(testTicks>=600) GuiSelfTest.tick(mc,testTicks-600,side);
  }
  if(!prepared && mc.player!=null && mc.getSingleplayerServer()!=null && !mc.getSingleplayerServer().getPlayerList().getPlayers().isEmpty()) {
   prepared=true;
   try {
    Scene scene=Wire.JSON.fromJson(Files.readString(session.root().resolve("scenario.json")),Scene.class);
    for(Placement item:scene.placements()) if(!item.runtime().equals(runtime) && item.block().endsWith(":demo_block")) side=item.x()<0?-1:1;
    var server=mc.getSingleplayerServer();
    server.execute(()->{
     var level=server.overworld();
     level.setDayTime(6000);level.setWeatherParameters(0,0,false,false);
     level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
     level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
     for(int x=-7;x<=7;x++) for(int z=-6;z<=8;z++) {
      for(int y=100;y<=106;y++) level.setBlock(new BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
      level.setBlock(new BlockPos(x,99,z),net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),3);
     }
     for(Placement item:scene.placements()) if(item.runtime().equals(runtime)) {
      var key=ResourceLocation.parse(item.block());
      if(!BuiltInRegistries.BLOCK.containsKey(key)) { if(item.optional()) continue; throw new IllegalStateException("Missing scenario block "+key); }
      var blockState=withProperties(BuiltInRegistries.BLOCK.get(key).defaultBlockState(),item.properties());
      var at=new BlockPos(item.x(),item.y(),item.z());
      level.setBlock(at,blockState,3);
      if(key.getNamespace().equals("minecraft")) BridgeCore.ownVanilla(at);
      if(item.items()!=null && level.getBlockEntity(at) instanceof net.minecraft.world.Container container) {
       int slot=0;
       for(String entry:item.items()) {
        String[] parts=entry.split("\\*"); var itemKey=ResourceLocation.parse(parts[0]);
        if(slot<container.getContainerSize() && BuiltInRegistries.ITEM.containsKey(itemKey)) container.setItem(slot++,new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(itemKey),parts.length>1?Integer.parseInt(parts[1]):1));
       }
      }
      if(blockState.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF))
       level.setBlock(at.above(),blockState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER),3);
     }
     var player=server.getPlayerList().getPlayers().getFirst();
     player.teleportTo(0.5,100,4.5); player.setYRot(180); player.setXRot(10);
     mc.execute(()->{mc.player.setPos(0.5,100,4.5);mc.player.setYRot(180);mc.player.setXRot(10);});
     System.out.println("MMLF SCENE READY runtime="+runtime);
    });
   } catch(Exception e) {throw new IllegalStateException("MMLF scenario failed",e);}
  }
 }
}
