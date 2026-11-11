package dev.mmlf.bridge;

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
    private static final long FREEZE_WAIT_NANOS=3_000_000_000L, DRAIN_WAIT_NANOS=1_000_000_000L, FINAL_WAIT_NANOS=10_000_000_000L, FROZEN_LIMIT_NANOS=15_000_000_000L;

    // ---- primary ----

    private static boolean saving;
    private static long epoch;
    private static Set<String> waitingFor=Set.of();
    private static final Set<String> frozen=ConcurrentHashMap.newKeySet(), committed=ConcurrentHashMap.newKeySet();
    private static volatile boolean drained;

    private static boolean active() {
        return BridgeCore.connected() && BridgeCore.session()!=null && WorldFollow.enabled(BridgeCore.session()) && SharedWorld.enabled();
    }

    /**
     * {@code ServerSaveMixin}, primary, before {@code saveEverything} and at the start of {@code stopServer} (both save
     * the players before the chunks): the freeze.
     */
    public static void primaryBefore(MinecraftServer server) {
        if(!active() || !BridgeCore.isPrimary() || saving || !server.isSameThread()) return;
        Set<String> remotes=Set.copyOf(BridgeCore.readyRemotes());
        if(remotes.isEmpty()) return;
        saving=true;
        epoch=readEpoch(server)+1;
        waitingFor=remotes; frozen.clear(); committed.clear();
        long started=System.nanoTime();
        for(String remote:remotes) BridgeCore.sendReliable("event",remote,new Event("mmlf:save_freeze",Map.of("epoch",Long.toString(epoch))));
        server.managedBlock(()->frozen.containsAll(remotes) || System.nanoTime()-started>FREEZE_WAIT_NANOS);
        // What the background sent before it froze may still be on its way through this runtime's client thread: a
        // marker behind it there, then here, runs after all of it. The client thread is not waited for while stopping.
        if(server.isRunning()) {
            drained=false;
            Minecraft.getInstance().execute(()->server.execute(()->drained=true));
            long drainStart=System.nanoTime();
            server.managedBlock(()->drained || System.nanoTime()-drainStart>DRAIN_WAIT_NANOS);
        }
        Set<String> missing=new TreeSet<>(remotes); missing.removeAll(frozen);
        MmlfLog.info("save","barrier_frozen",Map.of("epoch",epoch,"frozen",String.join(",",new TreeSet<>(frozen)),"missing",String.join(",",missing),"waitMs",(System.nanoTime()-started)/1_000_000));
    }

    /** {@code ServerSaveMixin}, primary, after {@code saveEverything} and after the chunks of {@code stopServer}: the commit. */
    public static void primaryAfter(MinecraftServer server,boolean flush) {
        if(!active() || !BridgeCore.isPrimary() || !saving) return;
        saving=false;
        Set<String> remotes=waitingFor; waitingFor=Set.of();
        if(remotes.isEmpty()) return;
        boolean last=!server.isRunning();
        for(String remote:remotes) BridgeCore.sendReliable("event",remote,new Event("mmlf:save_commit",Map.of("epoch",Long.toString(epoch),"flush",Boolean.toString(flush||last),"final",Boolean.toString(last))));
        // The last save waits for the background's, so it is on disk before this game goes on closing; others go on.
        if(last) {
            long started=System.nanoTime();
            server.managedBlock(()->committed.containsAll(remotes) || System.nanoTime()-started>FINAL_WAIT_NANOS);
            writeEpoch(server,epoch,remotes);
        } else pendingEpoch=new PendingEpoch(server,epoch,remotes,System.nanoTime());
    }
    private record PendingEpoch(MinecraftServer server,long epoch,Set<String> remotes,long since) {}
    private static volatile PendingEpoch pendingEpoch;

    /** Network thread, primary: a background froze or saved. */
    static void primaryReceive(String source,String topic,Map<String,String> values) {
        long at; try { at=Long.parseLong(values.get("epoch")); } catch(RuntimeException e) { return; }
        if(topic.equals("mmlf:save_frozen")) { if(at==epoch) frozen.add(source); return; }
        if(topic.equals("mmlf:save_done")) {
            committed.add(source);
            PendingEpoch pending=pendingEpoch;
            if(pending!=null && pending.epoch()==at && committed.containsAll(pending.remotes())) { pendingEpoch=null; writeEpoch(pending.server(),at,pending.remotes()); }
        }
    }

    private static Path epochFile(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("mmlf").resolve("epoch.json"); }
    static long readEpoch(MinecraftServer server) {
        try {
            String text=Files.readString(epochFile(server),StandardCharsets.UTF_8);
            var json=com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            return json.get("epoch").getAsLong();
        } catch(Exception e) { return 0; }
    }
    private static void writeEpoch(MinecraftServer server,long epoch,Set<String> remotes) {
        Set<String> inStep=new TreeSet<>(committed); inStep.retainAll(remotes);
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
        } catch(Exception e) { MmlfLog.warn("save","epoch_write_failed",Map.of("exception",e.toString())); }
        if(behind.isEmpty()) MmlfLog.info("save","save_point",Map.of("epoch",epoch,"runtimes",String.join(",",inStep)));
        else MmlfLog.warn("save","save_point_behind",Map.of("epoch",epoch,"behind",String.join(",",behind)));
    }
    /** Network thread, primary: a background opened its world at save point {@code theirs}; reported if not in step. */
    static void checkOpened(String runtime,String theirs) {
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

    private static volatile boolean backgroundFrozen, finalSaved, committing;
    private static volatile long frozenSince;
    private static final List<Runnable> deferred=new ArrayList<>();

    /** Background: its server is held still for a save of the primary (it reads as paused). */
    public static boolean frozen() {
        if(!backgroundFrozen) return false;
        if(!finalSaved && System.nanoTime()-frozenSince>FROZEN_LIMIT_NANOS) {
            MmlfLog.warn("save","freeze_expired",Map.of());
            unfreeze();
            return false;
        }
        return true;
    }
    /**
     * {@code ServerSaveMixin}, background: its own saves (autosave, the save on pausing) are left out; only the
     * primary's commit and the closing save run. Closing normally follows the primary's final save while frozen, so it
     * writes the same state again.
     */
    public static boolean backgroundSkips(MinecraftServer server) {
        return active() && !BridgeCore.isPrimary() && !committing && server.isRunning();
    }
    /** Background, client thread: an interaction waits while frozen, so it happens after this save, as on the primary. */
    static boolean deferWhileFrozen(Runnable action) {
        if(!frozen()) return false;
        synchronized(deferred) { deferred.add(action); }
        return true;
    }
    private static void unfreeze() {
        backgroundFrozen=false;
        List<Runnable> waiting;
        synchronized(deferred) { waiting=new ArrayList<>(deferred); deferred.clear(); }
        if(!waiting.isEmpty()) Minecraft.getInstance().execute(()->waiting.forEach(Runnable::run));
    }

    /** Network thread, background: the primary's barrier messages, handled after everything it sent before them. */
    static void backgroundReceive(String topic,Map<String,String> values) {
        var mc=Minecraft.getInstance();
        mc.execute(()->{
            var server=mc.getSingleplayerServer();
            if(server==null) return;
            String at=values.get("epoch");
            if(topic.equals("mmlf:save_freeze")) server.execute(()->{
                backgroundFrozen=true; frozenSince=System.nanoTime();
                BridgeCore.sendReliable("event",BridgeCore.primaryId(),new Event("mmlf:save_frozen",Map.of("epoch",String.valueOf(at))));
            });
            else if(topic.equals("mmlf:save_commit")) server.execute(()->{
                boolean flush=Boolean.parseBoolean(values.get("flush")), last=Boolean.parseBoolean(values.get("final"));
                committing=true;
                try { server.saveEverything(true,flush,true); }
                finally { committing=false; }
                OwnedVanillaStore.save(server);
                CoGeneration.save(server);
                writeOwnEpoch(server,at);
                MmlfLog.info("save","background_saved",Map.of("epoch",String.valueOf(at),"final",last));
                if(last) finalSaved=true; else unfreeze();
                BridgeCore.sendReliable("event",BridgeCore.primaryId(),new Event("mmlf:save_done",Map.of("epoch",String.valueOf(at))));
            });
        });
    }
    /** Background: its world was closed; the next one starts unfrozen. */
    static void worldClosed() { finalSaved=false; unfreeze(); }

    private static void writeOwnEpoch(MinecraftServer server,String at) {
        try { Files.writeString(server.getWorldPath(LevelResource.ROOT).resolve("mmlf-epoch"),String.valueOf(at),StandardCharsets.UTF_8); }
        catch(Exception e) { MmlfLog.warn("save","epoch_write_failed",Map.of("exception",e.toString())); }
    }
    /** Background: the save point its open world is at, or "" when unknown. */
    static String ownEpoch() {
        var server=Minecraft.getInstance().getSingleplayerServer(); if(server==null) return "";
        try { return Files.readString(server.getWorldPath(LevelResource.ROOT).resolve("mmlf-epoch"),StandardCharsets.UTF_8).trim(); }
        catch(Exception e) { return ""; }
    }
}
