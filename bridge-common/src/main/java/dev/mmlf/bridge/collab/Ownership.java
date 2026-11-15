package dev.mmlf.bridge.collab;

import dev.mmlf.bridge.transport.SessionLink;
import dev.mmlf.bridge.world.Worlds;

import dev.mmlf.common.MmlfLog;
import dev.mmlf.common.Wire;
import dev.mmlf.common.Messages.ObjectState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.util.Map;

/**
 * Collaboration layer, the one ownership authority: who owns what is at a position. Its parts are {@link Occupancy}
 * (the primary's map of other runtimes' blocks), {@link Boundary} (this runtime's blocks and the others' objects),
 * {@link OwnedVanillaStore} (vanilla blocks a background placed itself), {@link PlainEndpoints} and
 * {@link NeighborMirror}; their world-level state belongs to the open world ({@link Worlds}). Everything else, renderers
 * included, asks here.
 */
public final class Ownership {
    private Ownership() {}
    /** Other runtimes' objects' collision boxes, by object id; this world's. */
    private static final class State implements dev.mmlf.common.WorldSession.Scoped {
        final java.util.Map<String,RemoteShape> collisions=new java.util.concurrent.ConcurrentHashMap<>();
        /** Background: positions the primary rejected ("dimension" to packed position to until when), see {@link #rejected}. */
        final java.util.Map<String,java.util.Map<Long,Long>> rejected=new java.util.concurrent.ConcurrentHashMap<>();
    }
    private static final dev.mmlf.common.WorldSession.Key<State> STATE=new dev.mmlf.common.WorldSession.Key<>("ownership",State::new);
    private static State state() { var world=Worlds.current(); return world==null ? new State() : world.get(STATE); }

    private record RemoteShape(String owner,String dimension,java.util.List<net.minecraft.world.phys.shapes.VoxelShape> shapes) {}
    public static java.util.List<net.minecraft.world.phys.shapes.VoxelShape> remoteCollisions(net.minecraft.world.entity.Entity entity,net.minecraft.world.phys.AABB box) {
        if(!SessionLink.isPrimary() || !(entity instanceof net.minecraft.world.entity.player.Player) || entity.isSpectator()) return java.util.List.of();
        var result=new java.util.ArrayList<net.minecraft.world.phys.shapes.VoxelShape>();
        String dimension=entity.level().dimension().location().toString();
        for(var value:state().collisions.values()) if(value.dimension().equals(dimension))
            for(var shape:value.shapes()) if(shape.bounds().intersects(box)) result.add(shape);
        return result;
    }
    public static void updateCollision(ObjectState object) {
        if(!SessionLink.joined() || object.owner().equals(SessionLink.runtimeId())) return;
        if(object.type().equals("minecraft:air")) {state().collisions.remove(object.id());return;}
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
        state().collisions.put(object.id(),new RemoteShape(object.owner(),object.dimension(),java.util.List.copyOf(shapes)));
    }
    /** The primary owns vanilla blocks; a background owns the ones it places itself. */
    public static void ownVanilla(net.minecraft.server.level.ServerLevel level,BlockPos pos) {
        if(SessionLink.isPrimary()) return;
        OwnedVanillaStore.recorded(level.dimension().location().toString(),pos);
        Boundary.claim(level,pos);
        MmlfLog.debug("ownership","owned_vanilla",Map.of("position",pos.toShortString(),"epoch",(dev.mmlf.bridge.world.Worlds.current()==null?-1L:dev.mmlf.bridge.world.Worlds.current().epoch()),"total",OwnedVanillaStore.positions(level.dimension().location().toString()).size(),"thread",Thread.currentThread().getName()));
    }
    public static boolean ownsVanilla(Level level,BlockPos pos) {
        return level!=null && OwnedVanillaStore.positions(level.dimension().location().toString()).contains(pos);
    }
    public static boolean renderOwned(net.minecraft.world.level.block.state.BlockState state,BlockPos pos) {
        return renderOwned(Minecraft.getInstance().level,state,pos);
    }
    public static boolean renderOwned(Level level,net.minecraft.world.level.block.state.BlockState state,BlockPos pos) {
        return SessionLink.isPrimary() || !SessionLink.connected() || !dev.mmlf.bridge.platform.Experiments.BLOCK_FILTER || !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft") || ownsVanilla(level,pos);
    }
    /**
     * The owner rule for chunk-section meshers, plus positions the primary rejected. Whether the render host already
     * draws the section from this runtime's submission is the render layer's own test ({@code OwnedSectionCompiler}).
     */
    public static boolean renderOwnedInSection(net.minecraft.world.level.block.state.BlockState state,net.minecraft.core.BlockPos pos) {
        if(!SessionLink.isPrimary() && rejectedAt(Minecraft.getInstance().level,pos)) return false;
        return renderOwned(state,pos);
    }
    /** The same rule for fluids in section meshes: vanilla fluids belong to the primary unless placed here. */
    public static boolean renderOwnedFluidInSection(net.minecraft.world.level.material.FluidState fluid,BlockPos pos) {
        Level level=Minecraft.getInstance().level;
        if(!SessionLink.isPrimary() && rejectedAt(level,pos)) return false;
        return SessionLink.isPrimary() || !SessionLink.connected() || !dev.mmlf.bridge.platform.Experiments.BLOCK_FILTER || !net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getType()).getNamespace().equals("minecraft") || ownsVanilla(level,pos);
    }
    public static java.util.Set<BlockPos> ownedVanillaPositions(Level level) {
        return java.util.Collections.unmodifiableSet(OwnedVanillaStore.positions(level.dimension().location().toString()));
    }
    // ---- the primary's word on a contested position (background) ----

    /** How long a rejection hides a block: the primary repeats it every second while the position stays contested. */
    private static final long REJECTED_NANOS=5_000_000_000L;
    /**
     * Background, any thread ({@code CoGeneration.revert}): the primary has its own block where this runtime reported one
     * and asked for this runtime's to go. The block leaves this runtime's meshes now, the ones it submits to the primary
     * and its own, instead of after its server has removed it, which may be skipped (a newer report, a save point, an
     * unloaded chunk). A rejection the primary stops repeating lapses, and the block shows again if it is still here.
     */
    static void rejected(String dimension,long pos) {
        state().rejected.computeIfAbsent(dimension,d->new java.util.concurrent.ConcurrentHashMap<>()).put(pos,System.nanoTime()+REJECTED_NANOS);
        remesh(dimension,pos);
    }
    /** Background, server thread ({@code Boundary.changed}): this runtime put a block of its own there; it shows. */
    static void placedOwn(Level level,BlockPos pos) {
        var all=state().rejected; if(all.isEmpty()) return;
        String dimension=level.dimension().location().toString();
        var here=all.get(dimension);
        if(here!=null && here.remove(pos.asLong())!=null) remesh(dimension,pos.asLong());
    }
    static boolean rejectedAt(Level level,BlockPos pos) {
        if(level==null) return false;
        var all=state().rejected; if(all.isEmpty()) return false;
        var here=all.get(level.dimension().location().toString()); if(here==null) return false;
        Long until=here.get(pos.asLong());
        return until!=null && System.nanoTime()-until<0;
    }
    /** Background, client tick: lapsed rejections go, and their sections are compiled again. */
    public static void tick(Minecraft mc) {
        var all=state().rejected; if(all.isEmpty()) return;
        long now=System.nanoTime();
        for(var dimension:all.entrySet()) for(var it=dimension.getValue().entrySet().iterator();it.hasNext();) {
            var entry=it.next();
            if(now-entry.getValue()<0) continue;
            it.remove(); remesh(dimension.getKey(),entry.getKey());
        }
    }
    /** The render layer compiles the submission of a changed position again (registered by the entry). */
    private static volatile java.util.function.BiConsumer<net.minecraft.client.multiplayer.ClientLevel,BlockPos> remeshed=(level,pos)->{};
    public static void onRemesh(java.util.function.BiConsumer<net.minecraft.client.multiplayer.ClientLevel,BlockPos> hook) { remeshed=hook; }
    /** Client thread: the section holding the position is compiled again, for the primary and for this runtime. */
    private static void remesh(String dimension,long packed) {
        var mc=Minecraft.getInstance();
        mc.execute(()->{
            var level=mc.level;
            if(level==null || !level.dimension().location().toString().equals(dimension)) return;
            BlockPos pos=BlockPos.of(packed);
            remeshed.accept(level,pos);
            mc.levelRenderer.setSectionDirty(pos.getX()>>4,pos.getY()>>4,pos.getZ()>>4);
        });
    }

    /** A runtime left ({@code owner}) or the link dropped ({@code null}): its objects' collision boxes go. */
    public static void forgetCollisions(String owner) {
        if(owner==null) state().collisions.clear(); else state().collisions.values().removeIf(v->v.owner().equals(owner));
    }
}
