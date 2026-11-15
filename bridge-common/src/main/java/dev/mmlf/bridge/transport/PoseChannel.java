package dev.mmlf.bridge.transport;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Messages.Pose;
import dev.mmlf.common.PoseFile;
import dev.mmlf.common.PoseHistory;
import java.util.Map;

/**
 * Transport layer: the shared camera pose ({@code camera.pose}). The primary publishes its camera pose there each frame;
 * a background reads the newest one. Both the render layer (where a background's camera looks) and the collaboration
 * layer (where a background's player stands, where a ray starts) read it here; neither depends on the other for it.
 * <p>
 * The file is opened from the client tick and retried once a second; it never gates the link or interaction.
 */
public final class PoseChannel {
    private PoseChannel() {}
    /** Called when a newer pose arrives: the previous one (null after a gap) and both source times. */
    public interface Received { void pose(Pose previous,long previousSourceNanos,Pose pose,long sourceNanos); }

    public static PoseFile poseFile;
    public static long nextPoseOpen;
    // primary
    static long sequence;
    /** Primary: the pose it published last. */
    public static volatile Pose primaryPose;
    /** Primary: when each recent pose was published, to measure each composited frame end to end. */
    public static final PoseHistory poseHistory=new PoseHistory(1024);
    // background
    /** Background: the newest primary pose, or null when none arrived for a second. */
    public static volatile Pose lastRemotePose;
    public static long poseSequence, poseSourceNanos, poseArrived;
    private static volatile Received received=(previous,previousSource,pose,source)->{};

    /** Registered by the entry: the render layer keeps its turn rate from the poses as they arrive. */
    public static void onReceived(Received hook) { received=hook; }

    /** Client tick: opens the pose file (publisher on the primary, reader on a background), retried once a second. */
    public static void open() {
        if(!SessionLink.joined() || poseFile!=null || System.nanoTime()-nextPoseOpen<0) return;
        nextPoseOpen=System.nanoTime()+1_000_000_000L;
        var session=SessionLink.session();
        try { poseFile=SessionLink.isPrimary() ? PoseFile.publisher(session.posePath(),session.token()) : PoseFile.reader(session.posePath(),session.token()); }
        catch(java.io.IOException e) { MmlfLog.sampled("camera","pose_file_unavailable",5000,Map.of("primary",SessionLink.isPrimary(),"reason",e.toString())); }
    }

    /** Primary: the next pose's sequence. */
    public static long nextSequence() { return ++sequence; }
    /** Primary: the sequence of the newest pose it published. */
    public static long publishedSequence() { return SessionLink.isPrimary() ? sequence : 0; }
    /** Background: the sequence of the newest primary pose it received. */
    public static long receivedSequence() { return SessionLink.isPrimary() ? 0 : poseSequence; }

    /** Primary, render thread: publishes {@code pose} as the current camera. */
    public static void publish(Pose pose) {
        primaryPose=pose;
        if(poseFile==null) return;
        try {
            long publishedAt=System.nanoTime();
            if(!poseFile.publish(sequence,publishedAt,pose)) MmlfLog.sampled("camera","pose_publish_busy",1000,Map.of());
            else poseHistory.published(sequence,publishedAt);
        } catch(java.io.IOException | IllegalArgumentException e) { MmlfLog.sampled("camera","pose_publish_failed",1000,Map.of("reason",e.toString())); }
    }

    /** Background, render thread: takes the newest pose, if newer; no cross-process wait is performed. */
    public static void poll() {
        if(SessionLink.isPrimary() || poseFile==null || !SessionLink.connected() || !SessionLink.peers().online().contains(SessionLink.session().primary())) return;
        try {
            PoseFile.Snapshot snapshot=poseFile.read();
            if(snapshot!=null && snapshot.sequence()!=poseSequence) {
                if(poseSequence!=0 && snapshot.sequence()>poseSequence+1)
                    MmlfLog.sampled("camera","pose_sequence_gap",1000,Map.of("skipped",snapshot.sequence()-poseSequence-1));
                poseSequence=snapshot.sequence();
                Pose previous=lastRemotePose; long previousSource=poseSourceNanos;
                poseArrived=System.nanoTime(); poseSourceNanos=snapshot.publishedNanos(); lastRemotePose=snapshot.pose();
                received.pose(previous,previousSource,snapshot.pose(),snapshot.publishedNanos());
            }
            if(lastRemotePose!=null && System.nanoTime()-poseArrived>1_000_000_000L) lastRemotePose=null;
        } catch(java.io.IOException e) { MmlfLog.sampled("camera","pose_read_failed",1000,Map.of("reason",e.toString())); }
    }
}
