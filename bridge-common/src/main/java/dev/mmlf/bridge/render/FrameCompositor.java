package dev.mmlf.bridge.render;
import dev.mmlf.bridge.transport.PoseChannel;
import dev.mmlf.bridge.transport.Frames;

import dev.mmlf.bridge.platform.PixelTransfer;
import dev.mmlf.bridge.transport.SessionLink;

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
 * Render layer: world frames. A background captures and exports its frames; the primary composites the remote layers
 * into its own (moved from BridgeCore, design S7).
 */
public final class FrameCompositor {
    private FrameCompositor() {}
    /** World frames since the last metrics window, and the time between them (fps alone hides stutter); read by the entry's metrics. */
    public static long metricFrames, lastWorldFrame;
    public static final dev.mmlf.common.SampleWindow frameTimesMs=new dev.mmlf.common.SampleWindow(4096);
    static FrameFile frames;

    public static final class RemoteLayer {
        Frame displayed;
        FrameFile.Latest cursor;
        FrameFile file;
        long nextOpen;
        FrameFile.Pixels pixels, spare;
        final SampleWindow frameAgeMs=new SampleWindow(2048), angularErrorDegrees=new SampleWindow(2048);
        /** Pose published → composited, on the primary's clock; how many poses newer than the frame's existed then. */
        final SampleWindow endToEndMs=new SampleWindow(2048), posesBehind=new SampleWindow(2048);
        long metricSince, feedbackSince, lastFrameId=-1;
        int composited, repeated, stale, unknownPose;
        public void close() { if(file!=null) try { file.close(); } catch(java.io.IOException ignored) {} }
    }
    public static final Map<String,RemoteLayer> remoteLayers=new java.util.LinkedHashMap<>();
    public static Frame displayFrame;
    public static FrameFile.Pixels displayPixels;
    static boolean compositeReported;
    static java.nio.ByteBuffer colorBuffer, depthBuffer;
    /** True on the primary once a remote world frame has been received; a background runtime's world may load late. */
    public static boolean remoteLayerReady() { return SessionLink.isPrimary() && displayFrame!=null; }
    static boolean sameCamera(Pose a,Pose b) {
        return a.dimension().equals(b.dimension()) && a.width()==b.width() && a.height()==b.height()
            && Math.abs(a.x()-b.x())<1e-5 && Math.abs(a.y()-b.y())<1e-5 && Math.abs(a.z()-b.z())<1e-5
            && Math.abs(a.yaw()-b.yaw())<1e-4 && Math.abs(a.pitch()-b.pitch())<1e-4 && a.fov()==b.fov();
    }
    public static void captureFrame() {
        metricFrames++;
        long frameNow=System.nanoTime();
        if(lastWorldFrame!=0) frameTimesMs.add((frameNow-lastWorldFrame)/1e6);
        lastWorldFrame=frameNow;
        if (SessionLink.isPrimary()) { if(CPU_COMPOSITOR) composite(); else gpuComposite(); return; }
        Pose pose=PoseChannel.lastRemotePose;
        long captureNow=System.nanoTime();
        if(CameraPublisher.cameraRenderStart!=0) { CameraPublisher.predictionLead.observeRender((captureNow-CameraPublisher.cameraRenderStart)/1e6); CameraPublisher.cameraRenderStart=0; }
        Minecraft mc=Minecraft.getInstance();
        if(!SessionLink.connected() || pose==null || mc.level==null) return;
        try {
            if(frames==null) frames=Frames.writer(SessionLink.runtimeId());
            if(!mc.level.dimension().location().toString().equals(pose.dimension())) { FrameExporter.drain(frames,SessionLink.runtimeId(),false); return; }
            var target=mc.getMainRenderTarget();
            var camera=mc.gameRenderer.getMainCamera(); Vec3 at=camera.getPosition();
            // The frame carries the camera it was actually rendered with, so the primary can re-project it exactly.
            Pose rendered=new Pose(pose.cameraId(),pose.epoch(),pose.dimension(),at.x,at.y,at.z,camera.getYRot(),camera.getXRot(),pose.fov(),target.width,target.height,
                pose.playerX(),pose.playerY(),pose.playerZ(),
                new org.joml.Matrix4f().rotation(new org.joml.Quaternionf(camera.rotation()).conjugate()).get(new float[16]),
                new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix()).get(new float[16]));
            MmlfLog.sampled("camera","capture_error",1000,Map.of("positionError",at.distanceTo(new Vec3(pose.x(),pose.y(),pose.z())),"yawErrorDegrees",net.minecraft.util.Mth.wrapDegrees(camera.getYRot()-pose.yaw()),"pitchErrorDegrees",camera.getXRot()-pose.pitch()));
            FrameExporter.capture(target,rendered,frames,SessionLink.runtimeId(),SessionLink.identity(),PoseChannel.poseSequence,SessionLink.calibration());
        } catch(Exception e) { MmlfLog.sampled("render.capture","failed",1000,Map.of("exception",e.toString())); }
    }
    /** A background frame without a world pass: still publishes readbacks that completed since its last world frame. */
    public static void drainExportedFrames() {
        if(SessionLink.isPrimary() || frames==null || !SessionLink.joined()) return;
        try { FrameExporter.drain(frames,SessionLink.runtimeId(),false); } catch(Exception e) { MmlfLog.sampled("render.capture","failed",1000,Map.of("exception",e.toString())); }
    }
    /** The previous CPU compositor stays available for comparison: -Dmmlf.compositor=cpu. */
    static final boolean CPU_COMPOSITOR="cpu".equalsIgnoreCase(System.getProperty("mmlf.compositor"));
    /** A remote layer older than this is hidden rather than left frozen on screen. */
    static final long STALE_NANOS=1_000_000_000L;
    static void gpuComposite() {
        prepareDisplayFrame();
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null || !SessionLink.joined()) return;
        var target=mc.getMainRenderTarget();
        for(RemoteLayer layer:remoteLayers.values()) {
            Frame frame=layer.displayed; FrameFile.Pixels pixels=layer.pixels;
            if(frame==null || pixels==null || frame.pose()==null) continue;
            if(!mc.level.dimension().location().toString().equals(frame.pose().dimension())) continue;
            if(!"opengl-window-depth".equals(frame.depthConvention()) || !frame.bottomUp()) continue;
            FrameTiming.Age age=FrameTiming.age(frame,SessionLink.calibration(),System.nanoTime());
            if(age==null || age.estimatedNanos()+age.uncertaintyNanos()>STALE_NANOS) {
                layer.stale++;
                MmlfLog.sampled("render.composite","stale_or_uncalibrated",1000,Map.of("frameId",frame.frameId())); continue;
            }
            long metricNow=System.nanoTime();
            long publishedAt=PoseChannel.poseHistory.publishedAt(frame.cameraSequence());
            if(publishedAt!=Long.MIN_VALUE) layer.endToEndMs.add((metricNow-publishedAt)/1e6); else layer.unknownPose++;
            layer.posesBehind.add(PoseChannel.poseHistory.behind(frame.cameraSequence()));
            layer.composited++;
            if(frame.frameId()==layer.lastFrameId) layer.repeated++;
            layer.lastFrameId=frame.frameId();
            layer.frameAgeMs.add(Math.max(0,age.estimatedNanos()/1e6));
            var current=mc.gameRenderer.getMainCamera();
            double dy=net.minecraft.util.Mth.wrapDegrees(current.getYRot()-frame.pose().yaw());
            double dp=current.getXRot()-frame.pose().pitch();
            layer.angularErrorDegrees.add(Math.hypot(dy,dp));
            if(metricNow-layer.feedbackSince>=1_000_000_000L) {
                SessionLink.send("event",frame.runtime(),new Event("mmlf:latency_feedback",Map.of("captureToCompositeMs",Double.toString(Math.max(0,age.estimatedNanos()/1e6)))));
                layer.feedbackSince=metricNow;
            }
            if(layer.metricSince==0) layer.metricSince=metricNow;
            if(metricNow-layer.metricSince>=10_000_000_000L) {
                var ages=layer.frameAgeMs.drain(); var angles=layer.angularErrorDegrees.drain();
                MmlfLog.info("metrics","remote_frame",Map.of("runtime",frame.runtime(),"samples",ages.count(),
                    "ageP50Ms",ages.p50(),"ageP95Ms",ages.p95(),"ageP99Ms",ages.p99(),
                    "angularP50Degrees",angles.p50(),"angularP95Degrees",angles.p95(),"angularP99Degrees",angles.p99(),
                    "clockUncertaintyMs",age.uncertaintyNanos()/1e6));
                var e2e=layer.endToEndMs.drain(); var behind=layer.posesBehind.drain();
                Map<String,Object> sync=new java.util.LinkedHashMap<>();
                sync.put("runtime",frame.runtime()); sync.put("composited",layer.composited); sync.put("repeatedFrames",layer.repeated);
                sync.put("staleRejected",layer.stale); sync.put("unknownPose",layer.unknownPose);
                sync.put("endToEndP50Ms",e2e.p50()); sync.put("endToEndP95Ms",e2e.p95()); sync.put("endToEndP99Ms",e2e.p99());
                sync.put("posesBehindP50",behind.p50()); sync.put("posesBehindP95",behind.p95());
                sync.put("frameChannel",Frames.mode(SessionLink.session()).name());
                MmlfLog.info("metrics","frame_sync",sync);
                layer.composited=layer.repeated=layer.stale=layer.unknownPose=0;
                layer.metricSince=metricNow;
            }
            // Captured section meshes do not yet cover Sodium, block entities or animated geometry.
            // Keep the complete native frame until all content has a safe unified rendering path.
            RemoteCompositor.draw(frame,pixels,current,target.width,target.height);
            if(!compositeReported) {compositeReported=true;System.out.println("MMLF COMPOSITE READY remote="+frame.runtime()+" mode=gpu");}
        }
    }
    static void ensureBuffers(int n) {
        if(colorBuffer==null || colorBuffer.capacity()<n) {
            colorBuffer=org.lwjgl.BufferUtils.createByteBuffer(n);
            depthBuffer=org.lwjgl.BufferUtils.createByteBuffer(n).order(java.nio.ByteOrder.nativeOrder());
        }
        colorBuffer.clear().limit(n); depthBuffer.clear().limit(n);
    }
    static void prepareDisplayFrame() {
        if(!SessionLink.joined() || SessionLink.session()==null) return;
        // Backgrounds publish frames with their metadata in shared slots; the primary polls each peer's file.
        for(String peer:SessionLink.peers().online()) if(!peer.equals(SessionLink.runtimeId())) remoteLayers.computeIfAbsent(peer,id->new RemoteLayer());
        for(var entry:remoteLayers.entrySet()) {
            RemoteLayer layer=entry.getValue(); String runtime=entry.getKey();
            try {
                if(layer.file==null) {
                    long now=System.nanoTime(); if(now-layer.nextOpen<0) continue;
                    layer.nextOpen=now+500_000_000L;
                    // The background creates its file on its first capture; until then there is nothing to poll.
                    try { layer.file=Frames.reader(runtime); }
                    catch(java.io.IOException e) { MmlfLog.sampled("render.transfer","frame_file_unavailable",5000,Map.of("runtime",runtime,"reason",e.toString())); continue; }
                }
                FrameFile.Latest latest=layer.file.readLatest(layer.cursor,layer.spare);
                if(latest==null) continue;
                layer.cursor=latest;
                Frame frame=Wire.JSON.fromJson(new String(latest.metadata(),java.nio.charset.StandardCharsets.UTF_8),Frame.class);
                if(frame==null || !runtime.equals(frame.runtime()) || !SessionLink.identity().equals(frame.sessionId()) || frame.frameId()!=latest.frameId()
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
    static void composite() {
        Frame frame=displayFrame; Pose pose=frame==null?null:frame.pose(); Minecraft mc=Minecraft.getInstance();
        if (frame==null || pose==null || mc.level==null || !SessionLink.joined() || !FrameTiming.isFresh(frame,SessionLink.calibration(),System.nanoTime(),STALE_NANOS) || frame.pose().cameraId()!=pose.cameraId()
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
                var transform=new org.joml.Matrix4f().set(PoseChannel.primaryPose.projection()).mul(newView).mul(oldInverse);
                changed=DepthReprojection.composite(colors,depths,rc,rd,width,height,transform.get(new float[16]));
                MmlfLog.sampled("camera","remote_reprojected",1000,Map.of("sourceCameraId",pose.cameraId(),"currentCameraId",PoseChannel.primaryPose.cameraId(),"pixels",changed));
            }
            MmlfLog.sampled("render.composite","frame",1000,Map.of("frameId",frame.frameId(),"cameraId",pose.cameraId(),"changedPixels",changed,"cpuMs",(System.nanoTime()-composeStarted)/1e6,"captureToCompositeEstimateMs",Math.max(0,FrameTiming.age(frame,SessionLink.calibration(),System.nanoTime()).estimatedNanos()/1e6)));
            if(changed>0 && !compositeReported) {compositeReported=true;System.out.println("MMLF COMPOSITE READY remote="+frame.runtime()+" pixels="+changed);}
            int binding=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,mc.getMainRenderTarget().getColorTextureId());
            org.lwjgl.opengl.GL11.glTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,0,0,0,width,height,org.lwjgl.opengl.GL11.GL_RGBA,org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,colors);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,binding);
        } catch(Exception e) { MmlfLog.error("render.composite","failed",Map.of("exception",e.getClass().getName(),"message",String.valueOf(e.getMessage()))); }
    }
}
