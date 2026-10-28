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
 private static boolean started,prepared,hidden;
 private static int testTicks;
 public record Placement(String runtime,String block,int x,int y,int z,boolean optional) {}
 public record Scene(List<Placement> placements) {}
 public static void tick(Minecraft mc,Session session,String runtime) {
  if(session==null || !Files.isRegularFile(session.root().resolve("scenario.json"))) return;
  if(prepared && !hidden && runtime.equals("neoforge") && "true".equals(System.getenv("MMLF_HIDE_SECONDARY"))) {
   org.lwjgl.glfw.GLFW.glfwHideWindow(mc.getWindow().getWindow()); hidden=true;
  }
  mc.options.pauseOnLostFocus=false;

  if(!started && mc.screen!=null && mc.screen.getClass().getSimpleName().equals("AccessibilityOnboardingScreen")) mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
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
  if(prepared && runtime.equals("fabric") && mc.player!=null && Files.exists(session.root().resolve("self-test"))) {
   testTicks++;
   if(testTicks>=100 && testTicks<=140) { mc.player.setYRot(180f+(testTicks-100)*12.53f/40f); mc.player.setXRot(13.65f); }
   if(testTicks==120 || testTicks==160) {
    float error=Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.gameRenderer.getMainCamera().getYRot()-mc.player.getYRot()));
    System.out.println("MMLF CAMERA CONTROL SELFTEST yawError="+error+" passed="+(error<2));
   }
   if(testTicks==220) {
    var box=new net.minecraft.world.phys.AABB(0.2,100,0.2,0.8,101.8,0.8);
    var motion=new net.minecraft.world.phys.Vec3(1,0,0);
    var shapes=BridgeCore.remoteCollisions(mc.player,box.expandTowards(motion));
    var resolved=net.minecraft.world.entity.Entity.collideBoundingBox(mc.player,motion,box,mc.level,shapes);
    System.out.println("MMLF COLLISION SELFTEST shapes="+shapes.size()+" dx="+resolved.x+" passed="+(!shapes.isEmpty() && resolved.x<=0.20001));
   }
   if(testTicks==240) net.minecraft.client.Screenshot.grab(mc.gameDirectory,"mmlf-core-demo.png",mc.getMainRenderTarget(),message->System.out.println("MMLF screenshot "+message.getString()));
   if(testTicks==260) {
    var server=mc.getSingleplayerServer(); server.execute(()->server.getPlayerList().getPlayers().getFirst().connection.teleport(2.5,100,3.5,198.435f,25.68f));
   }
   if(testTicks==350) System.out.println("MMLF THIRD PARTY request="+BridgeCore.requestInteraction());
   if(testTicks==400) System.out.println("MMLF PICK SELFTEST request="+BridgeCore.requestInteraction("pick"));
   if(testTicks==420) {var server=mc.getSingleplayerServer();server.execute(()->server.getPlayerList().getPlayers().getFirst().connection.teleport(2.5,100,3.5,180,40));}
   if(testTicks==460) System.out.println("MMLF REMOTE PLACE SELFTEST request="+BridgeCore.requestInteraction());
   if(testTicks==540) System.out.println("MMLF PARTICLE SELFTEST request="+BridgeCore.requestInteraction("attack"));
   if(testTicks==548) net.minecraft.client.Screenshot.grab(mc.gameDirectory,"mmlf-particle-demo.png",mc.getMainRenderTarget(),message->System.out.println("MMLF screenshot "+message.getString()));
   if(testTicks==500) net.minecraft.client.Screenshot.grab(mc.gameDirectory,"mmlf-item-demo.png",mc.getMainRenderTarget(),message->System.out.println("MMLF screenshot "+message.getString()));
   if(testTicks==390) net.minecraft.client.Screenshot.grab(mc.gameDirectory,"mmlf-thirdparty-demo.png",mc.getMainRenderTarget(),message->System.out.println("MMLF screenshot "+message.getString()));
   if(testTicks==200) System.out.println("MMLF SELFTEST request="+BridgeCore.requestInteraction());
  }
  if(!prepared && mc.player!=null && mc.getSingleplayerServer()!=null && !mc.getSingleplayerServer().getPlayerList().getPlayers().isEmpty()) {
   prepared=true;
   try {
    Scene scene=Wire.JSON.fromJson(Files.readString(session.root().resolve("scenario.json")),Scene.class);
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
      var blockState=BuiltInRegistries.BLOCK.get(key).defaultBlockState();
      var at=new BlockPos(item.x(),item.y(),item.z());
      level.setBlock(at,blockState,3);
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
