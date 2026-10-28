package dev.mmlf.common;

import java.util.*;

public final class Messages {
    private Messages() {}
    public record Hello(String token, String loader, String minecraft, boolean primary) {}
    public record Welcome(int slot, String primary, Set<String> runtimes) {}
    public record Pose(long cameraId, long epoch, String dimension, double x, double y, double z,
                       float yaw, float pitch, float fov, int width, int height,
                       double playerX, double playerY, double playerZ, float[] view, float[] projection) {
        public Pose {
            if (dimension == null || width < 1 || height < 1 || width > FrameFile.MAX_EDGE || height > FrameFile.MAX_EDGE
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || fov < 1 || fov > 179)
                throw new IllegalArgumentException("invalid pose");
        }
    }
    public record ObjectState(String id, String owner, String dimension, int x, int y, int z,
                              String type, long revision, Map<String,String> state) {
        public String location() { return dimension + ":" + x + ":" + y + ":" + z; }
    }
    public static String objectId(String owner, String dimension, int x, int y, int z) {
        return UUID.nameUUIDFromBytes((owner + "|" + dimension + "|" + x + "|" + y + "|" + z)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    public record Event(String topic, Map<String,String> values) {}
    public record RayRequest(String actionId, Pose pose, double reach, String action, String heldItem, boolean secondaryUse) {
        public RayRequest(String id,Pose pose,double reach) { this(id,pose,reach,"use","minecraft:air",false); }
    }
    public record RayHit(String actionId, String objectId, long revision, double distance,
                         int x, int y, int z, String face, double hitX, double hitY, double hitZ, boolean miss) {}
    public record Interaction(String actionId, Pose pose, RayHit hit,String action,String heldItem,boolean secondaryUse) {
        public Interaction(String id,Pose pose,RayHit hit) { this(id,pose,hit,"use","minecraft:air",false); }
    }
    public record Frame(String runtime, long frameId, Pose pose, int slot, int width, int height,
                        long capturedNanos, boolean bottomUp, String depthConvention, double coordinatorOffsetNanos) {
        public Frame(String runtime,long frameId,Pose pose,int slot,int width,int height,long capturedNanos,boolean bottomUp,String depthConvention) {
            this(runtime,frameId,pose,slot,width,height,capturedNanos,bottomUp,depthConvention,0);
        }
    }
    public record Ping(long sent, long remote) {}
}
