package dev.mmlf.bridge;

import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.MmlfLog;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * Keeps world time and weather the same in every runtime of a session, whose integrated servers otherwise run their
 * own clocks: a block lit by one runtime and drawn by the other must see the same day and the same rain.
 * <ul>
 * <li>An explicit change in any runtime (a command, sleeping through the night: anything but the per-tick advance)
 * is sent to the others at once ({@code ServerLevelClockMixin}).</li>
 * <li>The primary also announces its time and weather once a second; the others adopt it when they drift, which
 * covers their own random weather cycle and tick-rate differences.</li>
 * </ul>
 */
public final class WorldClock {
    private WorldClock() {}
    private static final long DRIFT_TICKS=20;
    /** Server thread: true while a received value is applied, so it is not sent back. */
    private static boolean applying;
    private static long lastAnnounce;

    private static boolean overworld(ServerLevel level) { return level.dimension()==Level.OVERWORLD; }
    private static void send(String topic,Map<String,String> values) {
        Minecraft.getInstance().execute(()->{ if(BridgeCore.connected()) BridgeCore.send("event","*",new Event(topic,values)); });
    }

    /** Server thread, before {@code ServerLevel.setDayTime}. */
    public static void dayTimeSet(ServerLevel level,long time) {
        if(applying || !overworld(level) || !BridgeCore.connected()) return;
        if(time==level.getDayTime()+1) return; // the normal daylight cycle
        send("mmlf:world_time",Map.of("dayTime",Long.toString(time)));
        MmlfLog.info("world.clock","time_set_local",Map.of("dayTime",time));
    }
    /** Server thread, before {@code ServerLevel.setWeatherParameters} (commands only; the natural cycle sets fields directly). */
    public static void weatherSet(ServerLevel level,int clear,int weather,boolean raining,boolean thundering) {
        if(applying || !overworld(level) || !BridgeCore.connected()) return;
        send("mmlf:world_weather",Map.of("clear",Integer.toString(clear),"weather",Integer.toString(weather),"raining",Boolean.toString(raining),"thundering",Boolean.toString(thundering)));
        MmlfLog.info("world.clock","weather_set_local",Map.of("raining",raining,"thundering",thundering));
    }

    /** Client tick: the primary announces its clock once a second. */
    static void tick(Minecraft mc) {
        if(!BridgeCore.isPrimary() || !BridgeCore.connected()) return;
        MinecraftServer server=mc.getSingleplayerServer(); if(server==null) return;
        long now=System.nanoTime(); if(now-lastAnnounce<1_000_000_000L) return; lastAnnounce=now;
        server.execute(()->{
            ServerLevel level=server.overworld(); if(level==null) return;
            var data=(ServerLevelData)level.getLevelData();
            send("mmlf:world_clock",Map.of("dayTime",Long.toString(level.getDayTime()),"raining",Boolean.toString(data.isRaining()),"thundering",Boolean.toString(data.isThundering()),
                "clear",Integer.toString(data.getClearWeatherTime()),"weather",Integer.toString(data.getRainTime())));
        });
    }

    static void receive(String source,String topic,Map<String,String> values) {
        if(values==null || source.equals(BridgeCore.runtimeId())) return;
        boolean periodic=topic.equals("mmlf:world_clock");
        // Only the primary's announcements are authoritative; explicit changes come from anyone.
        if(periodic && (BridgeCore.isPrimary() || !source.equals(BridgeCore.primaryId()))) return;
        MinecraftServer server=Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{
            ServerLevel overworld=server.overworld(); if(overworld==null) return;
            applying=true;
            try {
                if(values.containsKey("dayTime")) {
                    long time=Long.parseLong(values.get("dayTime"));
                    if(!periodic || Math.abs(overworld.getDayTime()-time)>DRIFT_TICKS) {
                        for(ServerLevel level:server.getAllLevels()) level.setDayTime(time);
                        if(!periodic) MmlfLog.info("world.clock","time_set_remote",Map.of("source",source,"dayTime",time));
                    }
                }
                if(values.containsKey("raining")) {
                    boolean raining=Boolean.parseBoolean(values.get("raining")),thundering=Boolean.parseBoolean(values.get("thundering"));
                    var data=(ServerLevelData)overworld.getLevelData();
                    if(!periodic || data.isRaining()!=raining || data.isThundering()!=thundering) {
                        overworld.setWeatherParameters(Integer.parseInt(values.get("clear")),Integer.parseInt(values.get("weather")),raining,thundering);
                        MmlfLog.info("world.clock","weather_set_remote",Map.of("source",source,"raining",raining,"thundering",thundering,"periodic",periodic));
                    }
                }
            } catch(RuntimeException e) { MmlfLog.sampled("world.clock","apply_failed",5000,Map.of("exception",e.toString())); }
            finally { applying=false; }
        });
    }
}
