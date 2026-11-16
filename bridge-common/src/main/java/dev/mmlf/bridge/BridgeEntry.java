package dev.mmlf.bridge;
import dev.mmlf.bridge.transport.PoseChannel;

import dev.mmlf.bridge.collab.Boundary;
import dev.mmlf.bridge.collab.ChunkFollow;
import dev.mmlf.bridge.collab.CoGeneration;
import dev.mmlf.bridge.collab.ContainerBridge;
import dev.mmlf.bridge.collab.GuiClient;
import dev.mmlf.bridge.collab.GuiHost;
import dev.mmlf.bridge.collab.InteractionService;
import dev.mmlf.bridge.collab.InventoryBridge;
import dev.mmlf.bridge.collab.NeighborMirror;
import dev.mmlf.bridge.collab.Occupancy;
import dev.mmlf.bridge.collab.OwnEffects;
import dev.mmlf.bridge.collab.Ownership;
import dev.mmlf.bridge.collab.PlayerFollower;
import dev.mmlf.bridge.collab.RedstoneBridge;
import dev.mmlf.bridge.collab.RemoteCatalog;
import dev.mmlf.bridge.collab.RemoteEntities;
import dev.mmlf.bridge.collab.RemoteItems;
import dev.mmlf.bridge.collab.SaveSync;
import dev.mmlf.bridge.collab.SharedPlayer;
import dev.mmlf.bridge.collab.SharedWorld;
import dev.mmlf.bridge.collab.TickSync;
import dev.mmlf.bridge.collab.WorldClock;
import dev.mmlf.bridge.platform.Experiments;
import dev.mmlf.bridge.platform.ProcessMemory;
import dev.mmlf.bridge.platform.RuntimeContext;
import dev.mmlf.bridge.render.CameraPublisher;
import dev.mmlf.bridge.render.FrameCompositor;
import dev.mmlf.bridge.render.VisualLayers;
import dev.mmlf.bridge.scene.CollabSelfTest;
import dev.mmlf.bridge.scene.DemoScene;
import dev.mmlf.bridge.scene.ExperimentProbe;
import dev.mmlf.bridge.scene.TestPlatform;
import dev.mmlf.bridge.transport.LanguageSync;
import dev.mmlf.bridge.transport.ReleaseSession;
import dev.mmlf.bridge.transport.SessionBootstrap;
import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.transport.SessionStatus;
import dev.mmlf.bridge.world.WorldFollow;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import dev.mmlf.common.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;
import java.util.Map;
import static dev.mmlf.common.MessageRouter.Scope.*;

/**
 * Entry layer and composition root: the loader entry points ({@link #initialize}, {@link #tick}) and what wires the
 * layers together (joining the session, the message routes, the per-tick order, stopping). Formerly BridgeCore.
 */
public final class BridgeEntry {
    private BridgeEntry() {}
    /** Time between this runtime's world frames, for frame-time percentiles (fps alone hides stutter). */
    public static long previousGcCount, previousGcMillis;
    public static long metricAt, previousCpu;
    static long lastLinked;
    static boolean everLinked, backgroundMusicStopped;
    /**
     * Sounds a background must not play: its own music always, and everything once the primary has started leaving its
     * world, until this runtime has left its own (the save barrier keeps it open meanwhile, its mobs still about).
     */
    public static boolean suppressBackgroundMusic(net.minecraft.sounds.SoundSource source) {
        return SessionLink.joined() && !SessionLink.isPrimary() && (source==net.minecraft.sounds.SoundSource.MUSIC || Worlds.backgroundLeaving());
    }
    /** Primary: it is leaving a scene's world (the test platform); once out, the session follows saves again. */
    static boolean sceneLeft;
    /**
     * Primary, out of the scene's world: the session's scene ends, so the next save the player opens is followed by the
     * backgrounds as any other ({@link WorldFollow} reads the scene file each time). The platform stays used for this run.
     */
    static void endScene(Minecraft mc) {
        sceneLeft=false;
        TestPlatform.used();
        try { java.nio.file.Files.deleteIfExists(SessionLink.session().root().resolve("scenario.json")); }
        catch(java.io.IOException e) { MmlfLog.warn("scene","scene_end_failed",Map.of("exception",e.toString())); return; }
        MmlfLog.info("scene","scene_ended",Map.of());
    }
    /**
     * Primary, {@code Minecraft.disconnect} (every way out of a world goes through it): the backgrounds fall silent at
     * once, as this client does. Following a save, they close their world after the final save ({@code mmlf:world_closed},
     * {@link WorldFollow}); a scene world has no save barrier and closes now.
     */
    public static void primaryLeavingWorld(Minecraft mc) {
        if(!SessionLink.isPrimary() || !SessionLink.joined() || SessionLink.session()==null || mc.level==null) return;
        boolean scene=Worlds.scene();
        Worlds.primaryLeaving();
        SessionLink.sendReliable("event","*",new Event("mmlf:world_leaving",Map.of()));
        if(scene) { SessionLink.sendReliable("event","*",new Event("mmlf:world_closed",Map.of())); sceneLeft=true; }
        dev.mmlf.bridge.render.GeometryLayer.hostWorldLeft();
        RemoteEntities.forget(null);
        MmlfLog.info("world","leaving_sent",Map.of("scene",scene));
    }

    public static void initialize(String loader) {
        RuntimeContext context=RuntimeContext.current();
        if(!context.loader().equals(loader)) throw new IllegalStateException("MMLF bridge for "+loader+" runs in "+context.loader());
        String sessionPath=context.sessionPath();
        String id=context.id();
        if (sessionPath==null) {
            Path hosted=context.mayHost()?SessionBootstrap.hostIfRequested(id):null;
            if(hosted==null && context.mayHost() && SessionBootstrap.release()) { releaseRuntime=id; System.out.println("MMLF: release build; session starts with the game"); return; }
            if(hosted==null) { System.out.println("MMLF: no session configured; bridge inactive"); return; }
            sessionPath=hosted.toString();
        }
        join(sessionPath,id,loader);
    }
    static String releaseRuntime;
    static int remoteErrorTick;
    static java.util.concurrent.CompletableFuture<Path> releaseSession;
    /** Release builds join their session once the game is up; a launcher or version the manager cannot use is reported, and the game plays on alone. */
    static void joinRelease(Minecraft mc) {
        if(releaseSession==null) {
            SessionStatus.begin(remoteOf(releaseRuntime),mc.gameDirectory.toPath());
            releaseSession=ReleaseSession.start(mc,releaseRuntime);
        }
        if(!releaseSession.isDone()) return;
        String id=releaseRuntime; releaseRuntime=null;
        try { join(releaseSession.join().toString(),id,id); }
        catch(java.util.concurrent.CompletionException e) {
            Throwable cause=e.getCause()==null?e:e.getCause();
            SessionStatus.fail(cause.getMessage()==null?cause.toString():cause.getMessage(),mc.gameDirectory.toPath().resolve("mmlf"));
        }
    }
    static String remoteOf(String id) { return id.equals("fabric")?"neoforge":"fabric"; }
    /**
     * What lower layers ask of higher ones goes through hooks registered here, the one place that knows every layer:
     * a lower layer never names a higher one.
     */
    private static void wireLayers() {
        Worlds.sceneHooks(mc->DemoScene.shown(mc,SessionLink.session()),mc->DemoScene.createWorld(mc,SessionLink.runtimeId()));
        dev.mmlf.bridge.platform.FramePacing.busyWhen(()->GuiHost.hosting() || InteractionService.miningPos!=null);
        dev.mmlf.bridge.transport.LanguageSync.onLanguageChanged(RemoteCatalog::answer);
        dev.mmlf.bridge.transport.SessionStatus.hiddenWhen(GuiHost::capturing);
        WorldFollow.closeHooks(()->{ SaveSync.leaving(); PlayerFollower.releasePause(); },()->{ SaveSync.worldClosed(); CoGeneration.worldClosed(); });
        Ownership.onRemesh(dev.mmlf.bridge.render.OwnedSectionCompiler::blockChanged);
        PoseChannel.onReceived(CameraPublisher::receivePose);
        RemoteCatalog.iconAtlas(dev.mmlf.bridge.render.VisualLayers::renderAtlas);
        TickSync.diagnostics(dev.mmlf.bridge.scene.ExperimentProbe::serverTick,dev.mmlf.bridge.scene.SharedPlayerSelfTest::serverTick);
        Worlds.sessions().listen(new dev.mmlf.common.WorldSessions.Listener() {
            @Override public void opened(dev.mmlf.common.WorldSession world) {
                if(SessionLink.isPrimary()) Minecraft.getInstance().execute(RemoteCatalog::worldOpened);
            }
            @Override public void leaving(dev.mmlf.common.WorldSession world) {
                // A background's last recorded content is no longer shown in the primary's world.
                if(!SessionLink.isPrimary()) Minecraft.getInstance().execute(()->{ if(Worlds.current()==world) dev.mmlf.bridge.render.DynamicCapture.forgetExported(); });
                // A screen hosted for the primary, or shown from an owner, belongs to the world being left.
                Minecraft.getInstance().execute(()->{ if(SessionLink.isPrimary()) GuiClient.ended("world_leaving"); else GuiHost.primaryLost("world_leaving"); });
            }
        });
    }
    static void join(String sessionPath,String id,String loader) {
        try {
            router=routes();
            SessionLink.peers().listen(new PeerCleanup());
            wireLayers();
            RuntimeClient link=SessionLink.open(Session.read(Path.of(sessionPath)),id,loader,BridgeEntry::receive);
            MmlfLog.info("render","block_ownership",Map.of("mode",dev.mmlf.bridge.render.RenderOwnership.filterBlocks()?"filtered":"full", "runtime",id));
            BridgeEvents.install((target,event)->SessionLink.sendReliable("event",target,event));
            RemoteItems.initialize(link,SessionLink.isPrimary());
            InventoryBridge.initialize(SessionLink.runtimeId(),SessionLink.isPrimary());
            ContainerBridge.publish(SessionLink.identity(),id);
            link.start();
            if(SessionLink.isPrimary()) SessionStatus.launched(remoteOf(id),SessionLink.session().root().resolve(remoteOf(id)+".log"));
            if(SessionLink.isPrimary()) SharedWorld.publish(SessionLink.session().identity());
            if(SessionLink.isPrimary()) { CoGeneration.publish(SessionLink.session().identity()); SharedPlayer.publish(SessionLink.session().identity()); } else CoGeneration.publishBackground(SessionLink.session().identity());
            RuntimeContext context=RuntimeContext.current();
            RuntimeClient joined=SessionLink.client();
            String identity=SessionLink.session().identity(); boolean hosting=SessionLink.isPrimary();
            Runnable release=()->{ if(hosting) { SharedWorld.withdraw(identity); CoGeneration.withdraw(identity); SharedPlayer.withdraw(identity); } else CoGeneration.withdrawBackground(identity); ContainerBridge.withdraw(identity,id); joined.close(); try { if(PoseChannel.poseFile!=null) PoseChannel.poseFile.close(); } catch(java.io.IOException ignored) {} };
            // In-JVM, this runtime ends long before the JVM: its backend runs the clean-up once its game has returned.
            if(context.inJvm()) dev.mmlf.common.InJvm.registry().put(dev.mmlf.launcher.runtime.InJvmBackend.cleanupKey(context.loader()),release);
            else Runtime.getRuntime().addShutdownHook(new Thread(release,"mmlf-shutdown"));
            System.out.println("MMLF bridge initialized: "+id);
            if(context.inJvm()) {
                dev.mmlf.common.InJvm.registry().put(dev.mmlf.launcher.runtime.InJvmBackend.initializedKey(context.loader()),Boolean.TRUE);
                phase(dev.mmlf.launcher.runtime.InJvmBackend.PHASE_BRIDGE);
            }
        } catch(Exception e) {
            System.err.println("MMLF session initialization failed: "+e);
            SessionStatus.fail("MMLF 会话初始化失败："+e,Path.of(sessionPath).getParent());
        }
    }
    /** Routes of the session's messages; set before the link starts. */
    static MessageRouter router;
    static void receive(Wire message) { MessageRouter r=router; if(r!=null) r.dispatch(message); }
    /**
     * Every message this runtime acts on and who acts on it. Each handler runs on the delivery thread and moves work to
     * the client or server thread exactly as the former receive chain did; events no topic matches go to other mods.
     */
    static MessageRouter routes() {
        MessageRouter r=new MessageRouter(new MessageRouter.Roles() {
            @Override public boolean isPrimary() { return SessionLink.isPrimary(); }
            @Override public String primaryId() { return SessionLink.primaryId(); }
        });
        // ---- the Coordinator's reports ----
        r.on("welcome",ANY,m->SessionLink.peers().welcomed(m.body(Welcome.class).runtimes()));
        r.on("runtime_joined",ANY,m->SessionLink.peers().joined(m.data().get("runtime").getAsString()));
        r.on("runtime_left",ANY,m->SessionLink.peers().left(m.data().get("runtime").getAsString()));
        r.on("disconnected",ANY,m->SessionLink.peers().disconnected());
        r.on("error",ON_PRIMARY,m->{
            if(!m.source().equals("coordinator")) return;
            var id=m.data().get("fallbackAction");
            if(id!=null && id.isJsonPrimitive()) Minecraft.getInstance().execute(()->InteractionService.localUse(id.getAsString()));
        });
        // ---- blocks and ownership ----
        r.on("object",ANY,m->{
            ObjectState object=m.body(ObjectState.class);
            if(SessionLink.joined() && !object.owner().equals(SessionLink.runtimeId())) { Boundary.remoteChanged(object); Occupancy.receive(object); }
            Ownership.updateCollision(object);
        });
        r.on("blocks",ANY,m->{ ChunkBlocks blocks=m.body(ChunkBlocks.class); if(SessionLink.joined() && !blocks.owner().equals(SessionLink.runtimeId())) Occupancy.receive(blocks); });
        r.on("neighbors",ANY,m->NeighborMirror.receive(m.source(),m.body(Neighbors.class)));
        r.on("signals",ANY,m->RedstoneBridge.receive(m.source(),m.body(Signals.class)));
        r.onEvent("mmlf:interest",ANY,(m,e)->ChunkFollow.receive(m.source(),e));
        r.onEvent("mmlf:chunks",ANY,(m,e)->ChunkFollow.receive(m.source(),e));
        // ---- interaction ----
        r.on("ray_request",ANY,m->{
            Minecraft mc=Minecraft.getInstance();
            RayRequest request=m.body(RayRequest.class);
            // Every use and attack of the primary waits for the slowest answer, so none waits for a frame: the primary
            // answers with what it computed as it asked, a background on its server thread, idle between ticks.
            RayHit ready=SessionLink.isPrimary() ? InteractionService.ownAnswers.remove(request.actionId()) : null;
            var server=SessionLink.isPrimary() ? null : mc.getSingleplayerServer();
            if(ready!=null) InteractionService.sendHit(ready);
            else if(server!=null && InteractionService.SERVER_RAYS) server.execute(()->InteractionService.raycastOnServer(server,request));
            else mc.execute(()->InteractionService.raycast(request));
        });
        r.on("interaction",ANY,m->{ Interaction action=m.body(Interaction.class); Minecraft.getInstance().execute(()->InteractionService.interact(action)); });
        r.on("interaction_result",ON_PRIMARY,m->{ InteractionResult result=m.body(InteractionResult.class); Minecraft.getInstance().execute(()->InteractionService.interactionResult(m.source(),result)); });
        r.onEvent("mmlf:use_stop",ON_BACKGROUND_FROM_PRIMARY,(m,e)->InteractionService.primaryUsing=false);
        r.onEvent("mmlf:attack_stop",ON_BACKGROUND_FROM_PRIMARY,(m,e)->{ Minecraft mc=Minecraft.getInstance(); mc.execute(()->InteractionService.stopMining(mc)); });
        // ---- entities and the shared player ----
        r.on("entities",ON_PRIMARY,m->RemoteEntities.receive(m.source(),m.body(Messages.Entities.class)));
        r.on("player_xp",ON_PRIMARY,m->{ Minecraft mc=Minecraft.getInstance(); int amount=m.body(Messages.PlayerExperience.class).amount(); mc.execute(()->RemoteEntities.applyExperience(mc,amount)); });
        r.on("player_damage",ON_PRIMARY,m->{ Minecraft mc=Minecraft.getInstance(); var damage=m.body(Messages.PlayerDamage.class); mc.execute(()->RemoteEntities.applyDamage(mc,m.source(),damage)); });
        r.on("player_vitals",ON_BACKGROUND_FROM_PRIMARY,m->SharedPlayer.receive(m.body(dev.mmlf.common.PlayerShare.Vitals.class)));
        r.on("player_change",ON_PRIMARY,m->SharedPlayer.applyChange(Minecraft.getInstance(),m.source(),m.body(dev.mmlf.common.PlayerShare.Change.class)));
        r.on("player_own",ON_PRIMARY,m->OwnEffects.receive(m.source(),m.body(dev.mmlf.common.PlayerShare.OwnEffects.class)));
        r.on("player",ON_BACKGROUND_FROM_PRIMARY,m->PlayerFollower.followPlayer(Minecraft.getInstance(),m.body(PlayerState.class)));
        r.onEvent("mmlf:play",ON_BACKGROUND_FROM_PRIMARY,(m,e)->PlayerFollower.followPlay(Minecraft.getInstance(),e.values()));
        r.onEvent("mmlf:mob_counts",ANY,(m,e)->dev.mmlf.bridge.collab.MobSpawning.receiveCounts(e.values()));
        r.onEvent("mmlf:spawn_weights",ON_PRIMARY,(m,e)->dev.mmlf.bridge.collab.MobSpawning.receiveWeights(e.values()));
        r.onEvent("mmlf:player_stuck",ON_PRIMARY,(m,e)->dev.mmlf.bridge.collab.StandInFeel.receiveStuck(e.values()));
        r.onEvent("mmlf:stand_in_sound",ON_BACKGROUND_FROM_PRIMARY,(m,e)->dev.mmlf.bridge.collab.StandInFeel.playForPrimary(e.values()));
        r.onEvent("mmlf:player_cure",ON_BACKGROUND_FROM_PRIMARY,(m,e)->OwnEffects.cured(e.values().get("cure")));
        r.onEvent("mmlf:tick",ON_BACKGROUND_FROM_PRIMARY,(m,e)->{ try { TickSync.permit(Long.parseLong(e.values().get("tick"))); } catch(RuntimeException ignored) { } });
        r.onEvent("mmlf:language",ON_BACKGROUND_FROM_PRIMARY,(m,e)->{
            Minecraft mc=Minecraft.getInstance();
            mc.execute(()->{
                LanguageSync.initialLanguageReceived=true;
                LanguageSync.followLanguage(mc,e.values().get("code"),m.source());
            });
        });
        r.onEvent("mmlf:world_clock",ANY,(m,e)->WorldClock.receive(m.source(),e.topic(),e.values()));
        r.onEvent("mmlf:world_time",ANY,(m,e)->WorldClock.receive(m.source(),e.topic(),e.values()));
        r.onEvent("mmlf:world_weather",ANY,(m,e)->WorldClock.receive(m.source(),e.topic(),e.values()));
        // ---- items, inventories and screens ----
        r.on("inventory",ANY,InventoryBridge::receive);
        r.on("gui",ON_PRIMARY,GuiClient::receive);
        r.on("gui_input",ON_BACKGROUND_FROM_PRIMARY,m->GuiHost.receive(m.body(GuiInput.class)));
        r.onEvent("mmlf:picked",ANY,(m,e)->RemoteItems.receive(m));
        r.onEvent("mmlf:catalog_request",ON_BACKGROUND_FROM_PRIMARY,(m,e)->Minecraft.getInstance().execute(()->RemoteCatalog.answer(m.source())));
        r.onEvent("mmlf:catalog",ON_PRIMARY,(m,e)->RemoteCatalog.receive(m.source(),e.values().get("catalog")));
        r.onEvent("mmlf:icon_request",ON_BACKGROUND_FROM_PRIMARY,(m,e)->VisualLayers.requestIcons(e.values().get("references")));
        // ---- worlds and saves ----
        r.onEvent("mmlf:world_leaving",ON_BACKGROUND_FROM_PRIMARY,(m,e)->Worlds.backgroundPrimaryLeaving(Minecraft.getInstance()));
        // A save or a scene: the background opens its world on the primary's word, in the announced mode.
        r.onEvent("mmlf:world",ON_BACKGROUND_FROM_PRIMARY,(m,e)->WorldFollow.receive(Minecraft.getInstance(),e.topic(),e.values()));
        r.onEvent("mmlf:world_closed",ON_BACKGROUND_FROM_PRIMARY,(m,e)->WorldFollow.receive(Minecraft.getInstance(),e.topic(),e.values()));
        r.onEvent("mmlf:world_ready",ANY,(m,e)->{
            // Readiness is not tied to this runtime's world: a primary waits for it before opening its own (a scene)
            // and has none yet. The save barrier checks the world itself (SaveSync); a mismatch is only reported here.
            SessionLink.peers().worldReady(m.source());
            MmlfLog.info("connection","runtime_world_ready",Map.of("runtime",m.source()));
            var server=Minecraft.getInstance().getSingleplayerServer();
            if(server!=null && SessionLink.session()!=null && !WorldFollow.saveScope(server).equals(e.values().get("world")))
                MmlfLog.warn("connection","runtime_world_other_save",Map.of("runtime",m.source(),
                    "own",WorldFollow.saveScope(server),"theirs",String.valueOf(e.values().get("world"))));
            var world=Worlds.current();
            String theirEpoch=e.values().get("worldEpoch");
            if(SessionLink.isPrimary() && world!=null && theirEpoch!=null && !theirEpoch.equals(Long.toString(world.epoch())))
                MmlfLog.warn("connection","runtime_world_other_epoch",Map.of("runtime",m.source(),"own",world.epoch(),"theirs",theirEpoch));
            if(SessionLink.isPrimary()) SaveSync.checkOpened(m.source(),e.values().get("epoch"));
        });
        for(String topic:java.util.List.of("mmlf:save_freeze","mmlf:save_commit","mmlf:save_abort"))
            r.onEvent(topic,ON_BACKGROUND_FROM_PRIMARY,(m,e)->SaveSync.backgroundReceive(e.topic(),e.values()));
        for(String topic:java.util.List.of("mmlf:save_frozen","mmlf:save_done"))
            r.onEvent(topic,ON_PRIMARY,(m,e)->SaveSync.primaryReceive(m.source(),e.topic(),e.values()));
        r.onEvent("mmlf:session_end",ON_BACKGROUND,(m,e)->{ Minecraft mc=Minecraft.getInstance(); mc.execute(()->stopBackground(mc,"session_end")); });
        // ---- rendering ----
        r.onEvent("mmlf:render_shader_capability",ANY,(m,e)->dev.mmlf.bridge.render.RenderShaderCapabilities.receive(m.source(),e.values()));
        r.onEvent("mmlf:render_reset",ON_BACKGROUND_FROM_PRIMARY,(m,e)->dev.mmlf.bridge.render.GeometryLayer.hostReset(e.values()));
        r.onEvent("mmlf:geometry_consumed",ANY,(m,e)->dev.mmlf.bridge.render.GeometryLayer.receiveConsumed(m.source(),e.values()));
        r.onEvent("mmlf:latency_feedback",ON_BACKGROUND_FROM_PRIMARY,(m,e)->{
            try { double ms=Double.parseDouble(e.values().get("captureToCompositeMs"));
                Minecraft.getInstance().execute(()->CameraPublisher.predictionLead.observeTransfer(ms,System.nanoTime()));
            } catch(RuntimeException ignored) { }
        });
        // ---- diagnostics ----
        r.onEventPrefix("mmlf:experiment",ANY,(m,e)->ExperimentProbe.receive(Minecraft.getInstance(),m.source(),e));
        r.onEventPrefix("mmlf:selftest",ANY,(m,e)->CollabSelfTest.receive(Minecraft.getInstance(),m.source(),e));
        // ---- other mods' events ----
        r.onUnroutedEvent((m,event)->{
            Minecraft mc=Minecraft.getInstance();
            mc.execute(()-> {
                if (mc.getSingleplayerServer()!=null) mc.getSingleplayerServer().execute(()->BridgeEvents.deliver(event));
                else BridgeEvents.deliver(event);
            });
        });
        return r;
    }
    /**
     * What goes when a runtime or the link goes; the former receive branches' clean-up, unchanged. Each module takes its
     * own part once its state belongs to a world (design S4/S5).
     */
    static final class PeerCleanup implements Peers.Listener {
        @Override public void welcomed(java.util.Collection<String> runtimes) {
            Minecraft mc=Minecraft.getInstance();
            mc.execute(()->{ var server=mc.getSingleplayerServer(); if(server!=null) server.execute(Boundary::republish); });
            RedstoneBridge.runtimeJoined();
        }
        @Override public void joined(String id) {
            Minecraft.getInstance().execute(()->dev.mmlf.bridge.render.GeometryLayer.submitterJoined(id));
            RedstoneBridge.runtimeJoined();
        }
        @Override public void left(String id) {
            Minecraft mc=Minecraft.getInstance();
            OwnEffects.forget(id); if(id.equals(SessionLink.session().primary())) { PlayerFollower.playerVersion=0; SharedPlayer.forget(); PlayerFollower.primaryPaused=false; InteractionService.primaryUsing=false; } Boundary.forgetRemote(id); remoteLeft(mc,id); RemoteEntities.forget(id); RedstoneBridge.runtimeLeft(id);
            mc.execute(()->{GuiClient.runtimeLeft(id); RemoteCatalog.forget(id); if(id.equals(SessionLink.session().primary())) GuiHost.primaryLost("primary_left"); FrameCompositor.RemoteLayer removed=FrameCompositor.remoteLayers.remove(id); if(removed!=null) removed.close(); Ownership.forgetCollisions(id); if(id.equals(SessionLink.session().primary())) { PoseChannel.lastRemotePose=null; PoseChannel.poseSequence=0; PoseChannel.poseSourceNanos=0; CameraPublisher.yawRate=CameraPublisher.pitchRate=0; CameraPublisher.predictionLead.reset(); CameraPublisher.adaptiveOverscan.reset(); }});
        }
        @Override public void disconnected() {
            Minecraft mc=Minecraft.getInstance();
            Worlds.readyReportLost(); PlayerFollower.playerVersion=0; SharedPlayer.forget(); OwnEffects.forget(null); PlayerFollower.primaryPaused=false; InteractionService.primaryUsing=false; Boundary.forgetAllRemote(); Occupancy.forget(null); RemoteEntities.forget(null); SharedWorld.detach(); RedstoneBridge.disconnected();
            mc.execute(()->{GuiClient.disconnected(); GuiHost.primaryLost("disconnected"); Ownership.forgetCollisions(null); FrameCompositor.remoteLayers.values().forEach(FrameCompositor.RemoteLayer::close); FrameCompositor.remoteLayers.clear(); FrameCompositor.displayFrame=null; FrameCompositor.displayPixels=null; PoseChannel.lastRemotePose=null; PoseChannel.poseSequence=0; PoseChannel.poseSourceNanos=0; CameraPublisher.yawRate=CameraPublisher.pitchRate=0; CameraPublisher.predictionLead.reset(); CameraPublisher.adaptiveOverscan.reset();});
        }
    }
    /** In-JVM: tells the backend how far this runtime got (see {@link dev.mmlf.launcher.runtime.InJvmBackend#phaseKey}). */
    static void phase(String phase) {
        RuntimeContext context=RuntimeContext.current();
        if(!context.inJvm()) return;
        var registry=dev.mmlf.common.InJvm.registry();
        String key=dev.mmlf.launcher.runtime.InJvmBackend.phaseKey(context.loader());
        // A stopping runtime stays stopping; a phase reported again changes nothing.
        if(dev.mmlf.launcher.runtime.InJvmBackend.PHASE_STOPPING.equals(registry.get(key))) return;
        registry.put(key,phase);
    }
    static boolean gamePhaseSent;
    public static void tick(Minecraft mc) {
        if(!gamePhaseSent) { gamePhaseSent=true; phase(dev.mmlf.launcher.runtime.InJvmBackend.PHASE_GAME); }
        // Send the host language before either side enters a world, so the guest can reload at the title screen.
        LanguageSync.announceLanguage();
        // The host may ask for a stop before this runtime has joined its session (still loading, stalled on a screen).
        if(++stopCheckTick%20==0 && RuntimeContext.current().inJvm() && dev.mmlf.common.InJvm.registry().get(dev.mmlf.launcher.runtime.InJvmBackend.stopKey(RuntimeContext.current().loader()))!=null) { stopBackground(mc,"host_stop"); return; }
        if(releaseRuntime!=null) joinRelease(mc);
        WorldClock.tick(mc);
        OwnEffects.clientTick(mc);
        dev.mmlf.bridge.collab.StandInFeel.clientTick(mc);
        if(Experiments.GEOMETRY) dev.mmlf.bridge.render.GeometryLayer.tick(mc);
        if(SessionLink.joined() && SessionLink.isPrimary() && !SessionStatus.failed() && ++remoteErrorTick%20==0) {
            Path error=SessionLink.session().root().resolve("remote-error.txt");
            if(java.nio.file.Files.isRegularFile(error)) {
                String text;
                try { text=java.nio.file.Files.readString(error,java.nio.charset.StandardCharsets.UTF_8).trim(); } catch(java.io.IOException e) { text="远端启动失败，详见 "+error; }
                SessionStatus.fail(text,SessionLink.session().root());
            }
        }
        if(SessionLink.joined() && SessionLink.isPrimary()) SessionStatus.tick(mc,SessionLink.peers().anyOtherThan(SessionLink.runtimeId()),SessionLink.peers().anyWorldReady(),Worlds.nextPrimaryMode()==dev.mmlf.common.WorldSession.Mode.FOLLOWED_SAVE);
        else if(releaseRuntime!=null) SessionStatus.tick(mc,false,false,false);
        if(SessionLink.joined()) Worlds.tick(mc);
        if(SessionLink.joined() && !SessionLink.isPrimary()) Ownership.tick(mc);
        if(SessionLink.joined()) DemoScene.tick(mc,SessionLink.session(),SessionLink.runtimeId());
        if(SessionLink.connected()) { if(SessionLink.isPrimary()) RemoteEntities.push(mc); else RemoteEntities.publish(mc,SessionLink.runtimeId()); }
        if(SessionLink.joined()) { if(SessionLink.isPrimary()) SharedWorld.primaryTick(mc,SessionLink.session()); else SharedWorld.tick(); }
        if(sceneLeft && SessionLink.isPrimary() && mc.level==null && mc.getSingleplayerServer()==null) endScene(mc);
        if(SessionLink.connected()) WorldFollow.tick(mc,SessionLink.isPrimary());
        if(SessionLink.joined() && !SessionLink.isPrimary()) {
            if(!backgroundMusicStopped) {
                mc.getSoundManager().stop(null,net.minecraft.sounds.SoundSource.MUSIC);
                backgroundMusicStopped=true;
            }
            // A background runtime has no visible window; it must not outlive the session that started it.
            long now=System.nanoTime();
            if(SessionLink.connected()) { everLinked=true; lastLinked=now; }
            else if(everLinked && now-lastLinked>5_000_000_000L) { stopBackground(mc,"session_lost"); return; }
        }
        // The shared pose never gates the bridge: links and interaction work while it is retried once a second.
        PoseChannel.open();
        if(SessionLink.connected() && SessionLink.isPrimary()) { PlayerFollower.sendPlayer(mc); PlayerFollower.sendPlay(mc); }
        if(SessionLink.joined() && !SessionLink.isPrimary() && LanguageSync.pendingLanguage!=null) LanguageSync.followLanguage(mc,LanguageSync.pendingLanguage,SessionLink.session().primary());
        if(!SessionLink.connected() || mc.player==null || mc.level==null) return;
        if(mc.getSingleplayerServer()!=null && Worlds.claimReadyReport()) {
            String scope=WorldFollow.saveScope(mc.getSingleplayerServer());
            var world=Worlds.current();
            String worldEpoch=world==null?"":Long.toString(world.epoch());
            SessionLink.sendReliable("event","*",new Event("mmlf:world_ready",SessionLink.isPrimary()?Map.of("world",scope,"worldEpoch",worldEpoch):Map.of("world",scope,"epoch",SaveSync.ownEpoch(),"worldEpoch",worldEpoch)));
            phase(dev.mmlf.launcher.runtime.InJvmBackend.PHASE_WORLD);
        }
        // The primary decides when the game pauses; a background that paused on lost focus would stop its server.
        if(!SessionLink.isPrimary() && mc.options.pauseOnLostFocus) mc.options.pauseOnLostFocus=false;
        if(SessionLink.isPrimary()) { GuiClient.tick(mc); VisualLayers.flushIconRequests(); RemoteCatalog.tick(mc,SessionLink.peers().online()); } else GuiHost.tick(mc);
        InventoryBridge.tick(mc);
        InteractionService.checkPlacements(mc);
        InteractionService.continueMining(mc);
        // Clouds write depth and move with each runtime's own game time: a background's clouds would drift over the primary's.
        if(!SessionLink.isPrimary() && mc.options.cloudStatus().get()!=net.minecraft.client.CloudStatus.OFF) mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        // Vsync would hold a background to the monitor's rate regardless of BACKGROUND_FPS.
        if(!SessionLink.isPrimary() && mc.options.enableVsync().get()) { mc.options.enableVsync().set(false); mc.getWindow().updateVsync(false); }
        if (!SessionLink.isPrimary() && PoseChannel.lastRemotePose!=null && mc.level.dimension().location().toString().equals(PoseChannel.lastRemotePose.dimension())) {
            Pose p=PoseChannel.lastRemotePose;
            mc.player.setPos(p.playerX(),p.playerY(),p.playerZ());
            mc.player.setYRot(p.yaw()); mc.player.setXRot(p.pitch());
            PlayerFollower.matchPrimaryWindow(mc,p);
            if(mc.getSingleplayerServer()!=null) {
                var server=mc.getSingleplayerServer();
                server.execute(()->{
                    if(!server.getPlayerList().getPlayers().isEmpty()) {
                        var player=server.getPlayerList().getPlayers().getFirst();
                        if(player.level().dimension().location().toString().equals(p.dimension())) PlayerFollower.follow(player,p);
                    }
                });
            }
        } else if(!SessionLink.isPrimary() && PoseChannel.lastRemotePose!=null) PlayerFollower.followDimension(mc,PoseChannel.lastRemotePose);
        // The boundary is published at the end of each server tick (TickSync.endOfTick), not from here.
        metrics();
    }
    /** Whether this runtime is a background whose window stays hidden; known before the window is created. */
    public static boolean hiddenBackground() { return RuntimeContext.current().hiddenWindow(); }
    static int stopCheckTick;
    static void stopBackground(Minecraft mc,String reason) {
        if(!SessionLink.markStopping()) return;
        phase(dev.mmlf.launcher.runtime.InJvmBackend.PHASE_STOPPING);
        MmlfLog.warn("connection","background_stopping",Map.of("reason",reason));
        // As when leaving a world (WorldFollow.close): a server held still for the final save must run to stop.
        SaveSync.leaving();
        mc.stop();
    }
    /**
     * The game is about to release its window and GL resources. A primary hosting in-JVM runtimes stops them first and
     * waits (bounded) for them: they must not be inside their own GL driver calls while this JVM ends.
     */
    public static void destroying() {
        if(SessionLink.isPrimary() && SessionLink.connected() && SessionLink.markStopping()) {
            SessionLink.sendReliable("event","*",new Event("mmlf:session_end",Map.of()));
            MmlfLog.info("connection","session_end_sent",Map.of());
        }
        SessionBootstrap.stopRuntimes();
    }
    /**
     * Keep the peer available while Minecraft disconnects and saves the local world. The primary's window goes away at
     * once and its sounds stop: what follows (saving both worlds at one save point, ending the session, waiting for the
     * hosted runtimes to stop) still runs to the end, out of sight, instead of holding a frozen window for seconds.
     */
    public static void closing() {
        phase(dev.mmlf.launcher.runtime.InJvmBackend.PHASE_STOPPING);
        if(!SessionLink.isPrimary() || windowHidden || !com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) return;
        windowHidden=true;
        var mc=Minecraft.getInstance();
        try {
            org.lwjgl.glfw.GLFW.glfwHideWindow(mc.getWindow().getWindow());
            mc.getSoundManager().stop();
            MmlfLog.info("connection","window_hidden_for_shutdown",Map.of());
        } catch(RuntimeException e) { MmlfLog.warn("connection","window_hide_failed",Map.of("exception",e.toString())); }
    }
    static boolean windowHidden;
    /** True once another runtime has a loaded world. */
    /**
     * A runtime left. On the primary in a world its blocks stay where they are, still solid and lit, but no longer run:
     * releasing them would open holes the player could fall or build into, and water would flow in, which the owner's
     * world would not know of when it is back. They are released when this world closes.
     */
    static void remoteLeft(Minecraft mc,String id) {
        if(!SessionLink.isPrimary() || mc.getSingleplayerServer()==null || SessionLink.session()==null || id.equals(SessionLink.session().primary())) { Occupancy.forget(id); return; }
        MmlfLog.warn("connection","remote_left_frozen",Map.of("runtime",id));
        mc.execute(()->{ if(mc.gui!=null) mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(
            "MMLF：远端 "+id+" 已退出。它的方块保留占位但不再运行；请保存并重新进入世界以恢复。")); });
    }
    public static int peerCount() { return SessionLink.peers().online().size(); }
    static void metrics() {
        long now=System.nanoTime();
        if(metricAt==0) {
            metricAt=now; previousCpu=ProcessHandle.current().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos();
            for(var gc:java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) { previousGcCount+=Math.max(0,gc.getCollectionCount()); previousGcMillis+=Math.max(0,gc.getCollectionTime()); }
            FrameCompositor.frameTimesMs.drain();
            return;
        }
        if(now-metricAt<java.util.concurrent.TimeUnit.SECONDS.toNanos(10)) return;
        long cpu=ProcessHandle.current().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos();
        double seconds=(now-metricAt)/1e9;
        var memory=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String,Object> measurements=new java.util.LinkedHashMap<>();
        measurements.put("cpuOneCorePercent",100.0*(cpu-previousCpu)/(now-metricAt)); measurements.put("heapUsedMb",memory.getUsed()/1048576.0);
        measurements.put("worldFramesPerSecond",FrameCompositor.metricFrames/seconds); measurements.put("messageRttMs",SessionLink.client().rttMillis);
        measurements.put("experiments",Experiments.describe());
        var frameTimes=FrameCompositor.frameTimesMs.drain();
        measurements.put("frameTimeP50Ms",frameTimes.p50()); measurements.put("frameTimeP95Ms",frameTimes.p95()); measurements.put("frameTimeP99Ms",frameTimes.p99());
        long gcCount=0, gcMillis=0;
        for(var gc:java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) { gcCount+=Math.max(0,gc.getCollectionCount()); gcMillis+=Math.max(0,gc.getCollectionTime()); }
        // In one JVM both runtimes share the collector: these are JVM-wide, not this runtime's alone.
        measurements.put("gcCollections",gcCount-previousGcCount); measurements.put("gcMillis",gcMillis-previousGcMillis);
        previousGcCount=gcCount; previousGcMillis=gcMillis;
        if(Experiments.ON_DEMAND && !SessionLink.isPrimary()) { measurements.put("idleFrames",CameraPublisher.idleFrames); measurements.put("busyFrames",CameraPublisher.busyFrames); CameraPublisher.idleFrames=CameraPublisher.busyFrames=0; }
        var integrated=Minecraft.getInstance().getSingleplayerServer();
        if(integrated!=null) { measurements.put("serverMspt",integrated.getAverageTickTimeNanos()/1e6); measurements.put("serverTicks",integrated.getTickCount());
            int chunks=0; for(var level:integrated.getAllLevels()) chunks+=level.getChunkSource().getLoadedChunksCount(); measurements.put("loadedChunks",chunks); }
        if(Experiments.LOCKSTEP) measurements.putAll(TickSync.metrics());
        if(Experiments.FOLLOW) measurements.putAll(ChunkFollow.metrics());
        try {var rss=ProcessMemory.residentMb();if(rss.isPresent()) measurements.put("rssMb",rss.getAsDouble());else measurements.put("rssAvailable",false);} catch(LinkageError | RuntimeException e) {measurements.put("rssAvailable",false);MmlfLog.sampled("metrics","rss_unavailable",60000,Map.of("exception",e.getClass().getName()));}
        MmlfLog.info("metrics","runtime",measurements);
        FrameCompositor.metricFrames=0; metricAt=now; previousCpu=cpu;
    }
}
