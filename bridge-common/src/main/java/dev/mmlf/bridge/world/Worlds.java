package dev.mmlf.bridge.world;

import dev.mmlf.bridge.transport.SessionLink;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Session;
import dev.mmlf.common.WorldSession;
import dev.mmlf.common.WorldSession.Mode;
import dev.mmlf.common.WorldSessions;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.Files;
import java.util.Map;

/**
 * World layer: this runtime's {@link WorldSession}, bound to its integrated server.
 * <ul>
 * <li>Primary: a world opens when its integrated server exists ({@link #primaryServer}, from any thread, also from a
 * generation worker before the client ticks), in the mode the session gives then ({@link #nextPrimaryMode}); it is
 * leaving from {@code Minecraft.disconnect} and closed once the server is gone, after its final save.</li>
 * <li>Background: a world opens when the primary announces it ({@code mmlf:world}, {@link WorldFollow}) with the
 * primary's mode and epoch; it is leaving once the primary starts leaving and closed when this runtime's world is.</li>
 * </ul>
 */
public final class Worlds {
    private Worlds() {}
    private static final WorldSessions sessions=new WorldSessions();
    /** Primary: the epoch of the last world it opened in this run. */
    private static long lastEpoch;
    static final String SCENE_FILE="scenario.json";

    // ---- hooks: the scene layer and the entry register these; this layer never names them ----
    private static volatile java.util.function.Predicate<Minecraft> sceneShown=mc->true;
    private static volatile java.util.function.Consumer<Minecraft> sceneWorld=mc->{};
    /** Whether a scene world shows its placed scene, and how this runtime creates a scene world of its own. */
    public static void sceneHooks(java.util.function.Predicate<Minecraft> shown,java.util.function.Consumer<Minecraft> create) { sceneShown=shown; sceneWorld=create; }
    /** Client thread: the open world is in its final shape (a scene placed, or no scene at all). */
    public static boolean sceneShown(Minecraft mc) { return sceneShown.test(mc); }
    static void createSceneWorld(Minecraft mc) { sceneWorld.accept(mc); }

    // ---- reporting this runtime's world loaded (mmlf:world_ready): once per world, and again after the link was lost ----
    private static final class ReadyReport implements WorldSession.Scoped { boolean sent; }
    private static final WorldSession.Key<ReadyReport> READY=new WorldSession.Key<>("ready_report",ReadyReport::new);
    /** True once per world: the caller sends the report now. */
    public static synchronized boolean claimReadyReport() {
        WorldSession w=current(); if(w==null) return false;
        ReadyReport r=w.get(READY); if(r.sent) return false;
        r.sent=true; return true;
    }
    /** The link was lost: the others forgot this runtime's world, so it is reported again. */
    public static synchronized void readyReportLost() { WorldSession w=current(); if(w!=null) w.get(READY).sent=false; }

    public static WorldSessions sessions() { return sessions; }
    /** The open world, or null; read once and keep it. */
    public static WorldSession current() { return sessions.current(); }

    /** The mode a world of the primary opens in now: a scene while the session has one, else the player's saves. */
    public static Mode nextPrimaryMode() {
        Session session=SessionLink.session();
        return session!=null && Files.isRegularFile(session.root().resolve(SCENE_FILE)) ? Mode.SCENE : Mode.FOLLOWED_SAVE;
    }
    /** The open world is a scene. */
    public static boolean scene() { WorldSession w=current(); return w!=null && w.mode()==Mode.SCENE; }
    /**
     * Whether the backgrounds follow the primary's save: the open world's mode, or, with none open (a world still being
     * created), what the next one would be.
     */
    public static boolean followsSave() {
        WorldSession w=current();
        if(w!=null) return w.mode()==Mode.FOLLOWED_SAVE;
        return SessionLink.session()!=null && nextPrimaryMode()==Mode.FOLLOWED_SAVE;
    }
    /** Background: the primary started leaving the world this runtime still has open; nothing of it is shown there. */
    public static boolean backgroundLeaving() {
        if(SessionLink.isPrimary()) return false;
        WorldSession w=current();
        return w!=null && w.leaving();
    }

    // ---- primary ----

    /** Primary, any thread: the world of {@code server}, opened now if it is new. */
    static synchronized WorldSession primaryServer(MinecraftServer server) {
        WorldSession w=current();
        if(w!=null && w.owner()==server && !w.closed()) return w;
        String save=server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
        return sessions.open(++lastEpoch,nextPrimaryMode(),save,server,"primary_server");
    }
    /** Server thread, as it starts ({@code ServerTickMixin}): the primary's world opens before anything on that thread uses it. */
    public static void serverStarting(MinecraftServer server) { if(SessionLink.joined() && SessionLink.isPrimary()) primaryServer(server); }
    /** Primary, {@code Minecraft.disconnect}. */
    public static void primaryLeaving() { sessions.leaving(current(),"primary_leaving"); }

    // ---- background ----

    /** Background, render thread: it starts opening the world the primary announced. */
    static WorldSession backgroundOpening(Map<String,String> announced) {
        Mode mode="scene".equals(announced.get("mode")) ? Mode.SCENE : Mode.FOLLOWED_SAVE;
        long epoch;
        try { epoch=Long.parseLong(announced.getOrDefault("worldEpoch","0")); } catch(NumberFormatException e) { epoch=0; }
        return sessions.open(epoch,mode,announced.get("save"),null,"primary_announced");
    }
    /** Background, delivery thread: the primary started leaving its world. */
    public static void backgroundPrimaryLeaving(Minecraft mc) {
        WorldSession w=current();
        if(w==null) { MmlfLog.info("world","primary_leaving_without_world",Map.of()); return; }
        sessions.leaving(w,"primary_leaving");
        mc.execute(()->{
            if(current()!=w || !w.leaving()) return;
            mc.getSoundManager().stop();
        });
    }

    // ---- both ----

    /** Client tick: a world becomes ready once played and closes once this runtime has left it. */
    public static void tick(Minecraft mc) {
        WorldSession w=current();
        if(SessionLink.isPrimary()) {
            MinecraftServer server=mc.getSingleplayerServer();
            if(server!=null) {
                w=primaryServer(server);
                if(mc.level!=null && mc.player!=null) sessions.ready(w);
            } else if(mc.level==null && w!=null) {
                sessions.close(w,"server_closed");
                // The backgrounds close theirs too and report readiness again in the next world.
                SessionLink.peers().worldsClosed();
            }
        } else if(w!=null) {
            // Opening: the world is still being loaded (no client world yet) or failed to open; a new announcement replaces it.
            if(mc.level==null) { if(w.state()!=WorldSession.State.OPENING) sessions.close(w,"world_closed"); }
            else sessions.ready(w);
        }
    }
}
