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
    private static FrameFile frames;
    private static long frameSequence,lastWorldCapture;
    private static volatile Frame remoteFrame;
    private static Frame displayFrame;
    private static FrameFile.Pixels displayPixels,sparePixels;
    private static FrameFile remoteFrames;
    private static String remoteFrameOwner;
    private static Pose primaryPose;
    private static final java.util.Set<String> peers = new java.util.HashSet<>();
    private static final java.util.Map<net.minecraft.core.BlockPos,ObjectState> localObjects = new java.util.HashMap<>();
    private static int scanTick;
    private static long metricAt, metricFrames, previousCpu;
    private static boolean compositeReported;
    private static final java.util.Set<net.minecraft.core.BlockPos> ownedVanilla=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Boolean> nativeInteraction=ThreadLocal.withInitial(()->false);
    private static long lastAttack;
    private static String lastHeld="";
    private static long heldSent,lastResize;
    public static Session session() {return session;}
    public static String runtimeId() {return link==null?"unknown":link.id;}

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
    public static boolean renderOwned(net.minecraft.world.level.block.state.BlockState state,net.minecraft.core.BlockPos pos) {
        return primary || !connected() || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft") || ownedVanilla.contains(pos);
    }
    public static void captureSound(double x,double y,double z,net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound,net.minecraft.sounds.SoundSource source,float volume,float pitch) {
        if(!nativeInteraction.get() || link==null) return;
        MmlfLog.debug("audio","native_sound",Map.of("sound",sound.value().getLocation().toString(),"source",source.name(),"x",x,"y",y,"z",z,"volume",volume,"pitch",pitch));
        if(primary) { var mc=Minecraft.getInstance(); mc.execute(()->{ if(mc.level!=null) mc.level.playLocalSound(x,y,z,sound.value(),source,volume,pitch,false); }); return; }
        link.send("event","*",new Event("mmlf:sound",Map.of("id",sound.value().getLocation().toString(),"source",source.name(),"x",Double.toString(x),"y",Double.toString(y),"z",Double.toString(z),"volume",Float.toString(volume),"pitch",Float.toString(pitch))));
    }
    private static java.nio.ByteBuffer colorBuffer, depthBuffer;
    private BridgeCore() {}
    public static void initialize(String loader) {
        String sessionPath=System.getProperty("mmlf.session", System.getenv("MMLF_SESSION"));
        if (sessionPath==null || sessionPath.isBlank()) {
            System.out.println("MMLF: no session configured; bridge inactive"); return;
        }
        try {
            session=Session.read(Path.of(sessionPath));
            String id=System.getProperty("mmlf.runtime",loader);
            primary=Boolean.parseBoolean(System.getProperty("mmlf.primary", "false"));
            link=new RuntimeClient(session,id,loader,primary,BridgeCore::receive);
            BridgeEvents.install((target,event)->link.send("event",target,event));
            RemoteItems.initialize(link,primary);
            link.start();
            Runtime.getRuntime().addShutdownHook(new Thread(()->link.close(),"mmlf-shutdown"));
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
            String id=message.data().get("runtime").getAsString(); mc.execute(()->{peers.remove(id);collisions.values().removeIf(v->v.owner().equals(id));});
        } else if(message.type().equals("disconnected")) {
            mc.execute(()->{peers.clear(); collisions.clear(); localObjects.clear(); remoteFrame=null; displayFrame=null; displayPixels=null; lastRemotePose=null;});
        } else if(message.type().equals("ray_request")) {
            RayRequest request=message.body(RayRequest.class); mc.execute(()->raycast(request));
        } else if(message.type().equals("interaction")) {
            Interaction action=message.body(Interaction.class); mc.execute(()->interact(action));
        } else if(message.type().equals("event")) {
            Event event=message.body(Event.class);
            if(event.topic().equals("mmlf:visual")) {if(primary) mc.execute(()->VisualLayers.receive(event,message.source()));return;}
            if(event.topic().equals("mmlf:held")) {if(!primary) mc.execute(()->RemoteItems.updateHeld(event.values().get("item")));return;}
            if(event.topic().equals("mmlf:picked")) {RemoteItems.receive(message);return;}
            if(event.topic().equals("mmlf:feedback")) { MmlfLog.debug("input.feedback","received",event.values()); if(primary) mc.execute(()->{ if(mc.player!=null) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND); }); return; }
            if(event.topic().equals("mmlf:sound")) { MmlfLog.debug("audio","received",event.values());
                if(primary) mc.execute(()->{ var v=event.values(); if(mc.level!=null) mc.level.playLocalSound(Double.parseDouble(v.get("x")),Double.parseDouble(v.get("y")),Double.parseDouble(v.get("z")),net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.parse(v.get("id"))),net.minecraft.sounds.SoundSource.valueOf(v.get("source")),Float.parseFloat(v.get("volume")),Float.parseFloat(v.get("pitch")),false); });
                return;
            }
            mc.execute(()-> {
                if (mc.getSingleplayerServer()!=null) mc.getSingleplayerServer().execute(()->BridgeEvents.deliver(event));
                else BridgeEvents.deliver(event);
            });
        } else if(message.type().equals("frame") && primary) {
            Frame frame=message.body(Frame.class);
            if (!message.source().equals(frame.runtime())) return;
            mc.execute(()->remoteFrame=frame);
        } else if(message.type().equals("pose")) {
            Pose pose=message.body(Pose.class);
            mc.execute(()->lastRemotePose=pose);
        }
    }
    public static void tick(Minecraft mc) {
        if(link!=null) DemoScene.tick(mc,session,link.id);
        if(link==null || !link.ready() || mc.player==null || mc.level==null) return;
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
            String held=RemoteItems.held(mc.player.getMainHandItem());long now=System.nanoTime();
            if(!held.equals(lastHeld) || now-heldSent>1_000_000_000L) {
                lastHeld=held;heldSent=now;
                link.send("event","*",new Event("mmlf:held",Map.of("item",held)));
                MmlfLog.debug("render.items","held_sync",Map.of("remote",RemoteItemRef.parse(held).isPresent()));
            }

        }
    }
    public static void publishCamera(Camera camera) {
        Minecraft mc=Minecraft.getInstance();
        if(!primary || link==null || !link.ready() || mc.player==null || mc.level==null) return;
        prepareDisplayFrame();
            Vec3 pos=camera.getPosition();
            int w=mc.getMainRenderTarget().width,h=mc.getMainRenderTarget().height;
            // Interaction and remote camera control never depend on whether the remote layer can be transferred.
            if(!FrameFile.fits(w,h,FrameFile.MAX_PIXELS)) MmlfLog.sampled("render.transfer","window_exceeds_frame_capacity",5000,Map.of("width",w,"height",h,"maxPixels",FrameFile.MAX_PIXELS));
            if(w>0 && h>0 && w<=FrameFile.MAX_EDGE && h<=FrameFile.MAX_EDGE) {
                Pose pose=new Pose(++sequence,0,mc.level.dimension().location().toString(),pos.x,pos.y,pos.z,
                    camera.getYRot(),camera.getXRot(),mc.options.fov().get().floatValue(),w,h,
                    mc.player.getX(),mc.player.getY(),mc.player.getZ(),
                    new org.joml.Matrix4f().rotation(new org.joml.Quaternionf(camera.rotation()).conjugate()).get(new float[16]),
                    new org.joml.Matrix4f().setPerspective((float)Math.toRadians(mc.options.fov().get()),(float)w/h,0.05f,mc.gameRenderer.getDepthFar()).get(new float[16]));
                if (primaryPose!=null && sameCamera(primaryPose,pose)) {
                    pose=new Pose(primaryPose.cameraId(),pose.epoch(),pose.dimension(),pose.x(),pose.y(),pose.z(),pose.yaw(),pose.pitch(),pose.fov(),pose.width(),pose.height(),pose.playerX(),pose.playerY(),pose.playerZ(),pose.view(),pose.projection());
                }
                primaryPose=pose;
                MmlfLog.sampled("camera","requested",1000,Map.of("cameraId",pose.cameraId(),"x",pose.x(),"y",pose.y(),"z",pose.z(),"yaw",pose.yaw(),"pitch",pose.pitch(),"fov",pose.fov(),"dimension",pose.dimension()));
                link.send("pose","*",pose);
            }
    }
    public static boolean isPrimary() { return primary; }
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
        if(action.equals("attack") && System.nanoTime()-lastAttack<200_000_000L) return true;
        if(action.equals("attack")) lastAttack=System.nanoTime();
        Pose pose=primaryPose;
        MmlfLog.debug("input","request",Map.of("action",action,"cameraId",pose.cameraId(),"dimension",pose.dimension()));
        var item=RemoteItems.held(mc.player.getMainHandItem());
        link.send("ray_query","coordinator",new RayRequest(java.util.UUID.randomUUID().toString(),pose,5,action,item,mc.player.isShiftKeyDown()));
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
    private static void interact(Interaction action) {
        Minecraft mc=Minecraft.getInstance(); RayHit hit=action.hit();
        var pos=new net.minecraft.core.BlockPos(hit.x(),hit.y(),hit.z()); ObjectState current=localObjects.get(pos);
        if(mc.getSingleplayerServer()==null) return;
        if(hit.objectId()!=null && (current==null || !current.id().equals(hit.objectId()) || current.revision()!=hit.revision())) return;
        var itemRef=RemoteItemRef.parse(action.heldItem());
        boolean remotePlacement=itemRef.isPresent() && itemRef.get().owner().equals(link.id) && "use".equals(action.action());
        if(hit.objectId()==null && !primary && !remotePlacement) return;
        var server=mc.getSingleplayerServer();
        server.execute(()->{
            if(server.getPlayerList().getPlayers().isEmpty()) return;
            var player=server.getPlayerList().getPlayers().getFirst();
            if(!player.level().dimension().location().toString().equals(action.pose().dimension())) return;
            var actual=player.level().getBlockState(pos);
            var type=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(actual.getBlock());
            if(type==null || (current!=null && !type.toString().equals(current.type()))) return;
            if(hit.objectId()==null && remotePlacement && !type.getNamespace().equals("minecraft")) return;
            if("pick".equals(action.action())) {RemoteItems.pick(player,pos);return;}
            var nativeHit=new net.minecraft.world.phys.BlockHitResult(new Vec3(hit.hitX(),hit.hitY(),hit.hitZ()),net.minecraft.core.Direction.valueOf(hit.face()),pos,false);
            var previous=player.getMainHandItem(); boolean shifted=player.isShiftKeyDown();
            var adjacent=pos.relative(nativeHit.getDirection()); var before=player.level().getBlockState(adjacent);
            nativeInteraction.set(true);
            try {
                boolean success;
                if("attack".equals(action.action())) {
                    success=player.gameMode.destroyBlock(pos);
                    if(success) mc.execute(()->{
                        if(mc.level!=null) {mc.level.levelEvent(2001,pos,net.minecraft.world.level.block.Block.getId(actual));MmlfLog.info("particles","native_break",Map.of("block",type.toString(),"position",pos.toShortString()));}
                    });
                }
                else {
                    var stack=RemoteItems.resolve(action.heldItem());
                    if(stack==null) {MmlfLog.warn("items","unavailable_reference",Map.of("actionId",action.actionId()));return;}
                    player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,stack); player.setShiftKeyDown(action.secondaryUse());
                    success=player.gameMode.useItemOn(player,player.level(),stack,net.minecraft.world.InteractionHand.MAIN_HAND,nativeHit).consumesAction();
                }
                if(!player.level().getBlockState(adjacent).equals(before)) {
                    ownedVanilla.add(adjacent.immutable());
                    MmlfLog.info("items","native_placement",Map.of("actionId",action.actionId(),"position",adjacent.toShortString(),"state",player.level().getBlockState(adjacent).toString()));
                }
                MmlfLog.info("interaction","native_result",Map.of("actionId",action.actionId(),"action",action.action()==null?"use":action.action(),"success",success,"position",pos.toShortString(),"state",player.level().getBlockState(pos).toString()));
                if(success) {
                    if(primary) mc.execute(()->mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND));
                    else { mc.execute(()->{if(mc.player!=null) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);});link.send("event","*",new Event("mmlf:feedback",Map.of("actionId",action.actionId()))); }
                }
                System.out.println("MMLF INTERACTION runtime="+link.id+" action="+action.action()+" success="+success+" state="+player.level().getBlockState(pos));
            } finally { nativeInteraction.remove(); player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,previous); player.setShiftKeyDown(shifted); }
        });
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
        if (primary) { composite(); return; }
        long captureNow=System.nanoTime();
        if(captureNow-lastWorldCapture<33_000_000L) return;
        lastWorldCapture=captureNow;
        Pose pose=lastRemotePose;
        Minecraft mc=Minecraft.getInstance();
        if(primary || link==null || !link.ready() || pose==null || mc.level==null) return;
        if(!mc.level.dimension().location().toString().equals(pose.dimension())) return;
        int width=mc.getMainRenderTarget().width, height=mc.getMainRenderTarget().height;
        if(width != pose.width() || height != pose.height()) {MmlfLog.sampled("render.capture","size_mismatch",1000,Map.of("width",width,"height",height,"poseWidth",pose.width(),"poseHeight",pose.height()));return;}
        if(!FrameFile.fits(width,height,FrameFile.MAX_PIXELS)) return;
        try (PixelTransfer transfer=new PixelTransfer()) {
            if(frames==null) frames=new FrameFile(session.framePath(link.id));
            int size=Math.multiplyExact(Math.multiplyExact(width,height),4);
            ensureBuffers(size);
            java.nio.ByteBuffer color=colorBuffer, depth=depthBuffer;
            org.lwjgl.opengl.GL11.glReadPixels(0,0,width,height,org.lwjgl.opengl.GL11.GL_RGBA,org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,color);
            org.lwjgl.opengl.GL11.glReadPixels(0,0,width,height,org.lwjgl.opengl.GL11.GL_DEPTH_COMPONENT,org.lwjgl.opengl.GL11.GL_FLOAT,depth);
            long id=++frameSequence;
            int slot=frames.publish(id,width,height,color,depth);
            var nativeCamera=mc.gameRenderer.getMainCamera();
            MmlfLog.sampled("camera","capture_error",1000,Map.of("positionError",nativeCamera.getPosition().distanceTo(new Vec3(pose.x(),pose.y(),pose.z())),"yawErrorDegrees",net.minecraft.util.Mth.wrapDegrees(nativeCamera.getYRot()-pose.yaw()),"pitchErrorDegrees",nativeCamera.getXRot()-pose.pitch()));
            MmlfLog.sampled("render.capture","frame",1000,Map.of("frameId",id,"cameraId",pose.cameraId(),"slot",slot,"width",width,"height",height));
            if(slot>=0) link.send("frame","*",new Frame(link.id,id,pose,slot,width,height,System.nanoTime(),true,"opengl-window-depth",link.coordinatorOffsetNanos));
        } catch(Exception e) { System.err.println("MMLF frame capture: "+e.getMessage()); }
    }
    private static void ensureBuffers(int n) {
        if(colorBuffer==null || colorBuffer.capacity()<n) {
            colorBuffer=org.lwjgl.BufferUtils.createByteBuffer(n);
            depthBuffer=org.lwjgl.BufferUtils.createByteBuffer(n).order(java.nio.ByteOrder.nativeOrder());
        }
        colorBuffer.clear().limit(n); depthBuffer.clear().limit(n);
    }
    private static void prepareDisplayFrame() {
        Frame incoming=remoteFrame;
        if(incoming==null || (displayFrame!=null && displayFrame.runtime().equals(incoming.runtime()) && displayFrame.frameId()==incoming.frameId())) return;
        try {
            if(remoteFrames==null || !incoming.runtime().equals(remoteFrameOwner)) {
                if(remoteFrames!=null) remoteFrames.close();
                remoteFrameOwner=incoming.runtime(); remoteFrames=new FrameFile(session.framePath(remoteFrameOwner));
            }
            FrameFile.Pixels pixels=remoteFrames.read(incoming,sparePixels);
            if(pixels!=null) { sparePixels=displayPixels; displayFrame=incoming; displayPixels=pixels; }
            MmlfLog.sampled("render.transfer",pixels==null?"retained_previous_frame":"read_complete",1000,Map.of("incomingFrameId",incoming.frameId(),"displayFrameId",displayFrame==null?-1:displayFrame.frameId()));
        } catch(Exception e) { MmlfLog.error("render.transfer","failed",Map.of("exception",e.getClass().getName(),"message",String.valueOf(e.getMessage()))); }
    }
    private static void composite() {
        Frame frame=displayFrame; Pose pose=frame==null?null:frame.pose(); Minecraft mc=Minecraft.getInstance();
        if (frame==null || pose==null || mc.level==null || frame.pose().cameraId()!=pose.cameraId()
            || !sameCamera(frame.pose(),pose) || !frame.depthConvention().equals("opengl-window-depth")) return;
        Camera camera=mc.gameRenderer.getMainCamera(); Vec3 cp=camera.getPosition();
        boolean aligned=Math.abs(cp.x-pose.x())<1e-5 && Math.abs(cp.y-pose.y())<1e-5 && Math.abs(cp.z-pose.z())<1e-5
            && Math.abs(camera.getYRot()-pose.yaw())<1e-4 && Math.abs(camera.getXRot()-pose.pitch())<1e-4;
        int width=mc.getMainRenderTarget().width,height=mc.getMainRenderTarget().height;
        if(width!=frame.width() || height!=frame.height() || !frame.bottomUp()) {MmlfLog.sampled("render.composite","size_mismatch",1000,Map.of("width",width,"height",height,"frameWidth",frame.width(),"frameHeight",frame.height()));return;}
        try (PixelTransfer transfer=new PixelTransfer()) {
            if(remoteFrames==null || !frame.runtime().equals(remoteFrameOwner)) {
                if(remoteFrames!=null) remoteFrames.close();
                remoteFrameOwner=frame.runtime(); remoteFrames=new FrameFile(session.framePath(remoteFrameOwner));
            }
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
            MmlfLog.sampled("render.composite","frame",1000,Map.of("frameId",frame.frameId(),"cameraId",pose.cameraId(),"changedPixels",changed,"cpuMs",(System.nanoTime()-composeStarted)/1e6,"captureToCompositeEstimateMs",Math.max(0,((System.nanoTime()+link.coordinatorOffsetNanos)-(frame.capturedNanos()+frame.coordinatorOffsetNanos()))/1e6)));
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
