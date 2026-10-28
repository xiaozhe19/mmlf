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
    public record RayRequest(String actionId, Pose pose, double reach, String action, String heldItem, boolean secondaryUse, String hand, int selectedSlot) {
        public RayRequest(String id,Pose pose,double reach) { this(id,pose,reach,"use","minecraft:air",false); }
        public RayRequest(String id,Pose pose,double reach,String action,String heldItem,boolean secondaryUse) { this(id,pose,reach,action,heldItem,secondaryUse,MAIN_HAND,-1); }
    }
    public record RayHit(String actionId, String objectId, long revision, double distance,
                         int x, int y, int z, String face, double hitX, double hitY, double hitZ, boolean miss) {}
    public record Interaction(String actionId, Pose pose, RayHit hit,String action,String heldItem,boolean secondaryUse,String hand,int selectedSlot) {
        public Interaction(String id,Pose pose,RayHit hit) { this(id,pose,hit,"use","minecraft:air",false); }
        public Interaction(String id,Pose pose,RayHit hit,String action,String heldItem,boolean secondaryUse) { this(id,pose,hit,action,heldItem,secondaryUse,MAIN_HAND,-1); }
        /** Older peers omit the hand; it was always the main hand. */
        public String handOrMain() { return OFF_HAND.equals(hand) ? OFF_HAND : MAIN_HAND; }
    }
    public static final String MAIN_HAND="MAIN_HAND", OFF_HAND="OFF_HAND";
    /** The owner's answer to a routed interaction; the primary learns failures instead of guessing from silence. */
    public record InteractionResult(String actionId, String runtime, boolean success, String result, String guiInstanceId, String reason) {}
    /**
     * Lifecycle of one native screen hosted by its owner and shown on the primary. {@code state} is a {@link GuiLifecycle.State}
     * name; width/height are the owner's framebuffer, {@code sequence} orders the owner's reports for one instance.
     */
    public record GuiState(String instanceId, String actionId, String runtime, String state, String screen, boolean container,
                           int guiScale, int width, int height, long sequence, String reason) {}
    /**
     * One input event for a hosted screen. Pointer coordinates are normalised to the primary's window (0..1, origin top left)
     * so the owner can map them onto its own, possibly differently sized, window.
     */
    public record GuiInput(String instanceId, long sequence, String kind, double x, double y, int button,
                           double scrollX, double scrollY, int key, int scancode, int modifiers, String text,
                           int guiScale, int width, int height, long sentNanos) {}
    /**
     * Metadata of a hosted-screen image in {@code <runtime>-gui.frames}: premultiplied RGBA, bottom row first, sized like
     * the owner's framebuffer. {@code inputSequence} is the newest input applied before it was drawn, so the primary can
     * time input to visible feedback on its own clock.
     */
    public record GuiFrame(String runtime, String sessionId, String instanceId, long frameId, int width, int height,
                           int guiScale, long capturedNanos, long inputSequence) {}
    /** One inventory slot: a registry-neutral encoded stack, or a remote reference, with display data for the primary. */
    public record SlotItem(int slot, String encoded, String reference, int count, Map<String,String> display) {}
    /**
     * The player inventory as its current authority sees it. The primary owns it outside leases; an owner hosting a
     * container screen holds a lease and reports increasing versions until it returns the inventory.
     */
    public record InventorySnapshot(String leaseId, String authority, long version, int selected, List<SlotItem> slots, String reason, List<SlotItem> before) {
        public InventorySnapshot(String leaseId,String authority,long version,int selected,List<SlotItem> slots,String reason) { this(leaseId,authority,version,selected,slots,reason,null); }
    }
    public record Frame(String runtime, long frameId, Pose pose, int slot, int width, int height,
                        long capturedNanos, boolean bottomUp, String depthConvention, double coordinatorOffsetNanos,
                        String sessionId, long cameraSequence, double clockUncertaintyNanos) {
        public Frame(String runtime,long frameId,Pose pose,int slot,int width,int height,long capturedNanos,boolean bottomUp,String depthConvention) {
            this(runtime,frameId,pose,slot,width,height,capturedNanos,bottomUp,depthConvention,0,null,0,-1);
        }
        public Frame(String runtime,long frameId,Pose pose,int slot,int width,int height,long capturedNanos,boolean bottomUp,String depthConvention,double offset) {
            this(runtime,frameId,pose,slot,width,height,capturedNanos,bottomUp,depthConvention,offset,null,0,-1);
        }
    }
    /** Metadata of a native hand or item-icon overlay; the owner's crop offset is in its framebuffer. */
    public record Visual(Frame frame, String reference, int x, int y) {}
    public record Ping(long sent, long remote) {}
}
