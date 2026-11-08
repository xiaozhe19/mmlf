package dev.mmlf.bridge;

import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.Messages.ObjectState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.*;

/**
 * Scripted check of cross-runtime collaboration in the demo scene, run by the primary after the GUI self-test: signals
 * between mod blocks of both runtimes and between vanilla redstone and remote mod blocks, a comparator reading a remote
 * block, a remote mod block reading the primary's campfire, hoppers filling and emptying remote crates, and the
 * boundary index surviving a move away from the scene and the unloading and reloading of its chunks. Backgrounds take
 * part through {@code mmlf:selftest} events. Every result is printed as {@code MMLF ... SELFTEST ... passed=}.
 */
final class CollabSelfTest {
    private CollabSelfTest() {}
    private static int t;
    private static boolean done;
    private static int sceneObjects;
    /** Unloaded phase: ticks waited, whether the primary still has a scene chunk (read on the server thread), and since when it has none. */
    private static int unloadWait,unloadedFor=-1;
    private static volatile boolean sceneLoaded=true;
    private static final Map<String,Integer> report=new HashMap<>();
    static boolean finished() { return done; }

    static void tick(Minecraft mc) {
        if(done || mc.getSingleplayerServer()==null || DemoScene.scene==null) return;
        var server=mc.getSingleplayerServer(); t++;
        switch(t) {
            case 1 -> {
                // The scene is at the world spawn, whose chunks stay loaded: without this, it could never unload.
                server.execute(()->keepNoSpawnChunks(server));
                BridgeCore.sendReliable("event",background(),new Event("mmlf:selftest",Map.of("action","spawn_chunks")));
                signals(server,true);
            }
            case 60 -> check(server,true,"on");
            case 61 -> signals(server,false);
            case 120 -> check(server,false,"off");
            case 121 -> { report.clear(); BridgeCore.sendReliable("event",background(),new Event("mmlf:selftest",Map.of("action","report"))); }
            case 160 -> containers(server);
            case 170 -> { sceneObjects=remoteInScene(); teleport(server,40.5); }
            case 230 -> { int n=remoteInScene(); System.out.println("MMLF BOUNDARY SELFTEST phase=away objects="+n+" expected="+sceneObjects+" passed="+(sceneObjects>0 && n==sceneObjects)); }
            case 231 -> teleport(server,1000.5);
            case 530 -> { if(!unloaded(server)) t--; }
            case 531 -> teleport(server,0.5);
            case 830 -> { int n=remoteInScene(); System.out.println("MMLF BOUNDARY SELFTEST phase=reloaded objects="+n+" expected="+sceneObjects+" passed="+(n==sceneObjects)); }
            case 831 -> signals(server,true);
            case 890 -> check(server,true,"reloaded");
            case 891 -> signals(server,false);
            case 950 -> { check(server,false,"reloaded_off"); System.out.println("MMLF COLLAB SELFTEST complete"); done=true; }
            default -> { }
        }
    }

    /**
     * The boundary must follow the primary's own chunks: once none of the scene's chunks is loaded in the primary's
     * world, no other runtime's object may remain there for longer than a message round trip (40 ticks). Chunks unload
     * asynchronously after saving, so the primary's own unloading is waited for, up to 1500 ticks.
     */
    private static boolean unloaded(MinecraftServer server) {
        unloadWait++;
        server.execute(()->{ var chunks=server.overworld().getChunkSource(); boolean any=false; for(int x=-1;x<=0;x++) for(int z=-1;z<=0;z++) any|=chunks.hasChunk(x,z); sceneLoaded=any; });
        if(!sceneLoaded && unloadedFor<0) unloadedFor=0;
        if(unloadedFor>=0) unloadedFor++;
        int n=remoteInScene();
        if(unloadedFor>=0 && (n==0 || unloadedFor>=40)) {
            System.out.println("MMLF BOUNDARY SELFTEST phase=unloaded objects="+n+" primaryUnloadedAfterTicks="+(unloadWait-unloadedFor)+" remoteClearedAfterTicks="+(n==0?unloadedFor:-1)+" passed="+(n==0));
            return true;
        }
        if(unloadWait==100 || unloadWait>=1500) server.execute(()->{
            var chunks=server.overworld().getChunkSource(); var info=new StringBuilder();
            for(int x=-1;x<=0;x++) for(int z=-1;z<=0;z++) info.append(" [").append(x).append(',').append(z).append(' ').append(chunks.getChunkDebugData(new net.minecraft.world.level.ChunkPos(x,z)).replaceAll("§.","").replace('\n','/')).append(']');
            System.out.println("MMLF BOUNDARY DIAG wait="+unloadWait+" loaded="+chunks.getLoadedChunksCount()+" pending="+chunks.getPendingTasksCount()+info);
        });
        if(unloadWait>=1500) { System.out.println("MMLF BOUNDARY SELFTEST phase=unloaded objects="+n+" reason=primary_never_unloaded passed=false"); return true; }
        return false;
    }

    /** Primary: switches its own scene signals and asks the background to switch its own. */
    private static void signals(MinecraftServer server,boolean on) {
        BridgeCore.sendReliable("event",background(),new Event("mmlf:selftest",Map.of("action","signals","on",Boolean.toString(on))));
        server.execute(()->{
            ServerLevel level=server.overworld();
            switchOwnSignals(level,on);
            for(var item:placements("minecraft:lever")) {
                BlockPos pos=pos(item); var state=level.getBlockState(pos);
                if(state.getBlock() instanceof LeverBlock lever && state.getValue(LeverBlock.POWERED)!=on) lever.pull(state,level,pos,null);
            }
            for(var item:placements("minecraft:campfire")) { BlockPos pos=pos(item); var state=level.getBlockState(pos); if(state.is(Blocks.CAMPFIRE)) level.setBlock(pos,state.setValue(CampfireBlock.LIT,on),3); }
        });
    }
    private static void switchOwnSignals(ServerLevel level,boolean on) {
        for(var item:DemoScene.scene.placements()) {
            if(!DemoScene.owner(item,DemoScene.sceneSession).equals(BridgeCore.runtimeId()) || !DemoScene.block(item,DemoScene.sceneSession).endsWith(":signal_block")) continue;
            BlockPos pos=pos(item); var state=level.getBlockState(pos);
            if(state.hasProperty(BlockStateProperties.LIT)) level.setBlock(pos,state.setValue(BlockStateProperties.LIT,on),3);
        }
    }
    private static void check(MinecraftServer server,boolean on,String phase) {
        server.execute(()->{
            ServerLevel level=server.overworld();
            List<String> modToMod=new ArrayList<>(), vanillaToMod=new ArrayList<>();
            boolean modOk=true, vanillaOk=true;
            for(var item:DemoScene.scene.placements()) {
                String block=DemoScene.block(item,DemoScene.sceneSession); if(!block.endsWith(":lamp_block")) continue;
                Boolean lit=lit(level,item);
                // The lamp beside the primary's wire is powered by vanilla redstone; the others by the other runtime's signal block.
                boolean byVanilla=item.x()==2 && item.z()==-6;
                (byVanilla?vanillaToMod:modToMod).add(item.runtime()+"@"+item.x()+"="+lit);
                if(!Objects.equals(lit,on)) { if(byVanilla) vanillaOk=false; else modOk=false; }
            }
            var lamp=level.getBlockState(new BlockPos(6,100,-6));
            boolean vanillaLamp=lamp.is(Blocks.REDSTONE_LAMP) && lamp.getValue(BlockStateProperties.LIT);
            int comparator=level.getBlockEntity(new BlockPos(-1,100,7)) instanceof ComparatorBlockEntity c ? c.getOutputSignal() : -1;
            System.out.println("MMLF REDSTONE SELFTEST phase="+phase+" mod_to_mod="+modToMod+" vanilla_to_mod="+vanillaToMod+" mod_to_vanilla="+vanillaLamp+" comparator="+comparator
                +" passed="+(modOk && vanillaOk && vanillaLamp==on && comparator==(on?9:0)));
            Boolean probe=remoteLit(new BlockPos(2,101,7));
            System.out.println("MMLF NEIGHBOR SELFTEST phase="+phase+" probe="+probe+" passed="+Objects.equals(probe,on));
        });
    }
    private static void containers(MinecraftServer server) {
        server.execute(()->{
            ServerLevel level=server.overworld();
            int above=count(level,new BlockPos(5,101,7),"minecraft:cobblestone"), below=count(level,new BlockPos(-5,100,7),"minecraft:emerald");
            Integer pushed=report.get("push"), left=report.get("pull");
            int waiting=ContainerBridge.waiting();
            System.out.println("MMLF CONTAINER SELFTEST push hopper="+above+" remote_crate="+pushed+" pull hopper="+below+" remote_crate="+left+" waiting="+waiting
                +" passed="+(above==0 && Objects.equals(pushed,8) && below==4 && Objects.equals(left,0) && waiting==0));
        });
    }

    /** Both: the other side's part. */
    static void receive(Minecraft mc,String source,Event event) {
        var server=mc.getSingleplayerServer(); if(server==null || DemoScene.scene==null) return;
        if(event.topic().equals("mmlf:selftest_report")) {
            event.values().forEach((k,v)->{ try { report.put(k,Integer.parseInt(v)); } catch(NumberFormatException ignored) { } });
            return;
        }
        String action=event.values().get("action");
        if("signals".equals(action)) server.execute(()->switchOwnSignals(server.overworld(),Boolean.parseBoolean(event.values().get("on"))));
        if("spawn_chunks".equals(action)) server.execute(()->keepNoSpawnChunks(server));
        if("report".equals(action)) server.execute(()->{
            ServerLevel level=server.overworld();
            var values=Map.of("push",Integer.toString(count(level,new BlockPos(5,100,7),"minecraft:cobblestone")),"pull",Integer.toString(count(level,new BlockPos(-5,101,7),"minecraft:emerald")));
            BridgeCore.sendReliable("event",source,new Event("mmlf:selftest_report",values));
        });
    }

    private static Boolean lit(ServerLevel level,DemoScene.Placement item) {
        BlockPos pos=pos(item);
        if(DemoScene.owner(item,DemoScene.sceneSession).equals(BridgeCore.runtimeId())) { var s=level.getBlockState(pos); return s.hasProperty(BlockStateProperties.LIT) ? s.getValue(BlockStateProperties.LIT) : null; }
        return remoteLit(pos);
    }
    private static Boolean remoteLit(BlockPos pos) {
        ObjectState o=Boundary.remote("minecraft:overworld",pos);
        return o==null || o.state().get("lit")==null ? null : Boolean.parseBoolean(o.state().get("lit"));
    }
    private static int count(ServerLevel level,BlockPos pos,String item) {
        if(!(level.getBlockEntity(pos) instanceof Container container)) return -1;
        var kind=BuiltInRegistries.ITEM.get(ResourceLocation.parse(item)); int n=0;
        for(int i=0;i<container.getContainerSize();i++) if(container.getItem(i).is(kind)) n+=container.getItem(i).getCount();
        return n;
    }
    private static int remoteInScene() {
        int n=0;
        for(ObjectState o:Boundary.remoteObjects()) if(o.dimension().equals("minecraft:overworld") && Math.abs(o.x())<=8 && Math.abs(o.z())<=8) n++;
        return n;
    }
    private static void keepNoSpawnChunks(MinecraftServer server) {
        server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_SPAWN_CHUNK_RADIUS).set(0,server);
    }
    private static void teleport(MinecraftServer server,double x) {
        server.execute(()->server.getPlayerList().getPlayers().getFirst().connection.teleport(x,100,4.5,180,10));
    }
    private static List<DemoScene.Placement> placements(String block) {
        return DemoScene.scene.placements().stream().filter(p->DemoScene.owner(p,DemoScene.sceneSession).equals(BridgeCore.runtimeId()) && DemoScene.block(p,DemoScene.sceneSession).equals(block)).toList();
    }
    private static BlockPos pos(DemoScene.Placement item) { return new BlockPos(item.x(),item.y(),item.z()); }
    private static String background() { return DemoScene.sceneSession.primary().equals("fabric")?"neoforge":"fabric"; }
}
