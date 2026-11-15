package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.WorldFollow;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.common.Messages.Event;
import dev.mmlf.common.MmlfLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * One save for both runtimes. A background following the primary's saves keeps its world inside the primary's save
 * ({@code <save>/mmlf/<runtime>}, {@link WorldFollow}) and never saves on its own: it saves only when the primary does,
 * so after a crash both worlds are back at the same moment.
 * <p>
 * Each save of the primary is a clean cut across both runtimes, made with a barrier over their ordered link:
 * <ol>
 * <li>Freeze: the primary, about to save, sends {@code mmlf:save_freeze}. The background handles it on its server
 * thread after everything the primary sent before, stops ticking (as when paused) and answers. Everything it sent
 * before the answer is applied here before this save, by running this server's queued tasks while waiting.</li>
 * <li>The primary saves.</li>
 * <li>Commit: it sends {@code mmlf:save_commit}. The background, still frozen, applies what the primary sent before it,
 * saves, records the save point and goes on ticking (except after the final save, when it stays frozen until it
 * closes). Interactions arriving while frozen wait.</li>
 * </ol>
 * Each completed save point is numbered in {@code <save>/mmlf/epoch.json} and in the background's world; a background
 * that did not answer in time is recorded as not in step, and a world opened at different save points is reported.
 * Chunk files are still written asynchronously after a save, as in vanilla: a crash during that write can leave
 * seconds between them, except for the final save, which is flushed.
 */
public final class SaveSync {
    private SaveSync() {}
    /** This world's part of SaveSync: made when the world first needs it, gone when it closes ({@link Worlds}). */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        volatile SaveRound round;
        volatile Freeze freeze;
        volatile boolean finalSaved;
        volatile boolean committing;
        final List<Runnable> deferred=new ArrayList<>();
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("save_sync",State::new);
    /** The open world's state; with none open, a throwaway one: what is written to it reaches no world. */
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }

    private static final long FREEZE_WAIT_NANOS=3_000_000_000L, DRAIN_WAIT_NANOS=1_000_000_000L, FINAL_WAIT_NANOS=10_000_000_000L, FROZEN_LIMIT_NANOS=15_000_000_000L;

    // ---- primary ----

    private static final class SaveRound {
        final MinecraftServer server;
        final long epoch;
        final String id=UUID.randomUUID().toString(),world;
        final Set<String> remotes;
        final Set<String> frozen=ConcurrentHashMap.newKeySet();
        final Map<String,Boolean> replies=new ConcurrentHashMap<>();
        volatile Set<String> committingTo;
        long commitStarted;

        SaveRound(MinecraftServer server,long epoch,Set<String> remotes) {
            this.server=server; this.epoch=epoch; this.remotes=remotes; this.world=WorldFollow.saveScope(server);
        }
        boolean answered() { return replies.keySet().containsAll(committingTo); }
        Map<String,String> message() { return Map.of("round",id,"epoch",Long.toString(epoch),"world",world); }
    }
    private static MinecraftServer epochServer;
    private static long lastEpoch;

    static boolean transferFrozen() {
        SaveRound current=state().round;
        return current!=null && current.committingTo==null || frozen();
    }

    private static boolean active() {
        return SessionLink.connected() && SessionLink.session()!=null && Worlds.followsSave() && SharedWorld.enabled();
    }

    /** Primary server thread, before saving either the players or the chunks. */
    public static void primaryBefore(MinecraftServer server) {
        if(!active() || !SessionLink.isPrimary() || !server.isSameThread()) return;
        SaveRound previous=state().round;
        if(previous!=null && previous.server==server && previous.committingTo==null) return;
        if(previous!=null) finish(previous);
        Set<String> remotes=SessionLink.peers().worldReadyRuntimes();
        if(epochServer!=server) {
            epochServer=server;
            lastEpoch=readEpoch(server);
        }
        SaveRound current=new SaveRound(server,++lastEpoch,remotes);
        state().round=current;
        long started=System.nanoTime();
        for(String remote:remotes) SessionLink.sendReliable("event",remote,new Event("mmlf:save_freeze",current.message()));
        server.managedBlock(()->current.frozen.containsAll(remotes) || System.nanoTime()-started>=FREEZE_WAIT_NANOS);
        if(server.isRunning()) {
            var drained=new java.util.concurrent.atomic.AtomicBoolean();
            Minecraft.getInstance().execute(()->server.execute(()->drained.set(true)));
            long drainStart=System.nanoTime();
            server.managedBlock(()->drained.get() || System.nanoTime()-drainStart>=DRAIN_WAIT_NANOS);
        }
        Set<String> missing=new TreeSet<>(remotes);
        missing.removeAll(current.frozen);
        MmlfLog.info("save","barrier_frozen",Map.of("epoch",current.epoch,"frozen",String.join(",",new TreeSet<>(current.frozen)),
            "missing",String.join(",",missing),"waitMs",(System.nanoTime()-started)/1_000_000));
    }

    /** Primary server thread, after the local save. Only peers that reached the barrier can commit it. */
    public static void primaryAfter(MinecraftServer server,boolean flush,boolean metadataSaved) {
        SaveRound current=state().round;
        if(current==null || current.server!=server || current.committingTo!=null) return;
        boolean last=!server.isRunning();
        current.commitStarted=System.nanoTime();
        current.committingTo=metadataSaved ? Set.copyOf(current.frozen) : Set.of();
        Map<String,String> commit=new HashMap<>(current.message());
        commit.put("flush",Boolean.toString(flush||last));
        commit.put("final",Boolean.toString(last));
        for(String remote:current.remotes) {
            boolean frozen=current.committingTo.contains(remote);
            SessionLink.sendReliable("event",remote,new Event(frozen?"mmlf:save_commit":"mmlf:save_abort",frozen?commit:current.message()));
        }
        if(last) {
            server.managedBlock(()->current.answered() || System.nanoTime()-current.commitStarted>=FINAL_WAIT_NANOS);
            finish(current);
        } else primaryTick(server);
    }

    static void primaryTick(MinecraftServer server) {
        SaveRound current=state().round;
        if(current!=null && current.server==server && current.committingTo!=null &&
            (current.answered() || System.nanoTime()-current.commitStarted>=FINAL_WAIT_NANOS)) finish(current);
    }

    private static void finish(SaveRound current) {
        state().round=null;
        Set<String> confirmed=new HashSet<>();
        current.replies.forEach((remote,saved)->{ if(saved) confirmed.add(remote); });
        writeEpoch(current.server,current.epoch,current.remotes,confirmed);
    }

    /** Network thread: only the matching round and participating peer can change this barrier. */
    public static void primaryReceive(String source,String topic,Map<String,String> values) {
        SaveRound current=state().round;
        if(current==null || !current.id.equals(values.get("round")) || !Long.toString(current.epoch).equals(values.get("epoch"))
            || !current.remotes.contains(source)) return;
        Set<String> committing=current.committingTo;
        if(topic.equals("mmlf:save_frozen") && committing==null) current.frozen.add(source);
        if(topic.equals("mmlf:save_done") && committing!=null && committing.contains(source))
            current.replies.putIfAbsent(source,"true".equals(values.get("saved")));
    }

    private static Path epochFile(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("mmlf").resolve("epoch.json"); }
    static long readEpoch(MinecraftServer server) {
        try {
            String text=Files.readString(epochFile(server),StandardCharsets.UTF_8);
            var json=com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            return json.get("epoch").getAsLong();
        } catch(Exception e) { return 0; }
    }
    private static void writeEpoch(MinecraftServer server,long epoch,Set<String> remotes,Set<String> confirmed) {
        Set<String> inStep=new TreeSet<>(confirmed); inStep.retainAll(remotes);
        Set<String> behind=new TreeSet<>(remotes); behind.removeAll(inStep);
        var json=new com.google.gson.JsonObject();
        json.addProperty("epoch",epoch);
        var a=new com.google.gson.JsonArray(); inStep.forEach(a::add); json.add("inStep",a);
        var b=new com.google.gson.JsonArray(); behind.forEach(b::add); json.add("behind",b);
        try {
            Path file=epochFile(server); Files.createDirectories(file.getParent());
            Path temporary=file.resolveSibling("epoch.json.tmp");
            Files.writeString(temporary,json.toString(),StandardCharsets.UTF_8);
            Files.move(temporary,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch(Exception e) {
            MmlfLog.warn("save","epoch_write_failed",Map.of("exception",e.toString()));
            return;
        }
        if(behind.isEmpty()) MmlfLog.info("save","save_point",Map.of("epoch",epoch,"runtimes",String.join(",",inStep)));
        else MmlfLog.warn("save","save_point_behind",Map.of("epoch",epoch,"behind",String.join(",",behind)));
    }
    /** Network thread, primary: a background opened its world at save point {@code theirs}; reported if not in step. */
    public static void checkOpened(String runtime,String theirs) {
        var server=Minecraft.getInstance().getSingleplayerServer();
        if(server==null || theirs==null || theirs.isBlank() || !active()) return;
        long ours=readEpoch(server), other;
        try { other=Long.parseLong(theirs); } catch(NumberFormatException e) { return; }
        if(ours==0 || ours==other) return;
        MmlfLog.warn("save","save_point_mismatch",Map.of("runtime",runtime,"primary",ours,"background",other));
        Minecraft.getInstance().execute(()->{
            var mc=Minecraft.getInstance();
            if(mc.gui!=null) mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
                "MMLF：远端 "+runtime+" 的存档停在保存点 "+other+"，主端在 "+ours+"。两端上次没有一起保存（可能在保存时崩溃），跨端物品可能不一致。"));
        });
    }

    // ---- background ----

    private record Freeze(MinecraftServer server,String id,String epoch,long deadline) {}

    /** Background: its server is held still for a save of the primary (it reads as paused). */
    public static boolean frozen() {
        Freeze current=state().freeze;
        if(current==null) return false;
        if(!state().finalSaved && !state().committing && System.nanoTime()-current.deadline()>=0) {
            MmlfLog.warn("save","freeze_expired",Map.of());
            unfreeze(current);
            return state().freeze!=null;
        }
        return true;
    }
    /**
     * {@code ServerSaveMixin}, background: its own saves (autosave, the save on pausing) are left out; only the
     * primary's commit and the closing save run. Closing normally follows the primary's final save while frozen, so it
     * writes the same state again.
     */
    public static boolean backgroundSkips(MinecraftServer server) {
        return active() && !SessionLink.isPrimary() && !state().committing && server.isRunning();
    }
    /** Background, client thread: an interaction waits while frozen, so it happens after this save, as on the primary. */
    static boolean deferWhileFrozen(Runnable action) {
        synchronized(state().deferred) {
            if(!frozen()) return false;
            state().deferred.add(action);
            return true;
        }
    }
    static boolean deliverWhileUnfrozen(Runnable delivery) {
        synchronized(state().deferred) {
            if(frozen()) return false;
            delivery.run();
            return true;
        }
    }

    private static void unfreeze(Freeze expected) {
        List<Runnable> waiting;
        synchronized(state().deferred) {
            if(state().freeze!=expected) return;
            state().freeze=null;
            waiting=new ArrayList<>(state().deferred);
            state().deferred.clear();
        }
        if(!waiting.isEmpty()) Minecraft.getInstance().execute(()->waiting.forEach(Runnable::run));
    }

    /** Network thread, background: the primary's barrier messages, handled after everything it sent before them. */
    public static void backgroundReceive(String topic,Map<String,String> values) {
        var mc=Minecraft.getInstance();
        mc.execute(()->{
            var server=mc.getSingleplayerServer();
            if(server==null) return;
            String id=values.get("round"), at=values.get("epoch");
            if(id==null || at==null) return;
            server.execute(()->{
                if(mc.getSingleplayerServer()!=server) return;
                String scope=WorldFollow.saveScope(server);
                if(!scope.equals(values.get("world"))) {
                    // Not this world's save. Said aloud: a wrong scope would otherwise leave every save unconfirmed in silence.
                    MmlfLog.sampled("save","barrier_other_world",5000,Map.of("topic",topic,"own",scope,"primary",String.valueOf(values.get("world"))));
                    return;
                }
                if(topic.equals("mmlf:save_freeze")) {
                    if(state().finalSaved) return;
                    Freeze request=new Freeze(server,id,at,System.nanoTime()+FROZEN_LIMIT_NANOS);
                    synchronized(state().deferred) { state().freeze=request; }
                    if(!ContainerBridge.prepareSave(server)) { unfreeze(request); return; }
                    reply("mmlf:save_frozen",values,false);
                    return;
                }
                Freeze request=state().freeze;
                if(request==null || request.server()!=server || !id.equals(request.id()) || !at.equals(request.epoch()) || !frozen()) return;
                if(topic.equals("mmlf:save_abort")) {
                    unfreeze(request);
                    return;
                }
                boolean flush=Boolean.parseBoolean(values.get("flush")), last=Boolean.parseBoolean(values.get("final"));
                boolean saved=false;
                state().committing=true;
                try {
                    server.saveEverything(true,flush,true);
                    saved=OwnedVanillaStore.save(server) & CoGeneration.save(server) & ContainerBridge.save(server);
                    if(saved) saved=writeOwnEpoch(server,at);
                } catch(RuntimeException e) {
                    MmlfLog.warn("save","background_save_failed",Map.of("epoch",at,"exception",e.toString()));
                } finally {
                    state().committing=false;
                    if(last && saved) state().finalSaved=true; else unfreeze(request);
                }
                MmlfLog.info("save","background_save_result",Map.of("epoch",at,"final",last,"saved",saved));
                reply("mmlf:save_done",values,saved);
            });
        });
    }

    private static void reply(String topic,Map<String,String> values,boolean saved) {
        SessionLink.sendReliable("event",SessionLink.primaryId(),new Event(topic,Map.of(
            "round",values.get("round"),"epoch",values.get("epoch"),"saved",Boolean.toString(saved))));
    }

    /**
     * Background, about to leave its world (closing it, or the game stopping). A freeze kept after the final save makes
     * the server read as paused ({@code MinecraftMixin}), and a paused integrated server handles no disconnect: leaving
     * would wait forever for it to stop. Released here; the final save is written, and the closing save writes it again.
     */
    public static void leaving() { if(state().freeze!=null || state().finalSaved) MmlfLog.info("save","freeze_released_for_leave",Map.of("finalSaved",state().finalSaved)); worldClosed(); }
    /** Background: its world was closed; queued interactions for that world expire with it. */
    public static void worldClosed() {
        synchronized(state().deferred) { state().finalSaved=false; state().freeze=null; state().deferred.clear(); }
    }

    private static boolean writeOwnEpoch(MinecraftServer server,String at) {
        try {
            Path file=server.getWorldPath(LevelResource.ROOT).resolve("mmlf-epoch");
            Path temporary=file.resolveSibling("mmlf-epoch.tmp");
            Files.writeString(temporary,at,StandardCharsets.UTF_8);
            Files.move(temporary,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch(Exception e) {
            MmlfLog.warn("save","epoch_write_failed",Map.of("exception",e.toString()));
            return false;
        }
    }
    /** Background: the save point its open world is at, or "" when unknown. */
    public static String ownEpoch() {
        var server=Minecraft.getInstance().getSingleplayerServer(); if(server==null) return "";
        try { return Files.readString(server.getWorldPath(LevelResource.ROOT).resolve("mmlf-epoch"),StandardCharsets.UTF_8).trim(); }
        catch(Exception e) { return ""; }
    }
}
