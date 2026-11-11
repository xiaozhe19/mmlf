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
 private static int createFixtureTicks;
 /** The self-test is written for a remote demo block on +x; when this runtime's remote block is on -x, it is mirrored. */
 private static int side=1;
 private static double x(double value) { return side>0?value:1-value; }
 private static float yaw(float value) { return side>0?value:-value; }
 /** {@code properties} sets block state values by name; {@code items} ("id*count") fills a container block entity. */
 public record Placement(String runtime,String block,int x,int y,int z,boolean optional,Map<String,String> properties,List<String> items) {}
 /**
  * An entity the scene spawns in its owner's world, which then owns it ({@link RemoteEntities#claim}) whatever its type.
  * {@code equipment} maps slot names ({@code head}, {@code mainhand}, ...) to item ids; {@code profession} gives a
  * villager a fixed trade set; {@code invulnerable} keeps it alive among the scene's hostile mobs.
  */
 public record SceneEntity(String runtime,String type,double x,double y,double z,float yaw,Map<String,String> equipment,String profession,boolean invulnerable,String name) {}
 public record Scene(List<Placement> placements,List<SceneEntity> entities) {}
 /** The scene as loaded, for the collaboration self-test. */
 static Scene scene;
 /** Diagnostics: positions of this runtime's scene blocks (with the upper half of double blocks), watched for changes. */
 private static final java.util.Set<Long> watched=java.util.concurrent.ConcurrentHashMap.newKeySet();
 private static volatile long watchUntil;
 /** A watched scene block changed, on the server ({@code server}) or the client; the server side records who changed it. */
 public static void watch(BlockPos pos,net.minecraft.world.level.block.state.BlockState old,net.minecraft.world.level.block.state.BlockState now,boolean server) {
  if(watched.isEmpty() || System.currentTimeMillis()>watchUntil || !watched.contains(pos.asLong()) || java.util.Objects.equals(old,now)) return;
  var fields=new java.util.LinkedHashMap<String,Object>();
  fields.put("side",server?"server":"client"); fields.put("position",pos.toShortString()); fields.put("old",String.valueOf(old)); fields.put("new",String.valueOf(now));
  if(server) {
   var frames=new java.util.ArrayList<String>();
   for(var frame:Thread.currentThread().getStackTrace()) {
    String name=frame.getClassName();
    if(name.startsWith("java.") || name.contains("DemoScene") || name.contains("LevelChunk")) continue;
    frames.add(name.substring(name.lastIndexOf('.')+1)+"."+frame.getMethodName()+":"+frame.getLineNumber());
    if(frames.size()>=10) break;
   }
   fields.put("by",frames);
  }
  MmlfLog.warn("scene","scene_block_changed",fields);
 }
 static Session sceneSession;
 /**
  * A placement's runtime may be {@code primary} or {@code background} instead of a loader, and its block may name
  * {@code {primary}} or {@code {background}}, so one scene tests the same arrangement whichever runtime is the primary.
  */
 static String owner(Placement item,Session session) { return owner(item.runtime(),session); }
 static String owner(String runtime,Session session) {
  return switch(runtime) { case "primary" -> session.primary(); case "background" -> background(session); default -> runtime; };
 }
 /** Spawns (or, in a world saved with it, finds again) one scene entity and claims it for this runtime. */
 private static void spawn(net.minecraft.server.level.ServerLevel level,SceneEntity item,int index) {
  String tag="mmlf_scene_entity_"+index;
  var area=new net.minecraft.world.phys.AABB(item.x()-16,item.y()-8,item.z()-16,item.x()+16,item.y()+8,item.z()+16);
  var found=level.getEntities((net.minecraft.world.entity.Entity)null,area,e->e.getTags().contains(tag));
  net.minecraft.world.entity.Entity entity=found.isEmpty()?null:found.getFirst();
  if(entity==null) {
   var type=net.minecraft.world.entity.EntityType.byString(item.type()).orElse(null);
   if(type==null) { MmlfLog.warn("scene","scene_entity_unknown",Map.of("type",item.type())); return; }
   entity=type.create(level);
   if(entity==null) return;
   entity.moveTo(item.x(),item.y(),item.z(),item.yaw(),0);
   entity.addTag(tag);
   if(entity instanceof net.minecraft.world.entity.Mob mob) {
    mob.setPersistenceRequired();
    if(item.equipment()!=null) for(var slot:item.equipment().entrySet()) {
     var itemKey=ResourceLocation.parse(slot.getValue());
     if(BuiltInRegistries.ITEM.containsKey(itemKey)) { var equipment=net.minecraft.world.entity.EquipmentSlot.byName(slot.getKey()); mob.setItemSlot(equipment,new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(itemKey))); mob.setDropChance(equipment,0); }
    }
   }
   if(entity instanceof net.minecraft.world.entity.npc.Villager villager && item.profession()!=null) {
    var profession=BuiltInRegistries.VILLAGER_PROFESSION.get(ResourceLocation.parse(item.profession()));
    villager.setVillagerData(villager.getVillagerData().setProfession(profession).setLevel(1));
    villager.setVillagerXp(1); // keeps the profession without a job site
   }
   level.addFreshEntity(entity);
  }
  // A world saved with the entity keeps what an earlier scene set; the current scene decides.
  entity.setInvulnerable(item.invulnerable());
  // {@code name} shows above the entity; {owner} becomes the loader that owns it, e.g. "NeoForge 僵尸".
  if(item.name()!=null && !item.name().isBlank()) {
   entity.setCustomName(net.minecraft.network.chat.Component.literal(item.name().replace("{owner}",SessionStatus.displayName(BridgeCore.runtimeId()))));
   entity.setCustomNameVisible(true);
  }
  RemoteEntities.claim(entity);
  MmlfLog.info("scene","scene_entity",Map.of("type",item.type(),"uuid",entity.getUUID().toString(),"spawned",found.isEmpty()));
 }
 static String block(Placement item,Session session) { return item.block().replace("{primary}",session.primary()).replace("{background}",background(session)); }
 private static String background(Session session) { return session.primary().equals("fabric")?"neoforge":"fabric"; }
 private static net.minecraft.world.level.block.state.BlockState withProperties(net.minecraft.world.level.block.state.BlockState state,Map<String,String> properties) {
  if(properties==null) return state;
  for(var entry:properties.entrySet()) for(var property:state.getProperties()) if(property.getName().equals(entry.getKey())) state=withValue(state,property,entry.getValue());
  return state;
 }
 private static <T extends Comparable<T>> net.minecraft.world.level.block.state.BlockState withValue(net.minecraft.world.level.block.state.BlockState state,net.minecraft.world.level.block.state.properties.Property<T> property,String value) {
  return property.getValue(value).map(v->state.setValue(property,v)).orElseThrow(()->new IllegalStateException("Invalid scenario property "+property.getName()+"="+value));
 }
 /** Set on the server thread once this runtime's part of the scene is placed. */
 private static volatile boolean applied;
 /** Whether this runtime's world is in its final shape: no scene in this session, or its scene placed. */
 static boolean settled(Session session) { return session==null || !Files.isRegularFile(session.root().resolve("scenario.json")) || applied; }
 /**
  * Client thread: whether this runtime's client world already shows its placed scene, floor included. Its server's
  * block updates reach the client seconds later while the fresh world is still loading or paused (a resource reload).
  */
 static boolean shown(Minecraft mc,Session session) {
  if(!settled(session)) return false;
  Scene placed=scene;
  if(placed==null || sceneSession!=session) return session==null || !Files.isRegularFile(session.root().resolve("scenario.json"));
  var level=mc.level; if(level==null) return false;
  if(!(Experiments.THIN_WORLD && !BridgeCore.isPrimary()) && !level.getBlockState(new BlockPos(0,99,4)).is(net.minecraft.world.level.block.Blocks.SMOOTH_STONE)) return false;
  String runtime=BridgeCore.isPrimary()?session.primary():background(session);
  for(Placement item:placed.placements()) {
   if(!owner(item,session).equals(runtime) || !BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(block(item,session)))) continue;
   if(level.getBlockState(new BlockPos(item.x(),item.y(),item.z())).isAir()) return false;
  }
  return true;
 }
 public static void tick(Minecraft mc,Session session,String runtime) {
  if(session==null || !Files.isRegularFile(session.root().resolve("scenario.json"))) return;
  if(prepared && !hidden && !BridgeCore.isPrimary() && RuntimeContext.current().hiddenWindow()) {
   org.lwjgl.glfw.GLFW.glfwHideWindow(mc.getWindow().getWindow()); hidden=true;
  }
  if(prepared && createFixtureTicks<300 && "neoforge".equals(runtime) && mc.getSingleplayerServer()!=null && Files.exists(session.root().resolve("self-test"))
      && BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse("create:cogwheel")) && ++createFixtureTicks==300) {
   var server=mc.getSingleplayerServer();
   server.execute(()->{
    try {
     var level=server.overworld();
     var motor=level.getBlockEntity(new BlockPos(5,100,1)); var cog=level.getBlockEntity(new BlockPos(6,100,1));
     if(motor==null || cog==null) throw new IllegalStateException("fixture block entity missing");
     float motorSpeed=((Number)motor.getClass().getMethod("getSpeed").invoke(motor)).floatValue();
     float cogSpeed=((Number)cog.getClass().getMethod("getSpeed").invoke(cog)).floatValue();
     System.out.println("MMLF CREATE SELFTEST motorRpm="+motorSpeed+" cogRpm="+cogSpeed+" passed="+(Float.isFinite(motorSpeed) && Float.isFinite(cogSpeed) && motorSpeed!=0 && cogSpeed!=0));
    } catch(ReflectiveOperationException | RuntimeException e) {
     System.out.println("MMLF CREATE SELFTEST reason="+e.getClass().getSimpleName()+" passed=false");
    }
   });
  }
  // A release primary is the player's own game: its options (saved to options.txt) stay as the player set them.
  boolean playerOptions=SessionBootstrap.release() && BridgeCore.isPrimary();
  if(!playerOptions) mc.options.pauseOnLostFocus=false;
  // thin_world: a void world has nothing to stand on, and the player falls out of it before the primary's pose arrives.
  if(Experiments.THIN_WORLD && !BridgeCore.isPrimary() && mc.getSingleplayerServer()!=null) {
   var server=mc.getSingleplayerServer();
   server.execute(()->{ for(var player:server.getPlayerList().getPlayers()) { player.setNoGravity(true); if(player.getY()<0) player.teleportTo(0.5,100,4.5); } });
  }

  if(!started && mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen) mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
  // The primary opens its world only when a background runtime has loaded its own, so both appear together.
  if(!started && !skipWait && !SessionBootstrap.solo() && BridgeCore.isPrimary() && mc.level==null && !BridgeCore.remoteWorldReady()) {
   if(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) mc.setScreen(new WaitingScreen(()->{skipWait=true;mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());}));
   return;
  }
  if(!started && mc.screen instanceof WaitingScreen) mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
  if(!started && !BridgeCore.isPrimary() && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen && !BridgeCore.initialLanguageReady()) return;
  if(!started && mc.level==null && mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
   started=true;
   boolean release=SessionBootstrap.release();
   var settings=new LevelSettings(release?TestPlatform.LEVEL_NAME:"MMLF Demo",GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
   if(!playerOptions) {
    mc.options.pauseOnLostFocus=false;
    mc.options.framerateLimit().set(60);
    mc.options.bobView().set(false);
    mc.options.fov().set(70);
   }
   // Release: one fixed save in the player's (or the background's) saves, rebuilt fresh each time.
   String folder=release?TestPlatform.freshSave(mc):"mmlf-"+System.currentTimeMillis();
   boolean thin=Experiments.THIN_WORLD && !BridgeCore.isPrimary();
   if(thin) MmlfLog.info("experiment","thin_world_create",Map.of("runtime",runtime));
   if(Experiments.SMALL_VIEW && !BridgeCore.isPrimary()) mc.options.renderDistance().set(2);
   mc.createWorldOpenFlows().createFreshLevel(folder,settings,new WorldOptions(0L,false,false),
    registries->thin ? VoidWorld.dimensions(registries) : registries.registryOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).createWorldDimensions(),mc.screen);
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
   if(GuiSelfTest.finished()) CollabSelfTest.tick(mc);
   if(CollabSelfTest.finished() && Experiments.PROBE) ExperimentProbe.tick(mc);
  }
  if(!prepared && mc.player!=null && mc.getSingleplayerServer()!=null && !mc.getSingleplayerServer().getPlayerList().getPlayers().isEmpty()) {
   prepared=true;
   try {
    Scene scene=Wire.JSON.fromJson(Files.readString(session.root().resolve("scenario.json")),Scene.class);
    DemoScene.scene=scene; sceneSession=session;
    for(Placement item:scene.placements()) if(!item.runtime().equals(runtime) && item.block().endsWith(":demo_block")) side=item.x()<0?-1:1;
    var server=mc.getSingleplayerServer();
    server.execute(()->{
     var level=server.overworld();
     // Manual sessions start at 10d+2000: a low morning sun with long shadows, within villager work hours (the scene farmer works at its composter). The self-tests keep noon.
     level.setDayTime(Files.exists(session.root().resolve("self-test"))?6000:242000);level.setWeatherParameters(0,0,false,false);
     level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
     level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false,server);
     // Scene mobs need a difficulty that keeps hostile ones; nothing else may spawn among them.
     if(scene.entities()!=null && !scene.entities().isEmpty()) {
      server.setDifficulty(Difficulty.NORMAL,true);
      level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
     }
     // A thin background has no vanilla terrain of its own: the floor exists only in the primary's world.
     if(!(Experiments.THIN_WORLD && !BridgeCore.isPrimary())) for(int x=-7;x<=7;x++) for(int z=-6;z<=8;z++) {
      for(int y=100;y<=106;y++) level.setBlock(new BlockPos(x,y,z),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);
      level.setBlock(new BlockPos(x,99,z),net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),3);
     }
     for(Placement item:scene.placements()) if(owner(item,session).equals(runtime)) {
      var key=ResourceLocation.parse(block(item,session));
      if(!BuiltInRegistries.BLOCK.containsKey(key)) { if(item.optional()) continue; throw new IllegalStateException("Missing scenario block "+key); }
      var blockState=withProperties(BuiltInRegistries.BLOCK.get(key).defaultBlockState(),item.properties());
      var at=new BlockPos(item.x(),item.y(),item.z());
      level.setBlock(at,blockState,3);
      if(key.getNamespace().equals("minecraft")) BridgeCore.ownVanilla(level,at);
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
     if(scene.entities()!=null) for(int i=0;i<scene.entities().size();i++) {
      SceneEntity item=scene.entities().get(i);
      if(owner(item.runtime(),session).equals(runtime)) spawn(level,item,i);
     }
     watched.clear(); watchUntil=System.currentTimeMillis()+120_000;
     for(Placement item:scene.placements()) if(owner(item,session).equals(runtime)) { var at=new BlockPos(item.x(),item.y(),item.z()); watched.add(at.asLong()); watched.add(at.above().asLong()); }
     // Diagnostics: two seconds later, every block this runtime placed should still be there.
     var placed=new java.util.ArrayList<java.util.Map.Entry<BlockPos,net.minecraft.world.level.block.state.BlockState>>();
     for(Placement item:scene.placements()) if(owner(item,session).equals(runtime)) {
      var at=new BlockPos(item.x(),item.y(),item.z()); var state=level.getChunkAt(at).getSection(level.getSectionIndex(at.getY())).getBlockState(at.getX()&15,at.getY()&15,at.getZ()&15);
      if(!state.isAir()) placed.add(java.util.Map.entry(at,state));
     }
     server.tell(new net.minecraft.server.TickTask(server.getTickCount()+40,()->{
      for(var entry:placed) {
       BlockPos at=entry.getKey(); var raw=level.getChunkAt(at).getSection(level.getSectionIndex(at.getY())).getBlockState(at.getX()&15,at.getY()&15,at.getZ()&15);
       var read=level.getBlockState(at);
       if(!raw.equals(entry.getValue()) || !read.equals(entry.getValue()))
        MmlfLog.warn("scene","placement_lost",Map.of("position",at.toShortString(),"placed",entry.getValue().toString(),"raw",raw.toString(),"read",read.toString(),"mirrored",NeighborMirror.mirrored(level,at)));
      }
      MmlfLog.info("scene","placements_checked",Map.of("runtime",runtime,"count",placed.size()));
     }));
     var player=server.getPlayerList().getPlayers().getFirst();
     player.teleportTo(0.5,100,4.5); player.setYRot(180); player.setXRot(10);
     mc.execute(()->{mc.player.setPos(0.5,100,4.5);mc.player.setYRot(180);mc.player.setXRot(10);});
     applied=true;
     System.out.println("MMLF SCENE READY runtime="+runtime);
    });
   } catch(Exception e) {throw new IllegalStateException("MMLF scenario failed",e);}
  }
 }
}
