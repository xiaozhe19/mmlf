package dev.mmlf.bridge;

import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.Messages.ObjectState;
import dev.mmlf.common.MmlfLog;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LeverBlock;
import java.util.*;

/**
 * {@code probe} experiment, run by the primary after the collaboration self-test, with the same scene:
 * <ol>
 * <li>{@code static} then {@code turning}: 30 s each with the camera still, then swinging, for the render metrics that
 * every runtime logs ({@code metrics/runtime}, {@code metrics/remote_frame}); the phases are printed with wall times.</li>
 * <li>{@code latency}: the scene lever is pulled 40 times; for each pull, the primary server tick on which the
 * background's lamp beside the wire is seen in the new state is recorded. Under lockstep the background also reports
 * the primary tick its lamp switched in.</li>
 * <li>{@code heat}: a Farmer's Delight cooking pot of the background on the primary's campfire, and the scene's pot on
 * its own stove, are asked whether they are heated.</li>
 * </ol>
 */
final class ExperimentProbe {
    private ExperimentProbe() {}
    private static final BlockPos LEVER=new BlockPos(0,100,-6), LAMP=new BlockPos(2,100,-6);
    private static final BlockPos CAMPFIRE=new BlockPos(6,100,3), POT_ON_CAMPFIRE=new BlockPos(6,101,3), POT_ON_STOVE=new BlockPos(4,101,-3);
    private static final int STATIC=600, TURNING=600, PULLS=40, PULL_TICKS=13;
    private static int t;
    private static boolean done;
    /** Primary server thread: the pull being timed. */
    private static long pullTick=-1, pullNanos;
    private static boolean expected;
    private static final List<Long> roundTicks=new ArrayList<>(), oneWayTicks=new ArrayList<>();
    private static final List<Double> roundMs=new ArrayList<>();
    private static int timeouts;

    static boolean finished() { return done; }

    static void tick(Minecraft mc) {
        if(done || mc.player==null || mc.getSingleplayerServer()==null) return;
        var server=mc.getSingleplayerServer(); t++;
        if(t==1) {
            phase("static");
            server.execute(()->server.getPlayerList().getPlayers().getFirst().connection.teleport(0.5,100,4.5,180,10));
            server.execute(()->{ var level=server.overworld(); level.setBlock(CAMPFIRE,Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT,true),3); });
            BridgeCore.sendReliable("event",background(),new Event("mmlf:experiment",Map.of("action","place_pot")));
        }
        if(t==STATIC) phase("turning");
        if(t>STATIC && t<=STATIC+TURNING) {
            float yaw=180f+60f*(float)Math.sin(2*Math.PI*(t-STATIC)/80.0);
            mc.player.setYRot(yaw); mc.player.setYHeadRot(yaw); mc.player.setXRot(10);
        }
        int latencyStart=STATIC+TURNING+20;
        if(t==STATIC+TURNING+1) { mc.player.setYRot(180); mc.player.setXRot(10); phase("latency"); }
        if(t>=latencyStart && (t-latencyStart)%PULL_TICKS==0 && (t-latencyStart)/PULL_TICKS<PULLS) server.execute(()->pull(server));
        int end=latencyStart+PULLS*PULL_TICKS+20;
        if(t==end) {
            phase("heat");
            BridgeCore.sendReliable("event",background(),new Event("mmlf:experiment",Map.of("action","heat")));
        }
        if(t==end+60) { report(); done=true; phase("complete"); }
    }

    private static void phase(String name) {
        System.out.println("MMLF EXPERIMENT phase="+name+" wallMs="+System.currentTimeMillis()+" experiments="+Experiments.describe());
        MmlfLog.info("experiment","phase",Map.of("phase",name,"experiments",Experiments.describe()));
    }

    /** Primary server thread. */
    private static void pull(MinecraftServer server) {
        var level=server.overworld(); var state=level.getBlockState(LEVER);
        if(!(state.getBlock() instanceof LeverBlock lever)) { System.out.println("MMLF EXPERIMENT latency lever_missing"); return; }
        if(pullTick>=0) { timeouts++; MmlfLog.warn("experiment","latency_timeout",Map.of("tick",pullTick)); }
        lever.pull(state,level,LEVER,null);
        expected=level.getBlockState(LEVER).getValue(LeverBlock.POWERED);
        pullTick=server.getTickCount(); pullNanos=System.nanoTime(); notePull(pullTick);
    }

    /** Primary server thread, end of every server tick (ServerTickMixin). */
    static void serverTick(MinecraftServer server) {
        if(!Experiments.PROBE) return;
        if(BridgeCore.isPrimary()) {
            if(pullTick<0) return;
            ObjectState lamp=Boundary.remote("minecraft:overworld",LAMP);
            if(lamp==null || !String.valueOf(expected).equals(lamp.state().get("lit"))) return;
            roundTicks.add(server.getTickCount()-pullTick); roundMs.add((System.nanoTime()-pullNanos)/1e6);
            pullTick=-1;
        } else watchLamp(server);
    }

    /** Background server thread: reports the lockstep tick in which its lamp beside the primary's wire switched. */
    private static Boolean lampLit;
    private static void watchLamp(MinecraftServer server) {
        // Read from the chunk only if it is already loaded: a level read from the server thread adds a ticket that
        // would keep the scene from ever unloading.
        var chunk=server.overworld().getChunkSource().getChunkNow(LAMP.getX()>>4,LAMP.getZ()>>4);
        if(chunk==null) { lampLit=null; return; }
        var state=chunk.getBlockState(LAMP);
        if(!state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) return;
        boolean lit=state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT);
        if(lampLit!=null && lampLit!=lit) BridgeCore.send("event",BridgeCore.primaryId(),new Event("mmlf:experiment_lamp",Map.of("tick",Long.toString(TickSync.currentTick()),"lit",Boolean.toString(lit))));
        lampLit=lit;
    }

    private static final Map<String,String> heat=new HashMap<>();
    /** Both: the other side's part, on the network thread. */
    static void receive(Minecraft mc,String source,Event event) {
        var server=mc.getSingleplayerServer(); if(server==null) return;
        if(event.topic().equals("mmlf:experiment_lamp")) {
            // Primary: the background switched in lockstep tick N; the pull happened in primary tick N0.
            long tick=Long.parseLong(event.values().get("tick"));
            server.execute(()->{ if(tick>0 && pullTickForOneWay>=0) oneWayTicks.add(tick-pullTickForOneWay); });
            return;
        }
        if(event.topic().equals("mmlf:experiment_heat")) { heat.putAll(event.values()); return; }
        String action=event.values().get("action");
        if("place_pot".equals(action)) server.execute(()->{
            var key=ResourceLocation.parse("farmersdelight:cooking_pot");
            if(!BuiltInRegistries.BLOCK.containsKey(key)) return;
            server.overworld().setBlock(POT_ON_CAMPFIRE,BuiltInRegistries.BLOCK.get(key).defaultBlockState(),3);
        });
        if("heat".equals(action)) server.execute(()->{
            var level=server.overworld();
            var values=new HashMap<String,String>();
            values.put("pot_on_primary_campfire",heated(level,POT_ON_CAMPFIRE));
            values.put("pot_on_own_stove",heated(level,POT_ON_STOVE));
            values.put("below_campfire_seen_as",net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(level.getBlockState(CAMPFIRE)));
            BridgeCore.sendReliable("event",source,new Event("mmlf:experiment_heat",values));
        });
    }
    private static String heated(net.minecraft.server.level.ServerLevel level,BlockPos pos) {
        var entity=level.getBlockEntity(pos); if(entity==null) return "absent";
        try { return String.valueOf(entity.getClass().getMethod("isHeated").invoke(entity)); }
        catch(ReflectiveOperationException e) { return "no_method:"+entity.getClass().getSimpleName(); }
    }

    /** Primary tick of the pull that one-way reports refer to: the latest one. */
    private static volatile long pullTickForOneWay=-1;
    static void notePull(long tick) { pullTickForOneWay=tick; }

    private static void report() {
        System.out.println("MMLF EXPERIMENT latency pulls="+PULLS+" observed="+roundTicks.size()+" timeouts="+timeouts
            +" roundTripTicks="+histogram(roundTicks)+" roundTripMs="+summary(roundMs)+" oneWayLockstepTicks="+histogram(oneWayTicks));
        System.out.println("MMLF EXPERIMENT heat "+new TreeMap<>(heat));
        MmlfLog.info("experiment","latency",Map.of("roundTripTicks",roundTicks,"roundTripMs",roundMs,"oneWayLockstepTicks",oneWayTicks,"timeouts",timeouts));
        MmlfLog.info("experiment","heat",new TreeMap<String,Object>(heat));
    }
    private static String histogram(List<Long> values) {
        var counts=new TreeMap<Long,Integer>(); for(long v:values) counts.merge(v,1,Integer::sum); return counts.toString();
    }
    private static String summary(List<Double> values) {
        if(values.isEmpty()) return "{}";
        var sorted=new ArrayList<>(values); Collections.sort(sorted);
        return String.format(Locale.ROOT,"{min=%.1f,p50=%.1f,p95=%.1f,max=%.1f}",sorted.getFirst(),sorted.get(sorted.size()/2),sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*0.95)-1)),sorted.getLast());
    }
    private static String background() { return BridgeCore.primaryId().equals("fabric")?"neoforge":"fabric"; }
}
