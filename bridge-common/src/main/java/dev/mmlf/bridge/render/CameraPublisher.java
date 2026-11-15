package dev.mmlf.bridge.render;
import dev.mmlf.bridge.transport.PoseChannel;
import dev.mmlf.bridge.platform.FramePacing;

import dev.mmlf.bridge.platform.Experiments;
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
 * Render layer: the shared camera pose. The primary publishes its camera; a background reads it, predicts the rotation
 * ahead and paces its frames (moved from BridgeCore, design S7).
 */
public final class CameraPublisher {
    private CameraPublisher() {}
    public static void publishCamera(Camera camera, float worldFov) {
        Minecraft mc=Minecraft.getInstance();
        if(!SessionLink.isPrimary() || !SessionLink.connected() || mc.player==null || mc.level==null) return;
        if(!Float.isFinite(worldFov) || worldFov<1 || worldFov>179) {
            MmlfLog.sampled("camera","invalid_world_fov",1000,Map.of("fov",worldFov)); return;
        }
        FrameCompositor.prepareDisplayFrame();
            Vec3 pos=camera.getPosition();
            int w=mc.getMainRenderTarget().width,h=mc.getMainRenderTarget().height;
            // Interaction and remote camera control never depend on whether the remote layer can be transferred.
            if(!FrameFile.fits(w,h,FrameFile.MAX_PIXELS)) MmlfLog.sampled("render.transfer","window_exceeds_frame_capacity",5000,Map.of("width",w,"height",h,"maxPixels",FrameFile.MAX_PIXELS));
            if(w>0 && h>0 && w<=FrameFile.MAX_EDGE && h<=FrameFile.MAX_EDGE) {
                Pose pose=new Pose(PoseChannel.nextSequence(),0,mc.level.dimension().location().toString(),pos.x,pos.y,pos.z,
                    camera.getYRot(),camera.getXRot(),worldFov,w,h,
                    mc.player.getX(),mc.player.getY(),mc.player.getZ(),
                    new org.joml.Matrix4f().rotation(new org.joml.Quaternionf(camera.rotation()).conjugate()).get(new float[16]),
                    new org.joml.Matrix4f().setPerspective((float)Math.toRadians(worldFov),(float)w/h,0.05f,mc.gameRenderer.getDepthFar()).get(new float[16]));
                Pose primaryPose=PoseChannel.primaryPose;
                if (primaryPose!=null && FrameCompositor.sameCamera(primaryPose,pose)) {
                    pose=new Pose(primaryPose.cameraId(),pose.epoch(),pose.dimension(),pose.x(),pose.y(),pose.z(),pose.yaw(),pose.pitch(),pose.fov(),pose.width(),pose.height(),pose.playerX(),pose.playerY(),pose.playerZ(),pose.view(),pose.projection());
                }
                MmlfLog.sampled("camera","requested",1000,Map.of("cameraId",pose.cameraId(),"x",pose.x(),"y",pose.y(),"z",pose.z(),"yaw",pose.yaw(),"pitch",pose.pitch(),"fov",pose.fov(),"dimension",pose.dimension()));
                PoseChannel.publish(pose);
            }
    }
    /** A background's frame rate; the GTA V passthrough runs its guest uncapped by vsync at 120 for the same reason. */
    public static final int BACKGROUND_FPS=Integer.getInteger("mmlf.backgroundFps",120);
    /** How far ahead of the newest primary pose a background renders its camera; about the remote frame's latency. */
    public static final PredictionLead predictionLead=new PredictionLead(Double.parseDouble(System.getProperty("mmlf.poseLeadMs","25")));
    static long cameraRenderStart;
    public static double yawRate,pitchRate;
    /** Render thread ({@link PoseChannel#onReceived}): keeps a smoothed turn rate so a background can aim where the primary will be looking. */
    public static void receivePose(Pose previous,long previousSourceNanos,Pose pose,long sourceNanos) {
        long now=System.nanoTime();
        double dt=(sourceNanos-previousSourceNanos)/1e9;
        if(previous!=null && (!previous.dimension().equals(pose.dimension()) || Math.abs(pose.x()-previous.x())>16 || Math.abs(pose.y()-previous.y())>16 || Math.abs(pose.z()-previous.z())>16)) { previous=null; yawRate=0; pitchRate=0; }
        if(previous!=null && dt>0.002 && dt<0.2) {
            double nextYaw=net.minecraft.util.Mth.wrapDegrees(pose.yaw()-previous.yaw())/dt;
            double nextPitch=(pose.pitch()-previous.pitch())/dt;
            yawRate=nextYaw*yawRate<0 ? nextYaw : yawRate+0.5*(nextYaw-yawRate);
            pitchRate=nextPitch*pitchRate<0 ? nextPitch : pitchRate+0.5*(nextPitch-pitchRate);
        } else { yawRate=0; pitchRate=0; }
        if(previous==null || previous.cameraId()!=pose.cameraId()) lastCameraChange=now;
    }
    /** on_demand experiment: when the primary's camera last moved (this runtime's own changes: {@link FramePacing}). */
    static volatile long lastCameraChange;
    /** Frame rate while nothing a background renders changes: frames stay well inside the primary's one-second staleness. */
    static final int IDLE_FPS=Integer.getInteger("mmlf.idleFps",10);
    static final long IDLE_AFTER_NANOS=250_000_000L;
    public static long idleFrames, busyFrames;
    /** A background's frame-rate cap: {@link #BACKGROUND_FPS}, or {@link #IDLE_FPS} while idle under on_demand. */
    public static int backgroundFps() {
        if(!Experiments.ON_DEMAND || FramePacing.busy()) { busyFrames++; return BACKGROUND_FPS; }
        long now=System.nanoTime();
        boolean idle=now-lastCameraChange>IDLE_AFTER_NANOS && now-FramePacing.lastActivity()>IDLE_AFTER_NANOS;
        if(idle) idleFrames++; else busyFrames++;
        return idle ? IDLE_FPS : BACKGROUND_FPS;
    }
    public static void beginCameraRender() {
        pollSharedPose();
        if(!SessionLink.isPrimary()) cameraRenderStart=System.nanoTime();
    }
    /** Render thread, before camera setup: the newest primary pose ({@link PoseChannel}); no cross-process wait is performed. */
    public static void pollSharedPose() {
        PoseChannel.poll();
        if(PoseChannel.lastRemotePose==null) yawRate=pitchRate=0;
    }
    /** Background camera rotation {yaw, pitch}: the primary's, extrapolated by its turn rate (at most 30 degrees). */
    public static float[] predictedRotation(Pose p) {
        long now=System.nanoTime();
        double ahead=(now-PoseChannel.poseArrived)/1e9+predictionLead.milliseconds(now)/1000.0;
        double dy=Math.max(-30,Math.min(30,yawRate*ahead)),dp=Math.max(-30,Math.min(30,pitchRate*ahead));
        return new float[]{(float)(p.yaw()+dy),(float)Math.max(-90,Math.min(90,p.pitch()+dp))};
    }
    /** Background view widening (tangent scale) that covers a turn during the remote frame's latency. */
    public static final double OVERSCAN=Double.parseDouble(System.getProperty("mmlf.overscan","1.25"));
    static final boolean ADAPTIVE_OVERSCAN=Boolean.parseBoolean(System.getProperty("mmlf.adaptiveOverscan","false"));
    public static final AdaptiveOverscan adaptiveOverscan=new AdaptiveOverscan(OVERSCAN);
    static final SampleWindow overscanSamples=new SampleWindow(2048);
    static long overscanMetricSince;
    public static double backgroundFov(Pose pose) {
        long now=System.nanoTime();
        double horizon=predictionLead.milliseconds(now)/1000.0+Math.max(0,(now-PoseChannel.poseArrived)/1e9);
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
    public static Pose renderedPose() { return SessionLink.isPrimary() ? PoseChannel.primaryPose : PoseChannel.lastRemotePose; }
    public static Pose lastRemotePose() {
        if(SessionLink.isPrimary()) { FrameCompositor.prepareDisplayFrame(); return FrameCompositor.displayFrame==null?null:FrameCompositor.displayFrame.pose(); }
        return PoseChannel.lastRemotePose;
    }
}
