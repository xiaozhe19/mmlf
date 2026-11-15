package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.platform.Experiments;
import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Messages.Event;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/**
 * Experiment {@code follow} (split-runtime manual §5.1/§5.4, steps 0 and 6): a background loads and ticks the chunks
 * holding its objects exactly as far as the primary loads and ticks the same chunks, instead of around its own player.
 * <ul>
 * <li>A background publishes its interest: the chunks it has objects in ({@code mmlf:interest}).</li>
 * <li>The primary answers, at the end of each server tick, how far each of those chunks is loaded in its own world:
 * 0 not loaded, 1 loaded, 2 block ticking, 3 entity ticking ({@code mmlf:chunks}).</li>
 * <li>The background holds one ticket per such chunk at that level; its own players hold none
 * ({@code ChunkMapMixin}). A chunk the primary does not have goes dormant at once: its objects leave the boundary
 * ({@link Boundary#dormant}) without waiting for the chunk to finish unloading here.</li>
 * </ul>
 * Server thread only, except {@link #receive}, which hands over to it.
 */
public final class ChunkFollow {
    private ChunkFollow() {}
    /** This world's part of ChunkFollow: made when the world first needs it, gone when it closes ({@link Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        final Set<String> interest=java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<String> interestSent=new HashSet<>();
        final Map<String,Integer> followed=new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String,Set<String>> interests=new java.util.concurrent.ConcurrentHashMap<>();
        final Map<String,Map<String,Integer>> sent=new HashMap<>();
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("chunk_follow",State::new);
    /** The open world's state; with none open, a throwaway one: what is written to it reaches no world. */
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }

    private static final TicketType<ChunkPos> FOLLOW=TicketType.create("mmlf_follow",Comparator.comparingLong(ChunkPos::toLong));
    /** Background: chunks with objects of its own ("dimension@x,z"), and what the primary last said about them. */
    /** Primary: each background's interest, and the state last sent to it. Concurrent only for {@link #metrics}. */
    private static long ticketChanges,dormantChunks,stateMessages;

    public static boolean enabled() { return Experiments.FOLLOW; }
    /** {@code ChunkMapMixin}: whether a player of this runtime's world may hold chunk tickets. */
    public static boolean playersLoadChunks() { return !enabled() || SessionLink.isPrimary(); }

    static String key(String dimension,int x,int z) { return dimension+"@"+x+","+z; }
    static String key(String dimension,BlockPos pos) { return key(dimension,pos.getX()>>4,pos.getZ()>>4); }

    /** Background, server thread: a chunk holds objects of this runtime (or no longer does). */
    static void own(String dimension,int x,int z,boolean has) {
        SharedWorld.own(dimension,x,z,has);
        if(!enabled() || SessionLink.isPrimary()) return;
        if(has) state().interest.add(key(dimension,x,z)); else state().interest.remove(key(dimension,x,z));
    }
    /** Whether the primary has said this chunk is not loaded in its world. Unknown chunks count as followed. */
    static boolean dormant(String dimension,int x,int z) {
        if(!enabled() || SessionLink.isPrimary()) return false;
        Integer level=state().followed.get(key(dimension,x,z));
        return level!=null && level==0;
    }

    /** Server thread, at the end of each server tick. */
    static void tick(MinecraftServer server) {
        if(!enabled()) return;
        if(SessionLink.isPrimary()) { answer(server); return; }
        if(state().interest.equals(state().interestSent) || !SessionLink.connected()) return;
        Set<String> add=new TreeSet<>(state().interest); add.removeAll(state().interestSent);
        Set<String> remove=new TreeSet<>(state().interestSent); remove.removeAll(state().interest);
        state().interestSent.clear(); state().interestSent.addAll(state().interest);
        SessionLink.sendReliable("event",SessionLink.primaryId(),new Event("mmlf:interest",Map.of("add",String.join(";",add),"remove",String.join(";",remove))));
        MmlfLog.info("follow","interest",Map.of("add",add.size(),"remove",remove.size(),"total",state().interest.size()));
    }
    /** Primary: how far each chunk a background asked about is loaded here; changes go out once per tick. */
    private static void answer(MinecraftServer server) {
        for(var entry:state().interests.entrySet()) {
            String runtime=entry.getKey(); var last=state().sent.computeIfAbsent(runtime,r->new HashMap<>());
            List<String> changes=new ArrayList<>();
            for(String chunk:entry.getValue()) {
                int state=state(server,chunk);
                Integer before=last.put(chunk,state);
                if(before==null || before!=state) changes.add(chunk+"="+state);
            }
            last.keySet().retainAll(entry.getValue());
            if(changes.isEmpty()) continue;
            stateMessages++;
            SessionLink.sendReliable("event",runtime,new Event("mmlf:chunks",Map.of("states",String.join(";",changes),"tick",Integer.toString(server.getTickCount()))));
            MmlfLog.debug("follow","states",Map.of("runtime",runtime,"changes",changes.size(),"first",changes.get(0)));
        }
    }
    private static int state(MinecraftServer server,String chunk) {
        int at=chunk.indexOf('@'),comma=chunk.indexOf(',',at);
        ServerLevel level=RedstoneBridge.level(server,chunk.substring(0,at)); if(level==null) return 0;
        int x=Integer.parseInt(chunk.substring(at+1,comma)),z=Integer.parseInt(chunk.substring(comma+1));
        if(!level.getChunkSource().hasChunk(x,z)) return 0;
        if(level.isPositionEntityTicking(new BlockPos(x<<4,0,z<<4))) return 3;
        return level.shouldTickBlocksAt(ChunkPos.asLong(x,z)) ? 2 : 1;
    }

    /** Network thread. */
    public static void receive(String source,Event event) {
        if(!enabled()) return;
        // A background's world is usually ready before the primary's server exists, so its interest is kept right away.
        if(event.topic().equals("mmlf:interest") && SessionLink.isPrimary()) {
            var set=state().interests.computeIfAbsent(source,s->java.util.concurrent.ConcurrentHashMap.newKeySet());
            for(String chunk:split(event.values().get("add"))) set.add(chunk);
            for(String chunk:split(event.values().get("remove"))) set.remove(chunk);
            MmlfLog.info("follow","interest_received",Map.of("runtime",source,"total",set.size()));
        }
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if(server==null) return;
        if(event.topic().equals("mmlf:chunks") && !SessionLink.isPrimary() && source.equals(SessionLink.primaryId())) server.execute(()->{
            for(String change:split(event.values().get("states"))) {
                int eq=change.lastIndexOf('='); apply(server,change.substring(0,eq),Integer.parseInt(change.substring(eq+1)));
            }
        });
    }
    /** Background: holds a ticket for the chunk at the primary's level (distance 0: loaded, 1: block, 2: entity ticking). */
    private static void apply(MinecraftServer server,String chunk,int state) {
        int at=chunk.indexOf('@'),comma=chunk.indexOf(',',at);
        String dimension=chunk.substring(0,at);
        ServerLevel level=RedstoneBridge.level(server,dimension); if(level==null) return;
        int x=Integer.parseInt(chunk.substring(at+1,comma)),z=Integer.parseInt(chunk.substring(comma+1));
        ChunkPos pos=new ChunkPos(x,z);
        Integer before=state().followed.put(chunk,state);
        if(before!=null && before==state) return;
        if(before!=null && before>0) level.getChunkSource().removeRegionTicket(FOLLOW,pos,before-1,pos);
        if(state>0) level.getChunkSource().addRegionTicket(FOLLOW,pos,state-1,pos);
        ticketChanges++;
        if(state==0) { dormantChunks++; Boundary.dormant(level,x,z); }
        else if(before!=null && before==0) Boundary.awaken(level,x,z);
        MmlfLog.debug("follow","ticket",Map.of("chunk",chunk,"from",before==null?-1:before,"to",state));
    }
    private static List<String> split(String value) { return value==null || value.isEmpty() ? List.of() : List.of(value.split(";")); }

    /** A peer joined again or left: what it knew is gone, so the next tick sends everything anew. */
    static void reset() {
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{ state().interestSent.clear(); state().sent.clear(); });
    }
    static void forget(String runtime) {
        var server=net.minecraft.client.Minecraft.getInstance().getSingleplayerServer(); if(server==null) return;
        server.execute(()->{ state().interests.remove(runtime); state().sent.remove(runtime); });
    }
    public static Map<String,Object> metrics() {
        long held=state().followed.values().stream().filter(v->v>0).count();
        int asked=state().interests.values().stream().mapToInt(Set::size).sum();
        return Map.of("followInterest",state().interest.size()+asked,"followTickets",held,"followTicketChanges",ticketChanges,"followDormantChunks",dormantChunks,"followStateMessages",stateMessages);
    }
}
