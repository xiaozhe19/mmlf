package dev.mmlf.common;

import java.util.*;

public final class Messages {
    private Messages() {}
    /** {@code incarnation} names this process; reliable streams to and from it end when it is replaced. */
    public record Hello(String token, String loader, String minecraft, boolean primary, String incarnation) {
        public Hello(String token,String loader,String minecraft,boolean primary) { this(token,loader,minecraft,primary,UUID.randomUUID().toString()); }
    }
    public record Welcome(int slot, String primary, Set<String> runtimes, Map<String,String> incarnations) {}
    /**
     * The one logical player as its authority, the primary, sees it: everything an owner's own player must follow so
     * native logic acts for the same player. A snapshot: newer versions replace older ones, an older one is ignored.
     * {@code selected} is -1 and {@code held} null while the primary has no player (menus).
     */
    public record PlayerState(long version, int selected, String held, boolean grounded, String gameMode, String language) {}
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
    /**
     * One block a runtime owns. {@code generation} numbers the object placed at this position: a block broken and placed
     * again is a new object with a new id, so a reference to the old one (a ray hit, a pending interaction) is stale.
     */
    public record ObjectState(String id, String owner, String dimension, int x, int y, int z,
                              String type, long revision, Map<String,String> state, long generation) {
        public ObjectState(String id,String owner,String dimension,int x,int y,int z,String type,long revision,Map<String,String> state) {
            this(id,owner,dimension,x,y,z,type,revision,state,0);
        }
        public String location() { return dimension + ":" + x + ":" + y + ":" + z; }
    }
    /**
     * The plain blocks a runtime owns in one chunk, all at once (ores, plants: no block entity, no redstone), in place
     * of one {@link ObjectState} each; a newer one replaces the chunk's previous one, and one with no kinds removes it.
     * {@code positions[i]} are the positions of {@code kinds[i]}, packed by {@link #packInChunk}.
     */
    public record ChunkBlocks(String owner, String dimension, int x, int z, List<BlockKind> kinds, int[][] positions) {
        public String chunkKey() { return dimension + "@" + x + "," + z; }
        public boolean empty() { return kinds == null || kinds.isEmpty(); }
    }
    /** One block state of a {@link ChunkBlocks}: its block id and the same properties an {@link ObjectState} carries. */
    public record BlockKind(String type, Map<String,String> state) {}
    /** A block position within its chunk, as {@link ChunkBlocks} carries it: x and z within the chunk, y as is. */
    public static int packInChunk(int x, int y, int z) { return (y << 8) | ((z & 15) << 4) | (x & 15); }
    public static int unpackX(int chunkX, int packed) { return (chunkX << 4) | (packed & 15); }
    public static int unpackY(int packed) { return packed >> 8; }
    public static int unpackZ(int chunkZ, int packed) { return (chunkZ << 4) | ((packed >> 4) & 15); }
    /** The object id a ray hit on one of a runtime's {@link ChunkBlocks} carries. */
    public static String bulkId(String owner) { return "bulk@" + owner; }
    public static boolean isBulkId(String id) { return id != null && id.startsWith("bulk@"); }
    public static String objectId(String owner, String dimension, int x, int y, int z) { return objectId(owner,dimension,x,y,z,0); }
    public static String objectId(String owner, String dimension, int x, int y, int z, long generation) {
        return UUID.nameUUIDFromBytes((owner + "|" + dimension + "|" + x + "|" + y + "|" + z + (generation==0 ? "" : "|" + generation))
            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    /**
     * The redstone signal one position of the sender's world gives each neighbour, indexed by {@code Direction.ordinal()}
     * as Minecraft's {@code getSignal(pos, direction)} takes it: the direction from the neighbour asking to this position.
     * {@code weak} includes power conducted through the block, {@code strong} is the direct signal; {@code analog} is
     * what a comparator reads from the block (-1 when it has no analog output). All zero: no signal.
     */
    public record SignalState(String dimension, int x, int y, int z, int[] weak, int[] strong, int analog) {
        public SignalState(String dimension,int x,int y,int z,int[] weak,int[] strong) { this(dimension,x,y,z,weak,strong,-1); }
        public SignalState {
            if (dimension == null || weak == null || strong == null || weak.length != 6 || strong.length != 6) throw new IllegalArgumentException("invalid signal");
            for (int i = 0; i < 6; i++) if (weak[i] < 0 || weak[i] > 15 || strong[i] < 0 || strong[i] > 15) throw new IllegalArgumentException("signal out of range");
            if (analog < -1 || analog > 15) throw new IllegalArgumentException("analog signal out of range");
        }
        public String location() { return dimension + ":" + x + ":" + y + ":" + z; }
        public boolean silent() { if (analog > 0) return false; for (int i = 0; i < 6; i++) if (weak[i] != 0 || strong[i] != 0) return false; return true; }
    }
    /**
     * A vanilla block of the sender's world next to another runtime's object, as {@code BlockStateParser} writes it
     * (e.g. {@code minecraft:lever[face=floor,facing=north,powered=true]}); {@code minecraft:air} where the sender has
     * nothing the receiver could build. The receiver's mods read it as their neighbour. A null state withdraws it.
     */
    public record NeighborState(String dimension, int x, int y, int z, String state) {
        public String location() { return dimension + ":" + x + ":" + y + ":" + z; }
    }
    public record Neighbors(List<NeighborState> changes) {}
    /**
     * Items moving between a runtime's automation and another runtime's container, always through its owner:
     * {@code offer} (items held by the sender, for the owner to insert), {@code accepted} (how many it inserted; the rest
     * stays with the sender), {@code pull} (up to {@code count} items wanted out of the owner's container) and
     * {@code pulled} (the items the owner took out, now the receiver's). Each kind is answered once by {@code id}.
     */
    public record Transfer(String kind, String id, String dimension, int x, int y, int z, String face, SlotItem item, int count) {}
    /** The signals a runtime's boundary changed in one tick, in the order they changed. */
    public record Signals(List<SignalState> changes) {}
    public record Event(String topic, Map<String,String> values) {}
    public record RayRequest(String actionId, Pose pose, double reach, String action, String heldItem, boolean secondaryUse, String hand, int selectedSlot) {
        public RayRequest(String id,Pose pose,double reach) { this(id,pose,reach,"use","minecraft:air",false); }
        public RayRequest(String id,Pose pose,double reach,String action,String heldItem,boolean secondaryUse) { this(id,pose,reach,action,heldItem,secondaryUse,MAIN_HAND,-1); }
    }
    /**
     * One runtime's answer to a ray: the nearest block ({@code objectId}, position, face) or entity ({@code entity}, its
     * UUID) it owns along the ray, or a miss. An entity hit names no object; its position is the entity's block.
     */
    public record RayHit(String actionId, String objectId, long revision, double distance,
                         int x, int y, int z, String face, double hitX, double hitY, double hitZ, boolean miss, String entity) {
        public RayHit(String actionId,String objectId,long revision,double distance,int x,int y,int z,String face,double hitX,double hitY,double hitZ,boolean miss) {
            this(actionId,objectId,revision,distance,x,y,z,face,hitX,hitY,hitZ,miss,null);
        }
        public boolean entityHit() { return entity!=null; }
    }
    /**
     * An entity a runtime owns, as other runtimes need it without simulating it: where it is and how large, for collision
     * with the one player. {@code id} is the entity's UUID in its owner's world.
     */
    public record EntityState(String id, String type, double x, double y, double z, double width, double height, boolean pushable) {}
    /** Every entity {@code owner} currently owns near the player in {@code dimension}; each snapshot replaces the last. */
    public record Entities(String owner, String dimension, long sequence, List<EntityState> entities) {}
    /**
     * Damage an owner's entity did to its copy of the one player. The primary owns the player, so the owner never applies
     * it itself: the primary does, with knockback away from {@code fromX}/{@code fromZ}.
     */
    public record PlayerDamage(String attacker, String attackerType, String damageType, float amount, double fromX, double fromZ) {}
    /** Experience an owner's copy of the one player collected (orbs of its entities); the primary gives it to the player. */
    public record PlayerExperience(int amount) {}
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
