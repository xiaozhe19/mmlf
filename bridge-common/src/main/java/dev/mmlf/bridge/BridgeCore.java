package dev.mmlf.bridge;

import dev.mmlf.common.*;
import dev.mmlf.common.Messages.*;
import dev.mmlf.common.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import java.nio.file.Path;
import java.util.Map;

/** Common bridge code is compiled separately into each loader-specific mod. */
public final class BridgeCore {
    private static RuntimeClient link;
    private static boolean primary;
    private static long sequence;
    private static volatile Pose lastRemotePose;
    private static Session session;
    private static String sessionIdentity;
    private static FrameFile frames;
    private static PoseFile poseFile;
    private static long poseSequence, poseSourceNanos, nextPoseOpen;

    private static final class RemoteLayer {
        Frame displayed;
        FrameFile.Latest cursor;
        FrameFile file;
        long nextOpen;
        FrameFile.Pixels pixels, spare;
        final SampleWindow frameAgeMs=new SampleWindow(2048), angularErrorDegrees=new SampleWindow(2048);
        long metricSince, feedbackSince;
        void close() { if(file!=null) try { file.close(); } catch(java.io.IOException ignored) {} }
    }
    private static final Map<String,RemoteLayer> remoteLayers=new java.util.LinkedHashMap<>();
    private static Frame displayFrame;
    private static FrameFile.Pixels displayPixels;
    private static Pose primaryPose;
    private static final java.util.Set<String> peers = new java.util.HashSet<>();
    private static final java.util.Map<net.minecraft.core.BlockPos,ObjectState> localObjects = new java.util.HashMap<>();
    private static int scanTick;
    private static long metricAt, metricFrames, previousCpu;
    private static boolean compositeReported;
    private static final java.util.Set<net.minecraft.core.BlockPos> ownedVanilla=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static long lastAttack;
    private static String lastHeld="";
    private static long heldSent,lastResize,lastLinked,languageSent;
    private static String lastLanguage="";
    private static boolean everLinked, worldReadySent, backgroundMusicStopped;
    public static boolean suppressBackgroundMusic(net.minecraft.sounds.SoundSource source) {
        return link!=null && !primary && source==net.minecraft.sounds.SoundSource.MUSIC;
    }
    /** Runtimes that reported a loaded world; the primary waits for one before opening its own. */
    private static final java.util.Set<String> readyRuntimes=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static Session session() {return session;}
    public static String runtimeId() {return link==null?"unknown":link.id;}
    public static String primaryId() {return session==null?null:session.primary();}
    /** Sends a control message when linked; dropped otherwise, like every other bridge message. */
    public static void send(String type,String target,Object body) { if(link!=null) link.send(type,target,body); }
    public static FrameTiming.Clock calibration() { return link==null?null:link.calibration(); }

    private record RemoteShape(String owner,String dimension,java.util.List<net.minecraft.world.phys.shapes.VoxelShape> shapes) {}
    private static final java.util.Map<String,RemoteShape> collisions=new java.util.concurrent.ConcurrentHashMap<>();
    public static java.util.List<net.minecraft.world.phys.shapes.VoxelShape> remoteCollisions(net.minecraft.world.entity.Entity entity,net.minecraft.world.phys.AABB box) {
        if(!primary || !(entity instanceof net.minecraft.world.entity.player.Player) || entity.isSpectator()) return java.util.List.of();
        var result=new java.util.ArrayList<net.minecraft.world.phys.shapes.VoxelShape>();
        String dimension=entity.level().dimension().location().toString();
        for(var value:collisions.values()) if(value.dimension().equals(dimension))
            for(var shape:value.shapes()) if(shape.bounds().intersects(box)) result.add(shape);
        return result;
    }
    private static void updateCollision(ObjectState object) {
        if(link==null || object.owner().equals(link.id)) return;
        if(object.type().equals("minecraft:air")) {collisions.remove(object.id());return;}
        String encoded=object.state().get("_collision"); if(encoded==null) return;
        double[][] boxes=Wire.JSON.fromJson(encoded,double[][].class);
        var shapes=new java.util.ArrayList<net.minecraft.world.phys.shapes.VoxelShape>();
        if(boxes.length>64) throw new IllegalArgumentException("too many collision boxes");
        for(double[] b:boxes) {
            if(b.length!=6) throw new IllegalArgumentException("invalid collision box");
            for(double coordinate:b) if(!Double.isFinite(coordinate) || coordinate < -16 || coordinate > 16) throw new IllegalArgumentException("invalid collision extent");
            if(b[3]<=b[0] || b[4]<=b[1] || b[5]<=b[2]) continue;
            shapes.add(net.minecraft.world.phys.shapes.Shapes.box(b[0],b[1],b[2],b[3],b[4],b[5]).move(object.x(),object.y(),object.z()));
        }
        MmlfLog.debug("collision","shape_update",Map.of("objectId",object.id(),"owner",object.owner(),"revision",object.revision(),"boxes",shapes.size()));
        collisions.put(object.id(),new RemoteShape(object.owner(),object.dimension(),java.util.List.copyOf(shapes)));
    }
    /** A vanilla block this runtime placed for its own scene belongs to it, like one it placed through interaction. */
    public static void ownVanilla(net.minecraft.core.BlockPos pos) { ownedVanilla.add(pos.immutable()); }
    public static boolean renderOwned(net.minecraft.world.level.block.state.BlockState state,net.minecraft.core.BlockPos pos) {
        return primary || !connected() || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft") || ownedVanilla.contains(pos);
    }
    private static java.nio.ByteBuffer colorBuffer, depthBuffer;
    private BridgeCore() {}
    public static void initialize(String loader) {
        String sessionPath=System.getProperty("mmlf.session", System.getenv("MMLF_SESSION"));
        String id=System.getProperty("mmlf.runtime",loader);
        if (sessionPath==null || sessionPath.isBlank()) {
            Path hosted=SessionBootstrap.hostIfRequested(id);
            if(hosted==null) { System.out.println("MMLF: no session configured; bridge inactive"); return; }
            sessionPath=hosted.toString();
        }
        try {
            session=Session.read(Path.of(sessionPath));
            sessionIdentity=session.identity();
            primary=id.equals(session.primary());
            link=new RuntimeClient(session,id,loader,primary,BridgeCore::receive);
            BridgeEvents.install((target,event)->link.send("event",target,event));
            RemoteItems.initialize(link,primary);
            InventoryBridge.initialize(link.id,primary);
            link.start();
            Runtime.getRuntime().addShutdownHook(new Thread(()->{ link.close(); try { if(poseFile!=null) poseFile.close(); } catch(java.io.IOException ignored) {} },"mmlf-shutdown"));
            System.out.println("MMLF bridge initialized: "+id);
        } catch(Exception e) { System.err.println("MMLF session initialization failed: "+e); }
    }
    private static void receive(Wire message) {
        Minecraft mc=Minecraft.getInstance();
        if(message.type().equals("object")) {updateCollision(message.body(ObjectState.class));
        } else if(message.type().equals("welcome")) {
            Welcome welcome=message.body(Welcome.class); mc.execute(()->{peers.clear(); peers.addAll(welcome.runtimes()); localObjects.clear();});
        } else if(message.type().equals("runtime_joined")) {
            String id=message.data().get("runtime").getAsString(); mc.execute(()->peers.add(id));
        } else if(message.type().equals("runtime_left")) {
            String id=message.data().get("runtime").getAsString(); readyRuntimes.remove(id); mc.execute(()->{peers.remove(id); GuiClient.runtimeLeft(id); RemoteCatalog.forget(id); if(id.equals(session.primary())) GuiHost.primaryLost("primary_left"); RemoteLayer removed=remoteLayers.remove(id); if(removed!=null) removed.close(); collisions.values().removeIf(v->v.owner().equals(id)); if(id.equals(session.primary())) { lastRemotePose=null; poseSequence=0; poseSourceNanos=0; yawRate=pitchRate=0; predictionLead.reset(); adaptiveOverscan.reset(); }});
        } else if(message.type().equals("disconnected")) {
            worldReadySent=false; readyRuntimes.clear();
            mc.execute(()->{GuiClient.disconnected(); GuiHost.primaryLost("disconnected");peers.clear(); collisions.clear(); localObjects.clear(); remoteLayers.values().forEach(RemoteLayer::close); remoteLayers.clear(); displayFrame=null; displayPixels=null; lastRemotePose=null; poseSequence=0; poseSourceNanos=0; yawRate=pitchRate=0; predictionLead.reset(); adaptiveOverscan.reset();});
        } else if(message.type().equals("ray_request")) {
            RayRequest request=message.body(RayRequest.class); mc.execute(()->raycast(request));
        } else if(message.type().equals("interaction")) {
            Interaction action=message.body(Interaction.class); mc.execute(()->interact(action));
        } else if(message.type().equals("interaction_result")) {
            InteractionResult result=message.body(InteractionResult.class); if(primary) mc.execute(()->interactionResult(message.source(),result));
        } else if(message.type().equals("gui")) {
            if(primary) GuiClient.receive(message);
        } else if(message.type().equals("gui_input")) {
            if(!primary && message.source().equals(session.primary())) GuiHost.receive(message.body(GuiInput.class));
        } else if(message.type().equals("inventory")) {
            InventoryBridge.receive(message);
        } else if(message.type().equals("event")) {
            Event event=message.body(Event.class);
            if(event.topic().equals("mmlf:latency_feedback")) {
                if(!primary && message.source().equals(session.primary())) {
                    try { double ms=Double.parseDouble(event.values().get("captureToCompositeMs"));
                        mc.execute(()->predictionLead.observeTransfer(ms,System.nanoTime()));
                    } catch(RuntimeException ignored) { }
                }
                return;
            }
            if(event.topic().equals("mmlf:session_end")) { if(!primary) mc.execute(()->stopBackground(mc,"session_end")); return; }
            if(event.topic().equals("mmlf:world_ready")) { readyRuntimes.add(message.source()); MmlfLog.info("connection","runtime_world_ready",Map.of("runtime",message.source())); return; }
            if(event.topic().equals("mmlf:held")) {if(!primary) { primaryGrounded=!"false".equals(event.values().get("grounded")); mc.execute(()->RemoteItems.updateHeld(event.values().get("item"),event.values().get("slot"))); } return;}
            if(event.topic().equals("mmlf:picked")) {RemoteItems.receive(message);return;}
            if(event.topic().equals("mmlf:catalog_request")) {if(!primary && message.source().equals(session.primary())) mc.execute(()->RemoteCatalog.answer(message.source()));return;}
            if(event.topic().equals("mmlf:catalog")) {if(primary) RemoteCatalog.receive(message.source(),event.values().get("catalog"));return;}
            if(event.topic().equals("mmlf:language")) {if(!primary && message.source().equals(session.primary())) mc.execute(()->{followGameMode(mc,event.values().get("gameMode"));followLanguage(mc,event.values().get("code"),message.source());});return;}
            if(event.topic().equals("mmlf:attack_stop")) {if(!primary && message.source().equals(session.primary())) mc.execute(()->stopMining(mc));return;}
            if(event.topic().equals("mmlf:icon_request")) {if(!primary && message.source().equals(session.primary())) VisualLayers.requestIcons(event.values().get("references"));return;}
            mc.execute(()-> {
                if (mc.getSingleplayerServer()!=null) mc.getSingleplayerServer().execute(()->BridgeEvents.deliver(event));
                else BridgeEvents.deliver(event);
            });
        }
    }
    public static void tick(Minecraft mc) {
        if(link!=null) DemoScene.tick(mc,session,link.id);
        if(link!=null && !primary) {
            if(!backgroundMusicStopped) {
                mc.getSoundManager().stop(null,net.minecraft.sounds.SoundSource.MUSIC);
                backgroundMusicStopped=true;
            }
            // A background runtime has no visible window; it must not outlive the session that started it.
            long now=System.nanoTime();
            if(link.ready()) { everLinked=true; lastLinked=now; }
            else if(everLinked && now-lastLinked>5_000_000_000L) { stopBackground(mc,"session_lost"); return; }
            if(++hostCheckTick%20==0 && HOST!=null && !HOST.isAlive()) { stopBackground(mc,"host_exited"); return; }
        }
        // The shared pose never gates the bridge: links and interaction work while it is retried once a second.
        if(link!=null && poseFile==null && System.nanoTime()-nextPoseOpen>=0) {
            nextPoseOpen=System.nanoTime()+1_000_000_000L;
            try { poseFile=primary ? PoseFile.publisher(session.posePath(),session.token()) : PoseFile.reader(session.posePath(),session.token()); }
            catch(java.io.IOException e) { MmlfLog.sampled("camera","pose_file_unavailable",5000,Map.of("primary",primary,"reason",e.toString())); }
        }
        if(link==null || !link.ready() || mc.player==null || mc.level==null) return;
        if(!worldReadySent) { worldReadySent=true; link.send("event","*",new Event("mmlf:world_ready",Map.of())); }
        // The primary decides when the game pauses; a background that paused on lost focus would stop its server.
        if(!primary && mc.options.pauseOnLostFocus) mc.options.pauseOnLostFocus=false;
        if(primary) { GuiClient.tick(mc); VisualLayers.flushIconRequests(); RemoteCatalog.tick(mc,peers); } else GuiHost.tick(mc);
        InventoryBridge.tick(mc);
        checkPlacements(mc);
        continueMining(mc);
        // Clouds write depth and move with each runtime's own game time: a background's clouds would drift over the primary's.
        if(!primary && mc.options.cloudStatus().get()!=net.minecraft.client.CloudStatus.OFF) mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        // Vsync would hold a background to the monitor's rate regardless of BACKGROUND_FPS.
        if(!primary && mc.options.enableVsync().get()) { mc.options.enableVsync().set(false); mc.getWindow().updateVsync(false); }
        if (!primary && lastRemotePose!=null && mc.level.dimension().location().toString().equals(lastRemotePose.dimension())) {
            Pose p=lastRemotePose;
            mc.player.setPos(p.playerX(),p.playerY(),p.playerZ());
            mc.player.setYRot(p.yaw()); mc.player.setXRot(p.pitch());
            matchPrimaryWindow(mc,p);
            if(mc.getSingleplayerServer()!=null) {
                var server=mc.getSingleplayerServer();
                server.execute(()->{
                    if(!server.getPlayerList().getPlayers().isEmpty()) {
                        var player=server.getPlayerList().getPlayers().getFirst();
                        if(player.level().dimension().location().toString().equals(p.dimension()))
                            player.teleportTo(p.playerX(),p.playerY(),p.playerZ());
                    }
                });
            }
        }
        if (++scanTick % 2 == 0) publishNearby(mc);
        metrics();
        if(primary) {
            int slot=mc.player.getInventory().selected;
            String item=RemoteItems.held(mc.player.getMainHandItem()), held=item+"#"+slot+"#"+mc.player.onGround();long now=System.nanoTime();
            if(!held.equals(lastHeld) || now-heldSent>1_000_000_000L) {
                lastHeld=held;heldSent=now;
                link.send("event","*",new Event("mmlf:held",Map.of("item",item,"slot",Integer.toString(slot),"grounded",Boolean.toString(mc.player.onGround()))));
                MmlfLog.debug("render.items","held_sync",Map.of("remote",RemoteItemRef.parse(item).isPresent(),"slot",slot));
            }
            // Owners run native logic for the same player: its language and game mode follow the primary's, so names,
            // screens and whether a use consumes the item match what the player sees here.
            String language=mc.options.languageCode, mode=mc.gameMode==null?"survival":mc.gameMode.getPlayerMode().getName();
            if(!(language+"|"+mode).equals(lastLanguage) || now-languageSent>5_000_000_000L) {
                lastLanguage=language+"|"+mode;languageSent=now;
                link.send("event","*",new Event("mmlf:language",Map.of("code",language,"gameMode",mode)));
            }

        }
    }
    public static void publishCamera(Camera camera, float worldFov) {
        Minecraft mc=Minecraft.getInstance();
        if(!primary || link==null || !link.ready() || mc.player==null || mc.level==null) return;
        if(!Float.isFinite(worldFov) || worldFov<1 || worldFov>179) {
            MmlfLog.sampled("camera","invalid_world_fov",1000,Map.of("fov",worldFov)); return;
        }
        prepareDisplayFrame();
            Vec3 pos=camera.getPosition();
            int w=mc.getMainRenderTarget().width,h=mc.getMainRenderTarget().height;
            // Interaction and remote camera control never depend on whether the remote layer can be transferred.
            if(!FrameFile.fits(w,h,FrameFile.MAX_PIXELS)) MmlfLog.sampled("render.transfer","window_exceeds_frame_capacity",5000,Map.of("width",w,"height",h,"maxPixels",FrameFile.MAX_PIXELS));
            if(w>0 && h>0 && w<=FrameFile.MAX_EDGE && h<=FrameFile.MAX_EDGE) {
                Pose pose=new Pose(++sequence,0,mc.level.dimension().location().toString(),pos.x,pos.y,pos.z,
                    camera.getYRot(),camera.getXRot(),worldFov,w,h,
                    mc.player.getX(),mc.player.getY(),mc.player.getZ(),
                    new org.joml.Matrix4f().rotation(new org.joml.Quaternionf(camera.rotation()).conjugate()).get(new float[16]),
                    new org.joml.Matrix4f().setPerspective((float)Math.toRadians(worldFov),(float)w/h,0.05f,mc.gameRenderer.getDepthFar()).get(new float[16]));
                if (primaryPose!=null && sameCamera(primaryPose,pose)) {
                    pose=new Pose(primaryPose.cameraId(),pose.epoch(),pose.dimension(),pose.x(),pose.y(),pose.z(),pose.yaw(),pose.pitch(),pose.fov(),pose.width(),pose.height(),pose.playerX(),pose.playerY(),pose.playerZ(),pose.view(),pose.projection());
                }
                primaryPose=pose;
                MmlfLog.sampled("camera","requested",1000,Map.of("cameraId",pose.cameraId(),"x",pose.x(),"y",pose.y(),"z",pose.z(),"yaw",pose.yaw(),"pitch",pose.pitch(),"fov",pose.fov(),"dimension",pose.dimension()));
                if(poseFile!=null) try {
                    if(!poseFile.publish(sequence,System.nanoTime(),pose)) MmlfLog.sampled("camera","pose_publish_busy",1000,Map.of());
                } catch(java.io.IOException | IllegalArgumentException e) { MmlfLog.sampled("camera","pose_publish_failed",1000,Map.of("reason",e.toString())); }
            }
    }
    public static boolean isPrimary() { return primary; }
    private static boolean languageReloading;
    /** Owner: the bridge player plays in the primary player's game mode. */
    private static void followGameMode(Minecraft mc,String mode) {
        var server=mc.getSingleplayerServer(); if(mode==null || server==null) return;
        var type=net.minecraft.world.level.GameType.byName(mode,null); if(type==null) return;
        server.execute(()->{
            if(server.getPlayerList().getPlayers().isEmpty()) return;
            var player=server.getPlayerList().getPlayers().getFirst();
            if(player.gameMode.getGameModeForPlayer()==type) return;
            player.setGameMode(type);
            MmlfLog.info("connection","game_mode_follow",Map.of("mode",mode));
        });
    }
    /** Owner: switches to the primary's language, then sends a fresh catalogue so names and tabs follow. */
    private static void followLanguage(Minecraft mc,String code,String primaryId) {
        if(code==null || languageReloading || code.equals(mc.options.languageCode) || mc.getLanguageManager().getLanguage(code)==null) return;
        MmlfLog.info("connection","language_follow",Map.of("from",mc.options.languageCode,"to",code));
        languageReloading=true;
        mc.getLanguageManager().setSelected(code); mc.options.languageCode=code;
        mc.reloadResourcePacks().whenComplete((ok,error)->mc.execute(()->{
            languageReloading=false;
            if(error!=null) { MmlfLog.error("connection","language_reload_failed",Map.of("exception",error.toString())); return; }
            RemoteCatalog.answer(primaryId);
        }));
    }
    private static Boolean hiddenBackground;
    /**
     * Whether this process is a background runtime whose window should stay hidden. Read straight from the session file,
     * because the window is created before the bridge initializes.
     */
    public static boolean hiddenBackground() {
        if(hiddenBackground==null) {
            boolean hidden=false;
            try {
                String path=System.getProperty("mmlf.session",System.getenv("MMLF_SESSION"));
                if(path!=null && "true".equals(System.getenv("MMLF_HIDE_SECONDARY")))
                    hidden=!Session.read(Path.of(path)).primary().equals(System.getProperty("mmlf.runtime"));
            } catch(Exception ignored) { }
            hiddenBackground=hidden;
        }
        return hiddenBackground;
    }
    /** The process that started this background runtime (session host or launcher), when it told us. */
    private static final ProcessHandle HOST=hostProcess();
    private static int hostCheckTick;
    private static boolean stopping;
    private static ProcessHandle hostProcess() {
        try { String pid=System.getenv("MMLF_HOST_PID"); return pid==null?null:ProcessHandle.of(Long.parseLong(pid)).orElse(null); }
        catch(RuntimeException e) { return null; }
    }
    private static void stopBackground(Minecraft mc,String reason) {
        if(stopping) return; stopping=true;
        MmlfLog.warn("connection","background_stopping",Map.of("reason",reason));
        mc.stop();
    }
    /** Primary, as its window closes: background runtimes stop now instead of waiting to notice the lost link. */
    public static void closing() {
        // Minecraft.stop() runs again on every frame of the closing screen; announce the end once.
        if(primary && !stopping && link!=null && link.ready()) { stopping=true; link.send("event","*",new Event("mmlf:session_end",Map.of())); MmlfLog.info("connection","session_end_sent",Map.of()); }
    }
    /** A background's frame rate; the GTA V passthrough runs its guest uncapped by vsync at 120 for the same reason. */
    public static final int BACKGROUND_FPS=Integer.getInteger("mmlf.backgroundFps",120);
    /** How far ahead of the newest primary pose a background renders its camera; about the remote frame's latency. */
    private static final PredictionLead predictionLead=new PredictionLead(Double.parseDouble(System.getProperty("mmlf.poseLeadMs","25")));
    private static long cameraRenderStart;
    private static double yawRate,pitchRate;
    private static long poseArrived;
    /** Render thread: keeps a smoothed turn rate so a background can aim where the primary will be looking. */
    private static void receivePose(Pose pose, long sourceNanos) {
        long now=System.nanoTime(); Pose previous=lastRemotePose;
        double dt=(sourceNanos-poseSourceNanos)/1e9;
        if(previous!=null && (!previous.dimension().equals(pose.dimension()) || Math.abs(pose.x()-previous.x())>16 || Math.abs(pose.y()-previous.y())>16 || Math.abs(pose.z()-previous.z())>16)) { previous=null; yawRate=0; pitchRate=0; }
        if(previous!=null && dt>0.002 && dt<0.2) {
            double nextYaw=net.minecraft.util.Mth.wrapDegrees(pose.yaw()-previous.yaw())/dt;
            double nextPitch=(pose.pitch()-previous.pitch())/dt;
            yawRate=nextYaw*yawRate<0 ? nextYaw : yawRate+0.5*(nextYaw-yawRate);
            pitchRate=nextPitch*pitchRate<0 ? nextPitch : pitchRate+0.5*(nextPitch-pitchRate);
        } else { yawRate=0; pitchRate=0; }
        poseArrived=now; poseSourceNanos=sourceNanos; lastRemotePose=pose;
    }
    public static void beginCameraRender() {
        pollSharedPose();
        if(!primary) cameraRenderStart=System.nanoTime();
    }
    /** Called on the render thread before camera setup; no cross-process wait is performed. */
    public static void pollSharedPose() {
        if(primary || poseFile==null || link==null || !link.ready() || !peers.contains(session.primary())) return;
        try {
            PoseFile.Snapshot snapshot=poseFile.read();
            if(snapshot!=null && snapshot.sequence()!=poseSequence) {
                if(poseSequence!=0 && snapshot.sequence()>poseSequence+1)
                    MmlfLog.sampled("camera","pose_sequence_gap",1000,Map.of("skipped",snapshot.sequence()-poseSequence-1));
                poseSequence=snapshot.sequence(); receivePose(snapshot.pose(),snapshot.publishedNanos());
            }
            if(lastRemotePose!=null && System.nanoTime()-poseArrived>1_000_000_000L) { lastRemotePose=null; yawRate=pitchRate=0; }
        } catch(java.io.IOException e) { MmlfLog.sampled("camera","pose_read_failed",1000,Map.of("reason",e.toString())); }
    }
    /** Background camera rotation {yaw, pitch}: the primary's, extrapolated by its turn rate (at most 30 degrees). */
    public static float[] predictedRotation(Pose p) {
        long now=System.nanoTime();
        double ahead=(now-poseArrived)/1e9+predictionLead.milliseconds(now)/1000.0;
        double dy=Math.max(-30,Math.min(30,yawRate*ahead)),dp=Math.max(-30,Math.min(30,pitchRate*ahead));
        return new float[]{(float)(p.yaw()+dy),(float)Math.max(-90,Math.min(90,p.pitch()+dp))};
    }
    /** Background view widening (tangent scale) that covers a turn during the remote frame's latency. */
    public static final double OVERSCAN=Double.parseDouble(System.getProperty("mmlf.overscan","1.25"));
    private static final boolean ADAPTIVE_OVERSCAN=Boolean.parseBoolean(System.getProperty("mmlf.adaptiveOverscan","false"));
    private static final AdaptiveOverscan adaptiveOverscan=new AdaptiveOverscan(OVERSCAN);
    private static final SampleWindow overscanSamples=new SampleWindow(2048);
    private static long overscanMetricSince;
    public static double backgroundFov(Pose pose) {
        long now=System.nanoTime();
        double horizon=predictionLead.milliseconds(now)/1000.0+Math.max(0,(now-poseArrived)/1e9);
        double scale=ADAPTIVE_OVERSCAN ? adaptiveOverscan.update(pose.fov(),(double)pose.width()/pose.height(),yawRate,pitchRate,horizon,now) : OVERSCAN;
        if(ADAPTIVE_OVERSCAN) {
            overscanSamples.add(scale);
            if(overscanMetricSince==0) overscanMetricSince=now;
            if(now-overscanMetricSince>=10_000_000_000L) {
                var values=overscanSamples.drain();
                MmlfLog.info("metrics","overscan",Map.of("samples",values.count(),"p50",values.p50(),"p95",values.p95(),"p99",values.p99()));
                overscanMetricSince=now;
            }
        }
        return Math.toDegrees(2*Math.atan(Math.tan(Math.toRadians(pose.fov())/2)*scale));
    }
    /** True once another runtime has a loaded world. */
    public static boolean remoteWorldReady() { return !readyRuntimes.isEmpty(); }
    public static int peerCount() { return peers.size(); }
    /**
     * Background runtimes, every frame before the crosshair pick: the player stands exactly on the primary player, with
     * the previous-position fields set too, so the native ray and model are not a tick of interpolation behind.
     */
    public static void followPrimaryFrame() {
        pollSharedPose();
        Pose p=lastRemotePose; Minecraft mc=Minecraft.getInstance(); var player=mc.player;
        if(primary || p==null || player==null || mc.level==null || !mc.level.dimension().location().toString().equals(p.dimension())) return;
        player.setPos(p.playerX(),p.playerY(),p.playerZ());
        player.xo=player.xOld=p.playerX(); player.yo=player.yOld=p.playerY(); player.zo=player.zOld=p.playerZ();
        player.setYRot(p.yaw()); player.setXRot(p.pitch());
        player.yRotO=p.yaw(); player.xRotO=p.pitch();
        player.yHeadRot=player.yHeadRotO=p.yaw();
    }
    /** True on the primary once a remote world frame has been received; a background runtime's world may load late. */
    public static boolean remoteLayerReady() { return primary && displayFrame!=null; }
    /** Remote runtimes render at the primary framebuffer size so colour, depth and hand pixels share one projection. */
    private static void matchPrimaryWindow(Minecraft mc,Pose p) {
        var target=mc.getMainRenderTarget();
        if(target.width==p.width() && target.height==p.height()) return;
        long now=System.nanoTime(); if(now-lastResize<500_000_000L) return; lastResize=now;
        long window=mc.getWindow().getWindow();
        int[] ww=new int[1],wh=new int[1],fw=new int[1],fh=new int[1];
        org.lwjgl.glfw.GLFW.glfwGetWindowSize(window,ww,wh); org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(window,fw,fh);
        double sx=fw[0]>0?(double)ww[0]/fw[0]:1,sy=fh[0]>0?(double)wh[0]/fh[0]:1;
        int width=(int)Math.round(p.width()*sx),height=(int)Math.round(p.height()*sy);
        MmlfLog.info("render.capture","match_primary_size",Map.of("fromWidth",target.width,"fromHeight",target.height,"toWidth",p.width(),"toHeight",p.height(),"windowWidth",width,"windowHeight",height));
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(window,width,height);
    }
    private static void publishNearby(Minecraft mc) {
        Pose remote=lastRemotePose;
        Vec3 center=(!primary && remote!=null) ? new Vec3(remote.playerX(),remote.playerY(),remote.playerZ()) : mc.player.position();
        int cx=(int)Math.floor(center.x),cy=(int)Math.floor(center.y),cz=(int)Math.floor(center.z);
        String dimension=mc.level.dimension().location().toString();
        for(var entry:new java.util.ArrayList<>(localObjects.entrySet())) {
            ObjectState old=entry.getValue();
            if(!old.dimension().equals(dimension)) {
                link.send("object","*",new ObjectState(old.id(),link.id,old.dimension(),old.x(),old.y(),old.z(),"minecraft:air",old.revision()+1,java.util.Map.of()));
                localObjects.remove(entry.getKey());
            }
        }
        java.util.Set<net.minecraft.core.BlockPos> found=new java.util.HashSet<>();
        for(int x=cx-8;x<=cx+8;x++) for(int y=cy-5;y<=cy+5;y++) for(int z=cz-8;z<=cz+8;z++) {
            net.minecraft.core.BlockPos pos=new net.minecraft.core.BlockPos(x,y,z);
            if(!mc.level.hasChunkAt(pos)) continue;
            var state=mc.level.getBlockState(pos); var key=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if(key==null || state.isAir() || (key.getNamespace().equals("minecraft") && !ownedVanilla.contains(pos))) continue;
            found.add(pos);
            java.util.Map<String,String> properties=new java.util.TreeMap<>();
            state.getValues().forEach((property,value)->properties.put(property.getName(),value.toString()));
            var collision=state.getCollisionShape(mc.level,pos).toAabbs().stream().map(b->new double[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ}).toList();
            properties.put("_collision",Wire.JSON.toJson(collision));
            ObjectState old=localObjects.get(pos);
            if(old==null || !old.dimension().equals(dimension) || !old.type().equals(key.toString()) || !old.state().equals(properties)) {
                ObjectState value=new ObjectState(Messages.objectId(link.id,dimension,x,y,z),link.id,dimension,x,y,z,key.toString(),old==null || !old.dimension().equals(dimension)?1:old.revision()+1,properties);
                localObjects.put(pos,value); link.send("object","*",value);
            }
        }
        for(var entry:new java.util.ArrayList<>(localObjects.entrySet())) {
            var pos=entry.getKey(); var old=entry.getValue();
            if(!old.dimension().equals(dimension)) {
                link.send("object","*",new ObjectState(old.id(),link.id,old.dimension(),old.x(),old.y(),old.z(),"minecraft:air",old.revision()+1,java.util.Map.of()));
                localObjects.remove(pos); continue;
            }
            if(Math.abs(pos.getX()-cx)<=8 && Math.abs(pos.getY()-cy)<=5 && Math.abs(pos.getZ()-cz)<=8 && mc.level.hasChunkAt(pos) && !found.contains(pos)) {
                link.send("object","*",new ObjectState(old.id(),link.id,old.dimension(),old.x(),old.y(),old.z(),"minecraft:air",old.revision()+1,java.util.Map.of()));
                localObjects.remove(pos);
            }
        }
    }
    public static boolean requestInteraction() { return requestInteraction("use"); }
    public static boolean requestInteraction(String action) {
        if(!primary || link==null || !link.ready() || peers.size()<2 || primaryPose==null) return false;
        Minecraft mc=Minecraft.getInstance(); if(mc.player==null) return false;
        if(action.equals("attack") && System.nanoTime()-lastAttack<150_000_000L) return true;
        if(action.equals("attack")) lastAttack=System.nanoTime();
        Pose pose=primaryPose;
        MmlfLog.debug("input","request",Map.of("action",action,"cameraId",pose.cameraId(),"dimension",pose.dimension()));
        var item=RemoteItems.held(mc.player.getMainHandItem());
        String actionId=java.util.UUID.randomUUID().toString();
        if("use".equals(action)) GuiClient.requested(actionId);
        link.send("ray_query","coordinator",new RayRequest(actionId,pose,5,action,item,mc.player.isShiftKeyDown(),Messages.MAIN_HAND,mc.player.getInventory().selected));
        return true;
    }
    private static void raycast(RayRequest request) {
        Minecraft mc=Minecraft.getInstance(); Pose p=request.pose();
        if(mc.level==null || mc.player==null || !mc.level.dimension().location().toString().equals(p.dimension())) { sendMiss(request); return; }
        Vec3 start=new Vec3(p.x(),p.y(),p.z());
        Vec3 end=start.add(Vec3.directionFromRotation(p.pitch(),p.yaw()).scale(request.reach()));
        var hit=mc.level.clip(new net.minecraft.world.level.ClipContext(start,end,net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,mc.player));
        if(hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS) { sendMiss(request); return; }
        ObjectState o=localObjects.get(hit.getBlockPos());
        if(o==null && !primary) {
            var hitType=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock());
            if(hitType.getNamespace().equals("minecraft") && !ownedVanilla.contains(hit.getBlockPos())) {sendMiss(request);return;}
            // Rendered but not yet indexed objects block the ray; never act through them.
        }
        Vec3 point=hit.getLocation(); var pos=hit.getBlockPos();
        RayHit result=new RayHit(request.actionId(),o==null?null:o.id(),o==null?0:o.revision(),start.distanceTo(point),pos.getX(),pos.getY(),pos.getZ(),hit.getDirection().name(),point.x,point.y,point.z,false);
        link.send("ray_hit","coordinator",result);
    }
    private static void sendMiss(RayRequest request) {
        link.send("ray_hit","coordinator",new RayHit(request.actionId(),null,0,0,0,0,0,"UP",0,0,0,true));
    }
    /** Actions already handled here; a repeated routing never uses a block twice. */
    private static final RecentIds handledActions=new RecentIds(512);
    private record PlacementCheck(net.minecraft.core.BlockPos pos,net.minecraft.world.level.block.state.BlockState before,String actionId,long due) {}
    private static final java.util.List<PlacementCheck> placementChecks=new java.util.ArrayList<>();
    private static void interact(Interaction action) {
        Minecraft mc=Minecraft.getInstance(); RayHit hit=action.hit();
        if(!handledActions.firstTime(action.actionId())) { MmlfLog.warn("interaction","duplicate_ignored",Map.of("actionId",action.actionId())); return; }
        var pos=new net.minecraft.core.BlockPos(hit.x(),hit.y(),hit.z()); ObjectState current=localObjects.get(pos);
        if(mc.getSingleplayerServer()==null || mc.player==null || mc.level==null) { result(action,false,"FAIL","runtime_not_ready"); return; }
        if(hit.objectId()!=null && (current==null || !current.id().equals(hit.objectId()) || current.revision()!=hit.revision())) { result(action,false,"FAIL","stale_target"); return; }
        if(!mc.level.dimension().location().toString().equals(action.pose().dimension())) { result(action,false,"FAIL","dimension_mismatch"); return; }
        var itemRef=RemoteItemRef.parse(action.heldItem());
        boolean remotePlacement=itemRef.isPresent() && itemRef.get().owner().equals(link.id) && "use".equals(action.action());
        if(hit.objectId()==null && !primary && !remotePlacement) { result(action,false,"FAIL","unowned_target"); return; }
        // Every use, including placing this runtime's item on a vanilla surface, goes through the native client path with
        // the mirrored stack itself: the use consumes from the real inventory, never from a stand-in copy.
        if("use".equals(action.action())) { useNative(mc,action,pos,current); return; }
        if("attack".equals(action.action())) { mine(mc,action,pos,current); return; }
        var server=mc.getSingleplayerServer();
        server.execute(()->{
            if(server.getPlayerList().getPlayers().isEmpty()) { result(action,false,"FAIL","no_player"); return; }
            var player=server.getPlayerList().getPlayers().getFirst();
            if(!player.level().dimension().location().toString().equals(action.pose().dimension())) { result(action,false,"FAIL","dimension_mismatch"); return; }
            var actual=player.level().getBlockState(pos);
            var type=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(actual.getBlock());
            if(type==null || (current!=null && !type.toString().equals(current.type()))) { result(action,false,"FAIL","stale_target"); return; }
            if("pick".equals(action.action())) {RemoteItems.pick(player,pos);result(action,true,"PICK",null);return;}
            result(action,false,"FAIL","unsupported_action");
        });
    }
    /**
     * Uses a block the way this runtime's own player would: the client game mode runs the client-side logic (where some
     * mods open their screens) and sends the use packet, which the integrated server validates and executes on its own
     * thread. The held stack is the mirrored inventory's, never a stack substituted for the occasion.
     */
    private static void useNative(Minecraft mc,Interaction action,net.minecraft.core.BlockPos pos,ObjectState current) {
        RayHit hit=action.hit(); var player=mc.player;
        var state=mc.level.getBlockState(pos); var type=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if(current!=null && !type.toString().equals(current.type())) { result(action,false,"FAIL","stale_target"); return; }
        var hand=Messages.OFF_HAND.equals(action.handOrMain()) ? net.minecraft.world.InteractionHand.OFF_HAND : net.minecraft.world.InteractionHand.MAIN_HAND;
        if(action.selectedSlot()>=0 && action.selectedSlot()<9) player.getInventory().selected=action.selectedSlot();
        String problem=InventoryBridge.heldProblem(player.getItemInHand(hand),action.heldItem());
        if(problem!=null) { MmlfLog.warn("interaction","held_item_rejected",Map.of("actionId",action.actionId(),"reason",problem,"expected",String.valueOf(action.heldItem()))); result(action,false,"FAIL",problem); return; }
        var nativeHit=new net.minecraft.world.phys.BlockHitResult(new Vec3(hit.hitX(),hit.hitY(),hit.hitZ()),net.minecraft.core.Direction.valueOf(hit.face()),pos,false);
        var adjacent=pos.relative(nativeHit.getDirection());
        var before=mc.level.getBlockState(adjacent);
        var connection=mc.getConnection();
        boolean shifted=player.isShiftKeyDown();
        if(!primary) GuiHost.noteInteraction(action.actionId());
        try {
            // The server reads sneaking from command packets, which this runtime never sends from its own input.
            if(connection!=null) connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(player,action.secondaryUse()?net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY:net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
            player.setShiftKeyDown(action.secondaryUse());
            var outcome=mc.gameMode.useItemOn(player,hand,nativeHit);
            if(outcome.shouldSwing()) player.swing(hand);
            boolean success=outcome.consumesAction();
            MmlfLog.info("interaction","native_result",Map.of("actionId",action.actionId(),"action","use","path","client","result",outcome.name(),"position",pos.toShortString(),"state",state.toString(),"screen",mc.screen==null?"none":mc.screen.getClass().getName()));
            result(action,success,outcome.name(),null);
            placementChecks.add(new PlacementCheck(adjacent.immutable(),before,action.actionId(),System.nanoTime()+150_000_000L));
            System.out.println("MMLF INTERACTION runtime="+link.id+" action=use path=client result="+outcome+" state="+state);
        } finally {
            player.setShiftKeyDown(shifted);
            if(connection!=null && action.secondaryUse()!=shifted) connection.send(new net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket(player,shifted?net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY:net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY));
        }
    }
    /**
     * Breaking as this runtime's own player does: the client game mode starts the block and continues it every tick
     * while the primary keeps the attack button down (it repeats the request about every 200 ms), so survival mining
     * time, tools, cracks, drops and sounds are all native. Without a repeat the mining stops, as when a player lets go.
     */
    private static net.minecraft.core.BlockPos miningPos;
    private static net.minecraft.core.Direction miningFace;
    private static long miningUntil;

    private static void mine(Minecraft mc,Interaction action,net.minecraft.core.BlockPos pos,ObjectState current) {
        var state=mc.level.getBlockState(pos); var type=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if(state.isAir() || current!=null && !type.toString().equals(current.type())) { result(action,false,"FAIL","stale_target"); return; }
        if(action.selectedSlot()>=0 && action.selectedSlot()<9) mc.player.getInventory().selected=action.selectedSlot();
        var face=net.minecraft.core.Direction.valueOf(action.hit().face());
        boolean started=false;
        if(!pos.equals(miningPos) || !mc.gameMode.isDestroying()) {
            if(miningPos!=null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
            started=mc.gameMode.startDestroyBlock(pos,face); miningPos=pos.immutable(); miningFace=face;
        }
        miningUntil=System.nanoTime()+600_000_000L;
        mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        if(started) MmlfLog.info("interaction","native_result",Map.of("actionId",action.actionId(),"action","attack","path","client","position",pos.toShortString(),"state",state.toString()));
        result(action,true,"MINING",null);
    }
    /** Primary: the attack button was let go; owners stop breaking now instead of waiting for the repeat to lapse. */
    /** Set by the self-test while it holds attack through requests rather than the (unfocused) mouse. */
    static boolean selfTestAttacking;
    public static void attackReleased() {
        if(!primary || link==null || lastAttack==0 || selfTestAttacking) return;
        lastAttack=0;
        link.send("event","*",new Event("mmlf:attack_stop",Map.of()));
        Minecraft mc=Minecraft.getInstance(); stopMining(mc);
    }
    private static void stopMining(Minecraft mc) {
        if(miningPos!=null && mc.gameMode!=null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
        miningPos=null;
    }
    /**
     * A background's player only follows the primary's position, so its own physics never lands it. Breaking speed,
     * and the server's check of a finished break, depend on standing on the ground: the primary player's state is used.
     */
    private static volatile boolean primaryGrounded=true;
    public static Boolean followedGround(net.minecraft.world.entity.Entity entity) {
        if(primary || link==null || !link.ready()) return null;
        return entity==Minecraft.getInstance().player ? primaryGrounded : null;
    }
    /** True while a background breaks a block for the primary. */
    public static boolean miningForPrimary() { return !primary && miningPos!=null; }
    private static void continueMining(Minecraft mc) {
        if(miningPos==null || mc.gameMode==null || mc.level==null) return;
        if(System.nanoTime()-miningUntil>0 || mc.level.getBlockState(miningPos).isAir()) { stopMining(mc); return; }
        if(mc.gameMode.continueDestroyBlock(miningPos,miningFace)) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        MmlfLog.sampled("interaction","mining",250,Map.of("position",miningPos.toShortString(),"progress",((dev.mmlf.bridge.mixin.GameModeAccessor)mc.gameMode).mmlf$destroyProgress(),
            "destroying",mc.gameMode.isDestroying(),"onGround",mc.player.onGround(),"speed",mc.level.getBlockState(miningPos).getDestroyProgress(mc.player,mc.level,miningPos)));
    }
    /** Blocks the server placed after a native use belong to this runtime, like placements through the server path. */
    private static void checkPlacements(Minecraft mc) {
        if(placementChecks.isEmpty() || mc.getSingleplayerServer()==null) return;
        long now=System.nanoTime(); var server=mc.getSingleplayerServer();
        for(var it=placementChecks.iterator();it.hasNext();) {
            PlacementCheck check=it.next(); if(now-check.due()<0) continue; it.remove();
            server.execute(()->{
                if(server.getPlayerList().getPlayers().isEmpty()) return;
                var level=server.getPlayerList().getPlayers().getFirst().level(); var after=level.getBlockState(check.pos());
                if(!after.equals(check.before())) { ownedVanilla.add(check.pos()); MmlfLog.info("items","native_placement",Map.of("actionId",check.actionId(),"position",check.pos().toShortString(),"state",after.toString())); }
            });
        }
    }
    private static void result(Interaction action,boolean success,String outcome,String reason) {
        if(primary || link==null) return;
        link.send("interaction_result",session.primary(),new InteractionResult(action.actionId(),link.id,success,outcome,GuiHost.instanceFor(action.actionId()),reason));
    }
    /** Primary: an owner's answer. Success gives the usual arm swing; a failure is shown instead of silently ignored. */
    private static void interactionResult(String source,InteractionResult result) {
        Minecraft mc=Minecraft.getInstance();
        MmlfLog.debug("interaction","result",Map.of("actionId",result.actionId(),"owner",source,"success",result.success(),"result",String.valueOf(result.result()),"reason",String.valueOf(result.reason())));
        if(mc.player==null) return;
        if(result.success()) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        else if(result.reason()!=null) mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal("MMLF: "+source+" "+result.reason()),false);
    }
    private static boolean sameCamera(Pose a,Pose b) {
        return a.dimension().equals(b.dimension()) && a.width()==b.width() && a.height()==b.height()
            && Math.abs(a.x()-b.x())<1e-5 && Math.abs(a.y()-b.y())<1e-5 && Math.abs(a.z()-b.z())<1e-5
            && Math.abs(a.yaw()-b.yaw())<1e-4 && Math.abs(a.pitch()-b.pitch())<1e-4 && a.fov()==b.fov();
    }
    private static void metrics() {
        long now=System.nanoTime();
        if(metricAt==0) { metricAt=now; previousCpu=ProcessHandle.current().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos(); return; }
        if(now-metricAt<java.util.concurrent.TimeUnit.SECONDS.toNanos(10)) return;
        long cpu=ProcessHandle.current().info().totalCpuDuration().orElse(java.time.Duration.ZERO).toNanos();
        double seconds=(now-metricAt)/1e9;
        var memory=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String,Object> measurements=new java.util.LinkedHashMap<>();
        measurements.put("cpuOneCorePercent",100.0*(cpu-previousCpu)/(now-metricAt)); measurements.put("heapUsedMb",memory.getUsed()/1048576.0);
        measurements.put("worldFramesPerSecond",metricFrames/seconds); measurements.put("messageRttMs",link.rttMillis);
        try {var rss=ProcessMemory.residentMb();if(rss.isPresent()) measurements.put("rssMb",rss.getAsDouble());else measurements.put("rssAvailable",false);} catch(LinkageError | RuntimeException e) {measurements.put("rssAvailable",false);MmlfLog.sampled("metrics","rss_unavailable",60000,Map.of("exception",e.getClass().getName()));}
        MmlfLog.info("metrics","runtime",measurements);
        metricFrames=0; metricAt=now; previousCpu=cpu;
    }
    public static void captureFrame() {
        metricFrames++;
        if (primary) { if(CPU_COMPOSITOR) composite(); else gpuComposite(); return; }
        Pose pose=lastRemotePose;
        long captureNow=System.nanoTime();
        if(cameraRenderStart!=0) { predictionLead.observeRender((captureNow-cameraRenderStart)/1e6); cameraRenderStart=0; }
        Minecraft mc=Minecraft.getInstance();
        if(link==null || !link.ready() || pose==null || mc.level==null) return;
        try {
            if(frames==null) frames=FrameFile.writer(session.framePath(link.id));
            if(!mc.level.dimension().location().toString().equals(pose.dimension())) { FrameExporter.drain(frames,link.id,false); return; }
            var target=mc.getMainRenderTarget();
            var camera=mc.gameRenderer.getMainCamera(); Vec3 at=camera.getPosition();
            // The frame carries the camera it was actually rendered with, so the primary can re-project it exactly.
            Pose rendered=new Pose(pose.cameraId(),pose.epoch(),pose.dimension(),at.x,at.y,at.z,camera.getYRot(),camera.getXRot(),pose.fov(),target.width,target.height,
                pose.playerX(),pose.playerY(),pose.playerZ(),
                new org.joml.Matrix4f().rotation(new org.joml.Quaternionf(camera.rotation()).conjugate()).get(new float[16]),
                new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix()).get(new float[16]));
            MmlfLog.sampled("camera","capture_error",1000,Map.of("positionError",at.distanceTo(new Vec3(pose.x(),pose.y(),pose.z())),"yawErrorDegrees",net.minecraft.util.Mth.wrapDegrees(camera.getYRot()-pose.yaw()),"pitchErrorDegrees",camera.getXRot()-pose.pitch()));
            FrameExporter.capture(target,rendered,frames,link.id,sessionIdentity,poseSequence,link.calibration());
        } catch(Exception e) { MmlfLog.sampled("render.capture","failed",1000,Map.of("exception",e.toString())); }
    }
    /** The previous CPU compositor stays available for comparison: -Dmmlf.compositor=cpu. */
    private static final boolean CPU_COMPOSITOR="cpu".equalsIgnoreCase(System.getProperty("mmlf.compositor"));
    /** A remote layer older than this is hidden rather than left frozen on screen. */
    private static final long STALE_NANOS=1_000_000_000L;
    private static void gpuComposite() {
        prepareDisplayFrame();
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || link==null) return;
        var target=mc.getMainRenderTarget();
        for(RemoteLayer layer:remoteLayers.values()) {
            Frame frame=layer.displayed; FrameFile.Pixels pixels=layer.pixels;
            if(frame==null || pixels==null || frame.pose()==null) continue;
            if(!mc.level.dimension().location().toString().equals(frame.pose().dimension())) continue;
            if(!"opengl-window-depth".equals(frame.depthConvention()) || !frame.bottomUp()) continue;
            FrameTiming.Age age=FrameTiming.age(frame,link.calibration(),System.nanoTime());
            if(age==null || age.estimatedNanos()+age.uncertaintyNanos()>STALE_NANOS) {
                MmlfLog.sampled("render.composite","stale_or_uncalibrated",1000,Map.of("frameId",frame.frameId())); continue;
            }
            long metricNow=System.nanoTime();
            layer.frameAgeMs.add(Math.max(0,age.estimatedNanos()/1e6));
            var current=mc.gameRenderer.getMainCamera();
            double dy=net.minecraft.util.Mth.wrapDegrees(current.getYRot()-frame.pose().yaw());
            double dp=current.getXRot()-frame.pose().pitch();
            layer.angularErrorDegrees.add(Math.hypot(dy,dp));
            if(metricNow-layer.feedbackSince>=1_000_000_000L) {
                link.send("event",frame.runtime(),new Event("mmlf:latency_feedback",Map.of("captureToCompositeMs",Double.toString(Math.max(0,age.estimatedNanos()/1e6)))));
                layer.feedbackSince=metricNow;
            }
            if(layer.metricSince==0) layer.metricSince=metricNow;
            if(metricNow-layer.metricSince>=10_000_000_000L) {
                var ages=layer.frameAgeMs.drain(); var angles=layer.angularErrorDegrees.drain();
                MmlfLog.info("metrics","remote_frame",Map.of("runtime",frame.runtime(),"samples",ages.count(),
                    "ageP50Ms",ages.p50(),"ageP95Ms",ages.p95(),"ageP99Ms",ages.p99(),
                    "angularP50Degrees",angles.p50(),"angularP95Degrees",angles.p95(),"angularP99Degrees",angles.p99(),
                    "clockUncertaintyMs",age.uncertaintyNanos()/1e6));
                layer.metricSince=metricNow;
            }
            RemoteCompositor.draw(frame,pixels,current,target.width,target.height);
            if(!compositeReported) {compositeReported=true;System.out.println("MMLF COMPOSITE READY remote="+frame.runtime()+" mode=gpu");}
        }
    }
    private static void ensureBuffers(int n) {
        if(colorBuffer==null || colorBuffer.capacity()<n) {
            colorBuffer=org.lwjgl.BufferUtils.createByteBuffer(n);
            depthBuffer=org.lwjgl.BufferUtils.createByteBuffer(n).order(java.nio.ByteOrder.nativeOrder());
        }
        colorBuffer.clear().limit(n); depthBuffer.clear().limit(n);
    }
    private static void prepareDisplayFrame() {
        if(link==null || session==null) return;
        // Backgrounds publish frames with their metadata in shared slots; the primary polls each peer's file.
        for(String peer:peers) if(!peer.equals(link.id)) remoteLayers.computeIfAbsent(peer,id->new RemoteLayer());
        for(var entry:remoteLayers.entrySet()) {
            RemoteLayer layer=entry.getValue(); String runtime=entry.getKey();
            try {
                if(layer.file==null) {
                    long now=System.nanoTime(); if(now-layer.nextOpen<0) continue;
                    layer.nextOpen=now+500_000_000L;
                    // The background creates its file on its first capture; until then there is nothing to poll.
                    try { layer.file=FrameFile.reader(session.framePath(runtime)); }
                    catch(java.io.IOException e) { MmlfLog.sampled("render.transfer","frame_file_unavailable",5000,Map.of("runtime",runtime,"reason",e.toString())); continue; }
                }
                FrameFile.Latest latest=layer.file.readLatest(layer.cursor,layer.spare);
                if(latest==null) continue;
                layer.cursor=latest;
                Frame frame=Wire.JSON.fromJson(new String(latest.metadata(),java.nio.charset.StandardCharsets.UTF_8),Frame.class);
                if(frame==null || !runtime.equals(frame.runtime()) || !sessionIdentity.equals(frame.sessionId()) || frame.frameId()!=latest.frameId()
                    || frame.width()!=latest.width() || frame.height()!=latest.height() || frame.cameraSequence()<=0 || frame.pose()==null
                    || !"opengl-window-depth".equals(frame.depthConvention())) {
                    MmlfLog.sampled("render.transfer","rejected_metadata",1000,Map.of("runtime",runtime,"frameId",latest.frameId())); continue;
                }
                layer.spare=layer.pixels; layer.displayed=frame; layer.pixels=latest.pixels();
                MmlfLog.sampled("render.transfer","read_complete",1000,Map.of("runtime",runtime,"frameId",frame.frameId()));
            } catch(Exception e) { MmlfLog.sampled("render.transfer","failed",1000,Map.of("runtime",runtime,"exception",e.getClass().getName(),"message",String.valueOf(e.getMessage()))); }
        }
        displayFrame=null; displayPixels=null;
        for(RemoteLayer layer:remoteLayers.values()) if(layer.displayed!=null) { displayFrame=layer.displayed; displayPixels=layer.pixels; break; }
    }
    private static void composite() {
        Frame frame=displayFrame; Pose pose=frame==null?null:frame.pose(); Minecraft mc=Minecraft.getInstance();
        if (frame==null || pose==null || mc.level==null || link==null || !FrameTiming.isFresh(frame,link.calibration(),System.nanoTime(),STALE_NANOS) || frame.pose().cameraId()!=pose.cameraId()
            || !sameCamera(frame.pose(),pose) || !frame.depthConvention().equals("opengl-window-depth")) return;
        Camera camera=mc.gameRenderer.getMainCamera(); Vec3 cp=camera.getPosition();
        boolean aligned=Math.abs(cp.x-pose.x())<1e-5 && Math.abs(cp.y-pose.y())<1e-5 && Math.abs(cp.z-pose.z())<1e-5
            && Math.abs(camera.getYRot()-pose.yaw())<1e-4 && Math.abs(camera.getXRot()-pose.pitch())<1e-4;
        int width=mc.getMainRenderTarget().width,height=mc.getMainRenderTarget().height;
        if(width!=frame.width() || height!=frame.height() || !frame.bottomUp()) {MmlfLog.sampled("render.composite","size_mismatch",1000,Map.of("width",width,"height",height,"frameWidth",frame.width(),"frameHeight",frame.height()));return;}
        try (PixelTransfer transfer=new PixelTransfer()) {
            FrameFile.Pixels remote=displayPixels; if(remote==null) return;
            int n=width*height*4;
            ensureBuffers(n);
            java.nio.ByteBuffer colors=colorBuffer, depths=depthBuffer;
            org.lwjgl.opengl.GL11.glReadPixels(0,0,width,height,org.lwjgl.opengl.GL11.GL_RGBA,org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,colors);
            org.lwjgl.opengl.GL11.glReadPixels(0,0,width,height,org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT,org.lwjgl.opengl.GL11.GL_FLOAT,depths);
            java.nio.ByteBuffer rd=remote.depth().order(java.nio.ByteOrder.nativeOrder()), rc=remote.color();
            long composeStarted=System.nanoTime();
            int changed;
            if(aligned) changed=PixelCompositor.composite(colors,depths,rc,rd,width*height);
            else {
                var oldView=new org.joml.Matrix4f().set(pose.view()).translate((float)-pose.x(),(float)-pose.y(),(float)-pose.z());
                var oldInverse=new org.joml.Matrix4f().set(pose.projection()).mul(oldView).invert();
                var newView=new org.joml.Matrix4f().rotation(new org.joml.Quaternionf(camera.rotation()).conjugate()).translate((float)-cp.x,(float)-cp.y,(float)-cp.z);
                var transform=new org.joml.Matrix4f().set(primaryPose.projection()).mul(newView).mul(oldInverse);
                changed=DepthReprojection.composite(colors,depths,rc,rd,width,height,transform.get(new float[16]));
                MmlfLog.sampled("camera","remote_reprojected",1000,Map.of("sourceCameraId",pose.cameraId(),"currentCameraId",primaryPose.cameraId(),"pixels",changed));
            }
            MmlfLog.sampled("render.composite","frame",1000,Map.of("frameId",frame.frameId(),"cameraId",pose.cameraId(),"changedPixels",changed,"cpuMs",(System.nanoTime()-composeStarted)/1e6,"captureToCompositeEstimateMs",Math.max(0,FrameTiming.age(frame,link.calibration(),System.nanoTime()).estimatedNanos()/1e6)));
            if(changed>0 && !compositeReported) {compositeReported=true;System.out.println("MMLF COMPOSITE READY remote="+frame.runtime()+" pixels="+changed);}
            int binding=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,mc.getMainRenderTarget().getColorTextureId());
            org.lwjgl.opengl.GL11.glTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,0,0,0,width,height,org.lwjgl.opengl.GL11.GL_RGBA,org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,colors);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,binding);
        } catch(Exception e) { MmlfLog.error("render.composite","failed",Map.of("exception",e.getClass().getName(),"message",String.valueOf(e.getMessage()))); }
    }
    public static Pose renderedPose() { return primary ? primaryPose : lastRemotePose; }
    public static Pose lastRemotePose() {
        if(primary) { prepareDisplayFrame(); return displayFrame==null?null:displayFrame.pose(); }
        return lastRemotePose;
    }
    public static boolean connected() { return link!=null && link.ready(); }
}
